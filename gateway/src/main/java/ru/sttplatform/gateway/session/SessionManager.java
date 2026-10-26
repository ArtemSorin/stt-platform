package ru.sttplatform.gateway.session;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.common.ws.WsPeer;
import ru.sttplatform.gateway.clients.ApiException;
import ru.sttplatform.gateway.clients.DiarizationClient;
import ru.sttplatform.gateway.config.GatewayProperties;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Связывает браузер с тремя микросервисами в рамках одного интервью:
 * эталон и живой звук → diarization-service, смешанный звук → stt-service,
 * подтверждённая речь кандидата → analytics-service ({@link Orchestrator}).
 * Заменяет прежние {@code BrowserMode.Interview} и вызовы {@code IntegrationBridge}.
 *
 * @version 1.0.0
 */
@Component
public class SessionManager {
    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);
    private static final Set<String> TERMINAL = Set.of("finished", "error");

    /** Состояние интервью на стороне gateway. */
    private static final class Active {
        final String id;
        Orchestrator orchestrator;
        WsPeer live;
        long expectedEnrollSequence, expectedLiveSequence, acceptedSamples;
        volatile boolean finalizing, done;
        volatile String phase = "enrolling";
        volatile String failure;
        Active(String id) { this.id = id; }
    }

    private final DiarizationClient diarization;
    private final GatewayProperties props;
    private volatile Active current;

    /**
     * Создаёт менеджер.
     *
     * @param diarization клиент diarization-service
     * @param props адреса сервисов
     */
    public SessionManager(DiarizationClient diarization, GatewayProperties props) {
        this.diarization = diarization;
        this.props = props;
    }

    /**
     * Начинает интервью: diarization-service загружает модели и ждёт эталон.
     *
     * @return состояние с {@code session_id}
     */
    public synchronized Map<String, Object> start() {
        Map<String, Object> status = diarization.start();
        Active previous = current;
        if (previous != null) release(previous);
        current = new Active(String.valueOf(status.get("session_id")));
        return status;
    }

    /**
     * Принимает кусок аудио эталона.
     *
     * @param token идентификатор сессии
     * @param sequence порядковый номер блока
     * @param pcm PCM16 16 кГц моно
     */
    public synchronized void enrollAudio(String token, long sequence, byte[] pcm) {
        Active a = require(token);
        if (sequence != a.expectedEnrollSequence) throw new ApiException(409, "Нарушен порядок чанков эталона.");
        diarization.enrollAudio(a.id, sequence, pcm);
        a.expectedEnrollSequence++;
    }

    /**
     * Завершает запись эталона; при успехе подключает STT и аналитику.
     *
     * @param token идентификатор сессии
     * @return состояние (live или retry)
     */
    public synchronized Map<String, Object> finishEnrollment(String token) {
        Active a = require(token);
        Map<String, Object> status = diarization.finishEnrollment(a.id);
        if (!"live".equals(status.get("phase"))) {
            a.phase = String.valueOf(status.get("phase"));
            return status(token);
        }
        try {
            a.orchestrator = new Orchestrator(Path.of(props.outputDir()).toAbsolutePath().normalize()
                    .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + a.id.substring(0, 8)),
                    props.sttUrl(), props.analyticsUrl());
            a.live = diarization.connectLive(a.id, event -> onDiarizationEvent(a, event));
        } catch (RuntimeException | java.io.IOException failure) {
            a.failure = failure.getMessage();
            release(a);
            try {
                diarization.finish(a.id, "capture_error");
            } catch (RuntimeException ignored) {
                // сессия диаризации уже остановлена
            }
            throw new ApiException(503, failure.getMessage());
        }
        a.phase = "live";
        return status(token);
    }

    /**
     * Принимает кадр интервью и раздаёт его: в диаризацию и в общий таймлайн оркестратора.
     *
     * @param token идентификатор сессии
     * @param sequence порядковый номер кадра
     * @param pcm PCM16 16 кГц моно
     */
    public synchronized void liveAudio(String token, long sequence, byte[] pcm) {
        Active a = require(token);
        if (a.orchestrator == null || a.live == null || !"live".equals(a.phase) || a.finalizing)
            throw new ApiException(409, "Анализ аудио сейчас недоступен.");
        if (sequence != a.expectedLiveSequence) throw new ApiException(409, "Нарушен порядок чанков интервью.");
        try {
            a.live.sendAudio(pcm);
        } catch (RuntimeException e) {
            stopWithError(a, "Передача звука в diarization-service остановилась: " + e.getMessage());
            throw new ApiException(409, a.failure);
        }
        a.expectedLiveSequence++;
        try {
            a.orchestrator.submit(a.acceptedSamples, pcm);
        } catch (RuntimeException e) {
            stopWithError(a, "Передача звука в STT остановилась: " + e.getMessage());
            throw new ApiException(409, a.failure);
        }
        a.acceptedSamples += pcm.length / 2;
    }

    /**
     * Интервьюер задал вопрос: с этого момента ответ слушается.
     *
     * @param token идентификатор сессии
     */
    public synchronized void questionAsked(String token) {
        liveOrchestrator(token).questionAsked();
    }

    /**
     * Вопрос пропущен.
     *
     * @param token идентификатор сессии
     */
    public synchronized void questionSkipped(String token) {
        liveOrchestrator(token).questionSkipped();
    }

    /**
     * Событие озвучки вопроса (для журнала).
     *
     * @param token идентификатор сессии
     * @param event start | finish | cancel | error
     * @param question текст вопроса
     * @param voice имя голоса
     * @param voiceLang язык голоса
     */
    public synchronized void ttsEvent(String token, String event, String question, String voice, String voiceLang) {
        Active a = require(token);
        if (a.orchestrator != null) a.orchestrator.ttsEvent(event, question, voice, voiceLang);
    }

    /**
     * Подсказка показана в интерфейсе (для измерения задержек).
     *
     * @param token идентификатор сессии
     * @param eventId идентификатор события
     */
    public synchronized void suggestionSeen(String token, String eventId) {
        Active a = require(token);
        if (a.orchestrator != null) a.orchestrator.suggestionSeen(eventId);
    }

    /**
     * Запрашивает остановку интервью; итоговый отчёт готовится в фоне.
     *
     * @param token идентификатор сессии
     * @param reason причина остановки
     * @return состояние
     */
    public Map<String, Object> finish(String token, String reason) {
        Active a;
        synchronized (this) {
            a = require(token);
        }
        diarization.finish(a.id, reason);
        return status(token);
    }

    /**
     * Единое состояние для браузера: фаза диаризации, роль и блок {@code integration}.
     *
     * @param token заголовок {@code X-Session-Id} (может быть {@code null})
     * @return состояние
     */
    public Map<String, Object> status(String token) {
        Map<String, Object> result = new LinkedHashMap<>(diarization.current(token));
        Active a = current;
        if (a == null || !a.id.equals(result.get("session_id"))) return result;
        String phase = String.valueOf(result.get("phase"));
        a.phase = phase;
        Orchestrator orchestrator = a.orchestrator;
        if (orchestrator != null) {
            result.put("integration", orchestrator.status());
            if (TERMINAL.contains(phase) && !a.done) result.put("phase", "stopping");   // отчёт ещё готовится
            else if (a.done) {
                Object bridgeError = orchestrator.status().get("bridge_error");
                String problem = a.failure != null ? a.failure : bridgeError == null ? null : String.valueOf(bridgeError);
                if (problem != null && result.get("error") == null) {
                    result.put("error", problem);
                    result.put("phase", "error");
                }
            }
        }
        return result;
    }

    private void onDiarizationEvent(Active a, JsonNode event) {
        String type = event.path("type").asText();
        Orchestrator orchestrator = a.orchestrator;
        if (orchestrator == null) return;
        if ("diarization".equals(type)) {
            orchestrator.onDiarization(rows(event.path("segments")), rows(event.path("active_segments")),
                    event.path("audio_seconds").asDouble(), false);
        } else if ("error".equals(type)) {
            log.warn("diarization-service: {}", event.path("message").asText());
        }
    }

    private static List<Map<String, Object>> rows(JsonNode array) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode row : array) result.add(Json.toMap(row));
        return result;
    }

    /** Следит за диаризацией: как только она остановилась, собирает итог и запрашивает отчёт аналитики. */
    @Scheduled(fixedDelay = 300, initialDelay = 1000)
    void monitor() {
        Active a = current;
        if (a == null || a.orchestrator == null || a.finalizing) return;
        try {
            String phase = String.valueOf(diarization.status(a.id).get("phase"));
            a.phase = phase;
            if ("stopping".equals(phase) || TERMINAL.contains(phase)) {
                a.finalizing = true;
                Thread thread = new Thread(() -> finalizeInterview(a), "finalize-" + a.id.substring(0, 8));
                thread.setDaemon(true);
                thread.start();
            }
        } catch (RuntimeException e) {
            log.debug("Опрос diarization-service: {}", e.toString());
        }
    }

    private void finalizeInterview(Active a) {
        try {
            long deadline = System.nanoTime() + 120_000_000_000L;
            Map<String, Object> status;
            while (true) {
                status = diarization.status(a.id);
                if (TERMINAL.contains(String.valueOf(status.get("phase"))) || System.nanoTime() > deadline) break;
                Thread.sleep(200);
            }
            double audioSeconds = status.get("audio_seconds") instanceof Number n ? n.doubleValue() : 0;
            a.orchestrator.finish(audioSeconds, diarization.segments(a.id));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            a.failure = "Завершение интервью прервано.";
        } catch (Throwable e) {
            a.failure = "Не удалось завершить интервью: " + e;
            log.error("Завершение интервью", e);
        } finally {
            a.orchestrator.close();
            if (a.live != null && a.live.isOpen()) {
                try {
                    a.live.sendClose(1000, "done");
                } catch (RuntimeException ignored) {
                    // уже закрыт
                }
            }
            a.done = true;
        }
    }

    private void stopWithError(Active a, String message) {
        a.failure = message;
        try {
            diarization.finish(a.id, "capture_error");
        } catch (RuntimeException e) {
            log.warn("Остановка диаризации: {}", e.toString());
        }
    }

    private Orchestrator liveOrchestrator(String token) {
        Active a = require(token);
        if (a.orchestrator == null || !"live".equals(a.phase) || a.finalizing)
            throw new ApiException(409, "Интервью ещё не началось.");
        return a.orchestrator;
    }

    private Active require(String token) {
        Active a = current;
        if (a == null) throw new ApiException(409, "Сначала начните запись эталона.");
        if (token == null || !a.id.equals(token)) throw new ApiException(403, "Неверный идентификатор интервью.");
        return a;
    }

    private void release(Active a) {
        if (a.orchestrator != null && !a.done) a.orchestrator.close();
        if (a.live != null && a.live.isOpen()) {
            try {
                a.live.sendClose(1000, "replaced");
            } catch (RuntimeException ignored) {
                // уже закрыт
            }
        }
    }

    @PreDestroy
    void shutdown() {
        Active a = current;
        if (a != null) release(a);
    }
}
