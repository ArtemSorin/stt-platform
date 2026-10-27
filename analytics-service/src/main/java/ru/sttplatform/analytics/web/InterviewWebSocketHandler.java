package ru.sttplatform.analytics.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import ru.sttplatform.analytics.engine.DecisionPolicy;
import ru.sttplatform.analytics.engine.EndpointDetector;
import ru.sttplatform.analytics.engine.InterviewKnowledge;
import ru.sttplatform.analytics.engine.InterviewSession;
import ru.sttplatform.analytics.engine.Level;
import ru.sttplatform.analytics.judge.FridaJudge;
import ru.sttplatform.analytics.judge.JudgeProperties;
import ru.sttplatform.analytics.live.EventSpeechEngine;
import ru.sttplatform.analytics.live.LiveInterview;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Внутренний канал событий STT → analytics. Бинарное аудио здесь запрещено.
 * @version 1.0.0
 */
@Component
public final class InterviewWebSocketHandler extends AbstractWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(InterviewWebSocketHandler.class);

    /** Состояние живого интервью, привязанное к WebSocket-соединению.
     * @version 1.0.0
     */
    private static final class Active {
        final LiveInterview live;
        final EventSpeechEngine speech;
        final String sessionId;
        final AtomicLong outboundSequence = new AtomicLong(1);
        final AtomicLong audioSample = new AtomicLong();
        final Map<Integer, Long> answerAttempts = new ConcurrentHashMap<>();
        long nextSequence = 1;
        long currentAttemptId;

        Active(LiveInterview live, EventSpeechEngine speech, String sessionId) {
            this.live = live;
            this.speech = speech;
            this.sessionId = sessionId;
        }
    }

    private final InterviewKnowledge knowledge;
    private final DecisionPolicy policy;
    private final InterviewProperties properties;
    private final ExcelReportService reports;
    private final ObjectMapper mapper;
    private final FridaJudge judge;
    private final JudgeProperties judgeProps;
    private final Map<String, Active> active = new ConcurrentHashMap<>();

    /** Создаёт обработчик внутреннего потока событий STT.
     * @param knowledge банк и анализатор вопросов
     * @param policy правила переходов интервью
     * @param properties настройки сессии
     * @param reports генератор и хранилище отчётов
     * @param mapper JSON-сериализатор
     * @param judge помощник семантической оценки
     * @param judgeProps настройки помощника оценки
     */
    public InterviewWebSocketHandler(InterviewKnowledge knowledge, DecisionPolicy policy,
                                     InterviewProperties properties, ExcelReportService reports,
                                     ObjectMapper mapper, FridaJudge judge, JudgeProperties judgeProps) {
        this.knowledge = knowledge;
        this.policy = policy;
        this.properties = properties;
        this.reports = reports;
        this.mapper = mapper;
        this.judge = judge;
        this.judgeProps = judgeProps;
    }

    /** Обрабатывает JSON-событие STT и обновляет состояние интервью.
     * @param ws внутреннее WebSocket-соединение STT-сервиса
     * @param message JSON-команда или событие протокола
     * @throws IOException при ошибке чтения или отправки JSON
     */
    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) throws IOException {
        JsonNode data = mapper.readTree(message.getPayload());
        String type = data.path("type").asText();
        try {
            if ("start".equals(type)) {
                start(ws, data);
                return;
            }
            Active session = active.get(ws.getId());
            if (session == null) throw new IllegalStateException("Сначала отправьте start");
            if (!session.sessionId.equals(data.path("sessionId").asText()))
                throw new IllegalArgumentException("Неверный sessionId");
            long sequence = data.path("sequence").asLong(-1);
            if (sequence < session.nextSequence) return;
            if (sequence != session.nextSequence)
                throw new IllegalStateException("Пропущено событие STT: ожидалось " + session.nextSequence);
            long attemptId = data.path("attemptId").asLong(-1);
            if (attemptId < 0) throw new IllegalArgumentException("Отсутствует attemptId");
            if ("ready".equals(type)) {
                if (attemptId != session.currentAttemptId + 1)
                    throw new IllegalStateException("Неверный attemptId для нового ответа: " + attemptId);
                session.currentAttemptId = attemptId;
            } else if ("refined".equals(type) || "refine_done".equals(type)) {
                int answerId = data.path("answerId").asInt(-1);
                Long expectedAttempt = session.answerAttempts.get(answerId);
                if (expectedAttempt == null || expectedAttempt != attemptId)
                    throw new IllegalStateException("attemptId не соответствует answerId " + answerId);
            } else if (attemptId != session.currentAttemptId) {
                throw new IllegalStateException("Событие относится к неактивному ответу " + attemptId);
            }
            session.nextSequence++;
            long audioEnd = data.path("audioEndSample").asLong(-1);
            long audioStart = data.path("audioStartSample").asLong(-1);
            if (audioEnd >= 0) session.audioSample.accumulateAndGet(audioEnd, Math::max);
            else if (audioStart >= 0) session.audioSample.accumulateAndGet(audioStart, Math::max);
            switch (type) {
                case "ready" -> session.live.onReady();
                case "tts_end" -> session.live.onTtsEnd(data.path("afterMs").asLong(0));
                case "skip" -> session.live.onSkip();
                case "stop" -> session.live.onStop();
                case "peek" -> session.live.onPeek(data.path("text").asText(""), data.path("requestId").asText(""));
                case "frame" -> session.live.onSttFrame(data.path("asrType").asText(""),
                        data.path("text").asText(""), data.path("samples").asInt(0),
                        data.path("rms").asDouble(0), data.path("asrMs").asDouble(0),
                        data.path("vadState").asText("WAITING"), data.path("audioMs").asLong(0),
                        data.path("speechStartMs").asLong(-1), data.path("speechDurationMs").asLong(0),
                        data.path("silenceMs").asLong(0));
                case "refined" -> session.live.onRefined(data.path("answerId").asInt(-1), data.path("text").asText(""));
                case "refine_done" -> session.live.onRefineDone(data.path("answerId").asInt(-1),
                        data.path("error").asText(""));
                default -> throw new IllegalArgumentException("Неизвестное событие: " + type);
            }
        } catch (RuntimeException error) {
            log.warn("Ошибка внутреннего события '{}': {}", type, error.toString());
            Active session = active.get(ws.getId());
            Map<String, Object> failure = Map.of("type", "error", "message", String.valueOf(error.getMessage()));
            if (session == null) send(ws, failure);
            else sendSession(ws, session, failure);
            ws.close(CloseStatus.POLICY_VIOLATION);
        }
    }

    /** Отклоняет бинарные кадры: аудио принимает только STT-сервис.
     * @param ws внутреннее WebSocket-соединение STT-сервиса
     * @param message бинарный кадр, запрещённый этим протоколом
     * @throws IOException при ошибке закрытия WebSocket-соединения
     */
    @Override
    protected void handleBinaryMessage(WebSocketSession ws, BinaryMessage message) throws IOException {
        send(ws, Map.of("type", "error", "message", "Analytics принимает события, а не бинарное аудио."));
        ws.close(CloseStatus.POLICY_VIOLATION);
    }

    private void start(WebSocketSession ws, JsonNode data) throws IOException {
        if (active.containsKey(ws.getId())) throw new IllegalStateException("Интервью уже началось");
        String sessionId = data.path("sessionId").asText("");
        if (sessionId.isBlank()) throw new IllegalArgumentException("Отсутствует sessionId");
        if (data.path("sequence").asLong(-1) != 0 || data.path("attemptId").asLong(-1) != 0)
            throw new IllegalArgumentException("Старт должен иметь sequence=0 и attemptId=0");
        List<Level> levels = new ArrayList<>();
        for (JsonNode level : data.path("levels")) levels.add(Level.parse(level.asText()));

        EndpointDetector.Config config = new EndpointDetector.Config();
        config.baseSilenceMs = properties.endSilenceMs();
        config.noSpeechTimeoutMs = properties.noSpeechTimeoutMs();
        EventSpeechEngine speech = new EventSpeechEngine();
        Active[] holder = new Active[1];
        LiveInterview live = new LiveInterview(speech, new InterviewSession(knowledge, levels, policy),
                new EndpointDetector(config), event -> {
                    Active current = holder[0];
                    if (current != null) sendSession(ws, current, event);
                });
        live.setJudge(judge, judgeProps.decisionDeadlineMs());
        live.setTiming(new LiveInterview.Timing(properties.nextExtraMs(), properties.otherNextExtraMs(),
                properties.followUpExtraMs(), properties.graceMs()));
        boolean whisperOn = data.path("whisperEnabled").asBoolean(false);
        live.setRefinementWait(whisperOn ? properties.whisperWaitMs() : 0);
        boolean testMode = data.path("testMode").asBoolean(false);
        live.setExport(reports, data.path("candidate").asText(""), settings(whisperOn, testMode));
        Active session = new Active(live, speech, sessionId);
        holder[0] = session;
        active.put(ws.getId(), session);
        live.start();
        sendSession(ws, session, Map.of("type", "service_status", "whisper", whisperOn ? "configured" : "disabled",
                "judge", judge.ready() ? "frida" : "rules"));
    }

    private Map<String, String> settings(boolean whisperOn, boolean testMode) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("Распознавание речи", testMode ? "Vosk; тестовый режим без диаризации (весь микрофон — кандидат)"
                : "Vosk + диаризация в stt-service");
        settings.put("Второй проход (Whisper)", whisperOn ? "включён: stt-service" : "выключен");
        settings.put("Окончание ответа", "Определяется stt-service по тишине");
        settings.put("Порог тишины", properties.endSilenceMs() + " мс");
        settings.put("Окно отмены решения", properties.graceMs() + " мс");
        settings.put("Макс. уточнений", String.valueOf(properties.maxFollowUps()));
        settings.put("Анализ ответов", judge.ready() ? "FRIDA-Decisions с резервными правилами" : "правила на ключевых словах");
        return settings;
    }

    /** Освобождает временный движок речи после закрытия соединения.
     * @param ws завершённое WebSocket-соединение
     * @param status причина закрытия соединения
     */
    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        Active session = active.remove(ws.getId());
        if (session != null) session.speech.close();
    }

    /** Записывает транспортную ошибку внутреннего WebSocket-канала.
     * @param ws соединение, в котором произошла ошибка
     * @param error причина сбоя транспорта
     */
    @Override
    public void handleTransportError(WebSocketSession ws, Throwable error) {
        log.warn("Ошибка внутреннего WebSocket {}: {}", ws.getId(), error.toString());
    }

    private void sendSession(WebSocketSession ws, Active session, Map<String, ?> payload) {
        synchronized (ws) {
            Map<String, Object> event = new LinkedHashMap<>();
            payload.forEach(event::put);
            if ("decision".equals(event.get("type")) && event.get("answerId") instanceof Number id)
                session.answerAttempts.put(id.intValue(), session.currentAttemptId);
            event.put("sessionId", session.sessionId);
            event.put("sequence", session.outboundSequence.getAndIncrement());
            event.put("attemptId", session.currentAttemptId);
            event.put("audioSample", session.audioSample.get());
            send(ws, event);
        }
    }

    private void send(WebSocketSession ws, Map<String, ?> event) {
        try {
            String json = mapper.writeValueAsString(event);
            synchronized (ws) {
                if (ws.isOpen()) ws.sendMessage(new TextMessage(json));
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
