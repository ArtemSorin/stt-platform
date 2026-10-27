package ru.sttplatform.gateway.session;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.gateway.clients.ApiException;
import ru.sttplatform.gateway.config.GatewayProperties;
import ru.sttplatform.diarization.session.DiarizationSession;
import ru.sttplatform.diarization.session.SessionException;
import ru.sttplatform.diarization.session.SessionRegistry;
import ru.sttplatform.stt.whisper.WhisperClient;
import ru.sttplatform.stt.vosk.SpeechSessionFactory;
import ru.sttplatform.stt.integration.IntegrationProperties;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Связывает браузер с STT и аналитикой; основной режим также запускает диаризацию внутри STT.
 * Заменяет прежние {@code BrowserMode.Interview} и вызовы {@code IntegrationBridge}.
 *
 * @version 1.0.0
 */
@Component
public class SessionManager {
    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);
    private static final Set<String> TERMINAL = Set.of("finished", "error");

    /** Состояние интервью на стороне STT-сервиса. */
    private static final class Active {
        final String id;
        final boolean testMode;
        Orchestrator orchestrator;
        DiarizationSession diarization;
        long expectedEnrollSequence, expectedLiveSequence, acceptedSamples;
        volatile boolean finalizing, done;
        volatile String phase = "enrolling";
        volatile String failure;
        Active(String id) { this(id, false); }
        Active(String id, boolean testMode) { this.id = id; this.testMode = testMode; }
    }

    private final SessionRegistry diarizationSessions;
    private final GatewayProperties props;
    private final SpeechSessionFactory speechSessions;
    private final IntegrationProperties integrationProps;
    private volatile Active current;

    /**
     * Создаёт менеджер.
     *
     * @param diarizationSessions реестр локальных сессий диаризации
     * @param props адреса сервисов и каталог результатов
     * @param whisper клиент локального Whisper helper
     * @param speechSessions фабрика Vosk-распознавателей
     * @param integrationProps настройки аудиопотока
     */
    public SessionManager(SessionRegistry diarizationSessions, GatewayProperties props, WhisperClient whisper,
                          SpeechSessionFactory speechSessions, IntegrationProperties integrationProps) {
        this.diarizationSessions = diarizationSessions;
        this.props = props;
        this.speechSessions = speechSessions;
        this.integrationProps = integrationProps;
        this.whisperClient = whisper;
    }

    private final WhisperClient whisperClient;

    /**
     * Начинает интервью: локальные модели диаризации загружаются и ждут эталон.
     *
     * @return состояние с {@code session_id}
     */
    public synchronized Map<String, Object> start() {
        Active previous = current;
        if (previous != null && previous.orchestrator != null && !previous.done)
            throw new ApiException(409, "Сначала дождитесь завершения предыдущего отчёта.");
        DiarizationSession session;
        try {
            session = diarizationSessions.start();
        } catch (SessionException error) {
            throw new ApiException(error.code(), error.getMessage());
        } catch (Exception error) {
            throw new ApiException(503, "Не удалось загрузить модели диаризации: " + error.getMessage());
        }
        if (previous != null) release(previous);
        Active next = new Active(session.id());
        next.diarization = session;
        current = next;
        return session.status(session.id());
    }

    /** Запускает отдельный тестовый режим, в котором весь микрофонный звук считается речью кандидата. */
    public synchronized Map<String, Object> startTest(String candidate, boolean whisperEnabled) {
        Active previous = current;
        if (previous != null && previous.orchestrator != null && !previous.done)
            throw new ApiException(409, "Сначала дождитесь завершения предыдущего отчёта.");
        if (previous != null && !previous.testMode && !TERMINAL.contains(previous.phase))
            throw new ApiException(409, "Сначала завершите основное интервью.");
        if (previous != null) release(previous);
        Active test = new Active(UUID.randomUUID().toString(), true);
        try {
            test.orchestrator = new Orchestrator(Path.of(props.outputDir()).toAbsolutePath().normalize()
                    .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-test-" + test.id.substring(0, 8)),
                    props.sttUrl(), props.analyticsUrl(), test.id, whisperEnabled && whisperClient.configured(), speechSessions,
                    integrationProps, whisperClient, true, candidate);
            test.phase = "live";
            current = test;
            return status(test.id);
        } catch (RuntimeException | java.io.IOException failure) {
            release(test);
            throw new ApiException(503, "Не удалось запустить тестовый режим: " + failure.getMessage());
        }
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
        try {
            requireDiarization(a).enrollAudio(sequence, pcm);
        } catch (SessionException error) {
            throw new ApiException(error.code(), error.getMessage());
        }
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
        DiarizationSession session = requireDiarization(a);
        Map<String, Object> status;
        try {
            session.finishEnrollment();
            status = session.status(a.id);
        } catch (SessionException error) {
            throw new ApiException(error.code(), error.getMessage());
        } catch (Exception error) {
            throw new ApiException(503, "Не удалось подготовить диаризацию: " + error.getMessage());
        }
        if (!"live".equals(status.get("phase"))) {
            a.phase = String.valueOf(status.get("phase"));
            return status(token);
        }
        try {
            a.orchestrator = new Orchestrator(Path.of(props.outputDir()).toAbsolutePath().normalize()
                    .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + a.id.substring(0, 8)),
                    props.sttUrl(), props.analyticsUrl(), a.id, whisperClient.configured(), speechSessions, integrationProps, whisperClient);
            session.setListener(event -> onDiarizationEvent(a, Json.MAPPER.valueToTree(event)));
        } catch (RuntimeException | java.io.IOException failure) {
            a.failure = failure.getMessage();
            release(a);
            try {
                session.requestStop("capture_error");
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
        if (a.orchestrator == null || (!a.testMode && a.diarization == null) || !"live".equals(a.phase) || a.finalizing)
            throw new ApiException(409, "Анализ аудио сейчас недоступен.");
        if (sequence != a.expectedLiveSequence) throw new ApiException(409, "Нарушен порядок чанков интервью.");
        if (!a.testMode) {
            try {
                requireDiarization(a).liveAudio(pcm);
            } catch (SessionException | ApiException e) {
                stopWithError(a, "Передача звука в диаризацию остановилась: " + e.getMessage());
                throw new ApiException(409, a.failure);
            }
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
        if (a.testMode) {
            synchronized (this) {
                if (!a.finalizing && !a.done) {
                    a.finalizing = true;
                    a.phase = "stopping";
                    Thread thread = new Thread(() -> finalizeTest(a), "finalize-test-" + a.id.substring(0, 8));
                    thread.setDaemon(true);
                    thread.start();
                }
            }
            return status(token);
        }
        if (a.diarization != null) a.diarization.requestStop(reason);
        return status(token);
    }

    /**
     * Единое состояние для браузера: фаза диаризации, роль и блок {@code integration}.
     *
     * @param token заголовок {@code X-Session-Id} (может быть {@code null})
     * @return состояние
     */
    public Map<String, Object> status(String token) {
        Active activeSession = current;
        if (activeSession != null && activeSession.testMode) {
            if (token != null && !activeSession.id.equals(token))
                throw new ApiException(403, "Неверный идентификатор интервью.");
            return testStatus(activeSession);
        }
        Map<String, Object> result = new LinkedHashMap<>(diarizationSessions.status(token));
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

    private Map<String, Object> testStatus(Active a) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("session_id", a.id);
        state.put("phase", a.done ? (a.failure == null ? "finished" : "error") : a.phase);
        state.put("audio_seconds", a.acceptedSamples / 16_000.0);
        state.put("queue_depth_chunks", 0);
        state.put("role", "Кандидат · тестовый режим без диаризации");
        if (a.failure != null) state.put("error", a.failure);
        if (a.orchestrator != null) state.put("integration", a.orchestrator.status());
        return state;
    }

    private void onDiarizationEvent(Active a, JsonNode event) {
        String type = event.path("type").asText();
        Orchestrator orchestrator = a.orchestrator;
        if (orchestrator == null) return;
        if ("diarization".equals(type)) {
            orchestrator.onDiarization(rows(event.path("segments")), rows(event.path("active_segments")),
                    event.path("audio_seconds").asDouble(), false);
        } else if ("error".equals(type)) {
            String message = "Ошибка диаризации: " + event.path("message").asText("неизвестная ошибка");
            a.failure = message;
            log.warn("{}", message);
            stopWithError(a, message);
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
        if (a.testMode) {
            Object error = a.orchestrator.status().get("bridge_error");
            if (error != null) {
                a.failure = String.valueOf(error);
                finish(a.id, "capture_error");
            }
            return;
        }
        try {
            Object bridgeError = a.orchestrator.status().get("bridge_error");
            if (bridgeError != null && !a.done) {
                stopWithError(a, "Ошибка внутренней передачи аудио/событий: " + bridgeError);
            }
            String phase = String.valueOf(requireDiarization(a).status(a.id).get("phase"));
            a.phase = phase;
            if ("stopping".equals(phase) || TERMINAL.contains(phase)) {
                a.finalizing = true;
                Thread thread = new Thread(() -> finalizeInterview(a), "finalize-" + a.id.substring(0, 8));
                thread.setDaemon(true);
                thread.start();
            }
        } catch (RuntimeException e) {
            log.debug("Опрос локальной диаризации: {}", e.toString());
        }
    }

    private void finalizeTest(Active a) {
        try {
            a.orchestrator.finish(a.acceptedSamples / 16_000.0, List.of());
        } catch (Throwable error) {
            a.failure = "Не удалось завершить тестовое интервью: " + error;
            log.error("Завершение тестового интервью", error);
        } finally {
            a.orchestrator.close();
            a.done = true;
            a.finalizing = false;
        }
    }

    private void finalizeInterview(Active a) {
        try {
            long deadline = System.nanoTime() + 120_000_000_000L;
            Map<String, Object> status;
            while (true) {
                status = requireDiarization(a).status(a.id);
                if (TERMINAL.contains(String.valueOf(status.get("phase"))) || System.nanoTime() > deadline) break;
                Thread.sleep(200);
            }
            double audioSeconds = status.get("audio_seconds") instanceof Number n ? n.doubleValue() : 0;
            a.orchestrator.finish(audioSeconds, requireDiarization(a).segments());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            a.failure = "Завершение интервью прервано.";
        } catch (Throwable e) {
            a.failure = "Не удалось завершить интервью: " + e;
            log.error("Завершение интервью", e);
        } finally {
            a.orchestrator.close();
            if (a.diarization != null) a.diarization.setListener(null);
            a.done = true;
        }
    }

    private void stopWithError(Active a, String message) {
        a.failure = message;
        if (a.testMode) {
            finish(a.id, "capture_error");
            return;
        }
        try {
            requireDiarization(a).requestStop("capture_error");
        } catch (RuntimeException error) {
            log.warn("Остановка диаризации: {}", error.toString());
        }
    }

    private DiarizationSession requireDiarization(Active a) {
        if (a.diarization == null) throw new ApiException(409, "Сессия диаризации не запущена.");
        return a.diarization;
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
        if (a.diarization != null) {
            a.diarization.setListener(null);
            if (!a.diarization.terminal()) a.diarization.requestStop("server_shutdown");
        }
    }

    @PreDestroy
    void shutdown() {
        Active a = current;
        if (a != null) release(a);
    }
}
