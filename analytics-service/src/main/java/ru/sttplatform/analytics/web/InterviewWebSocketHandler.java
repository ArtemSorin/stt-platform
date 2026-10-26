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
import ru.sttplatform.analytics.live.LiveInterview;
import ru.sttplatform.analytics.live.RemoteSpeechEngine;
import ru.sttplatform.analytics.live.SpeechEngine;
import ru.sttplatform.analytics.live.SttWhisperClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /ws/interview} — анализ ответов в реальном времени.
 * <pre>
 *  клиент → сервер (text JSON): start | ready | peek | tts_end | skip | stop
 *  клиент → сервер (binary):    PCM 16 кГц / 16 бит / моно (кадры ~50 мс) — речь, которую нужно оценивать
 *  сервер → клиент (text JSON): question | partial | preview | decision | retract | refined | report | error …
 * </pre>
 * Распознавание (Vosk) и второй проход (Whisper) выполняет микросервис stt-service.
 *
 * @version 2.0.0
 */
@Component
public class InterviewWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(InterviewWebSocketHandler.class);

    private record Active(LiveInterview live, SpeechEngine engine) {}

    private final InterviewKnowledge kb;
    private final DecisionPolicy policy;
    private final InterviewProperties props;
    private final ExcelReportService reports;
    private final ObjectMapper mapper;
    private final SttWhisperClient whisper;
    private final FridaJudge judge;
    private final JudgeProperties judgeProps;
    private final Map<String, Active> active = new ConcurrentHashMap<>();

    /**
     * Создаёт обработчик.
     *
     * @param kb скомпилированный банк знаний
     * @param policy политика выбора следующего действия
     * @param props настройки сервиса
     * @param mapper JSON-маппер
     * @param reports сервис файлов отчётов
     */
    public InterviewWebSocketHandler(InterviewKnowledge kb, DecisionPolicy policy, InterviewProperties props,
                                     ObjectMapper mapper, ExcelReportService reports,
                                     FridaJudge judge, JudgeProperties judgeProps) {
        this.kb = kb;
        this.policy = policy;
        this.props = props;
        this.mapper = mapper;
        this.reports = reports;
        this.judge = judge;
        this.judgeProps = judgeProps;
        this.whisper = props.whisperEnabled() ? new SttWhisperClient(props.sttUrl(), Duration.ofSeconds(60)) : null;
        log.info("STT: {}; второй проход Whisper: {}", props.sttUrl(), whisper == null ? "выключен" : "через stt-service");
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) throws IOException {
        JsonNode n = mapper.readTree(message.getPayload());
        String type = n.path("type").asText();
        try {
            if ("start".equals(type)) {
                start(ws, n);
                return;
            }
            Active a = active.get(ws.getId());
            if (a == null) return;
            switch (type) {
                case "ready" -> a.live().onReady();
                case "peek" -> a.live().onPeek(n.path("text").asText(""), n.path("requestId").asText(""));
                case "tts_end" -> a.live().onTtsEnd(n.path("afterMs").asLong(0));
                case "skip" -> a.live().onSkip();
                case "stop" -> a.live().onStop();
                default -> { }
            }
        } catch (RuntimeException e) {
            log.warn("Ошибка обработки '{}': {}", type, e.toString());
            send(ws, Map.of("type", "error", "message", String.valueOf(e.getMessage())));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession ws, BinaryMessage message) {
        Active a = active.get(ws.getId());
        if (a == null) return;
        ByteBuffer buf = message.getPayload();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        try {
            a.live().onAudio(data, data.length);
        } catch (RuntimeException e) {
            log.warn("Ошибка обработки аудио: {}", e.toString());
        }
    }

    private void start(WebSocketSession ws, JsonNode n) {
        if (active.containsKey(ws.getId())) return;
        List<Level> levels = new ArrayList<>();
        for (JsonNode l : n.path("levels")) levels.add(Level.parse(l.asText()));

        EndpointDetector.Config cfg = new EndpointDetector.Config();
        cfg.baseSilenceMs = props.endSilenceMs();
        cfg.noSpeechTimeoutMs = props.noSpeechTimeoutMs();

        SpeechEngine engine;
        try {
            engine = RemoteSpeechEngine.connect(props.sttUrl(), props.sttFlushTimeoutMs(),
                    problem -> send(ws, Map.of("type", "error", "message", problem)));
        } catch (RuntimeException e) {
            log.warn("stt-service недоступен: {}", e.toString());
            send(ws, Map.of("type", "error", "message",
                    "Сервис распознавания речи (stt-service) недоступен: " + props.sttUrl()));
            return;
        }

        Map<String, Object> whisperState = whisper == null
                ? Map.of("configured", false, "reachable", false) : whisper.status();
        boolean whisperOn = Boolean.TRUE.equals(whisperState.get("configured"));

        LiveInterview live = new LiveInterview(engine, new InterviewSession(kb, levels, policy),
                new EndpointDetector(cfg), msg -> send(ws, msg));
        if (whisperOn) live.setTranscriber(whisper, props.whisperWaitMs());
        live.setJudge(judge, judgeProps.decisionDeadlineMs());
        live.setTiming(new LiveInterview.Timing(props.nextExtraMs(), props.otherNextExtraMs(),
                props.followUpExtraMs(), props.graceMs()));
        live.setExport(reports, n.path("candidate").asText(""), settings(whisperOn));
        active.put(ws.getId(), new Active(live, engine));
        live.start();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("type", "service_status");
        status.put("whisper", whisperOn ? "configured" : "disabled");
        status.put("whisperReachable", Boolean.TRUE.equals(whisperState.get("reachable")));
        status.put("whisperUrl", whisperOn ? props.sttUrl() + "/api/whisper" : "");
        status.put("judge", judge.ready() ? "frida" : "rules");
        send(ws, status);
    }

    /** Параметры интервью для листа «Методика» в Excel-отчёте. */
    private Map<String, String> settings(boolean whisperOn) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Распознавание речи", "Vosk (потоковое, русская модель) через stt-service");
        m.put("Порог тишины (мягкий конец ответа)", props.endSilenceMs() + " мс");
        m.put("Доп. пауза до решения: достаточный ответ / «не знаю» / нужно уточнение",
                props.nextExtraMs() + " / " + props.otherNextExtraMs() + " / " + props.followUpExtraMs() + " мс");
        m.put("Окно отмены решения", props.graceMs() + " мс");
        m.put("Макс. уточняющих вопросов на вопрос", String.valueOf(props.maxFollowUps()));
        m.put("Порог «слабого» ответа (покрытие)", Math.round(props.weakCoverage() * 100) + " %");
        m.put("Таймаут молчания (нет ответа)", props.noSpeechTimeoutMs() / 1000 + " с");
        m.put("Второй проход (Whisper)", whisperOn ? "включён: stt-service" : "выключен");
        m.put("Анализ ответов", judge.ready()
                ? "смысловой: FRIDA-Decisions (" + judgeProps.url() + "), бюджет ожидания " + judgeProps.decisionDeadlineMs()
                + " мс, запасной вариант — правила на ключевых словах"
                : "правила на ключевых словах (модель FRIDA-Decisions выключена или недоступна)");
        m.put("Порог «ответ достаточен»", "по уровню вопроса + " + Math.round(judgeProps.sufficientShift() * 100) + " п.п.");
        return m;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        Active a = active.remove(ws.getId());
        if (a != null) a.engine().close();
    }

    @Override
    public void handleTransportError(WebSocketSession ws, Throwable ex) {
        log.warn("Ошибка WebSocket {}: {}", ws.getId(), ex.toString());
    }

    /** Whisper и STT отвечают из других потоков, а WebSocketSession не терпит параллельной записи — синхронизируем. */
    private void send(WebSocketSession ws, Map<String, ?> msg) {
        try {
            String json = mapper.writeValueAsString(msg);
            synchronized (ws) {
                if (ws.isOpen()) ws.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
