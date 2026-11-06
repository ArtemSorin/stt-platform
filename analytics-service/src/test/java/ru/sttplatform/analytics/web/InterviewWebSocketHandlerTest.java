package ru.sttplatform.analytics.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import ru.sttplatform.analytics.engine.DecisionPolicy;
import ru.sttplatform.analytics.engine.EndpointDetector;
import ru.sttplatform.analytics.engine.TestKb;
import ru.sttplatform.analytics.judge.FridaJudge;
import ru.sttplatform.analytics.judge.JudgeProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Внутренний протокол STT → аналитика: порядок и нумерация событий, привязка к попытке ответа (attemptId),
 * защита от повторов/пропусков и чужих сообщений, закрытие сеанса. Сам боевой путь кадров — как его шлёт Orchestrator.
 */
class InterviewWebSocketHandlerTest {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String ANSWER = "джей ви эм это виртуальная машина которая исполняет байткод джава джи эр и это среда "
            + "выполнения для запуска приложений а джи ди кей это комплект разработчика там компилятор джавак и инструменты";

    @TempDir Path reports;
    InterviewWebSocketHandler handler;
    FakeWebSocketSession ws;
    long seq;
    long attempt;

    @BeforeEach
    void setUp() {
        handler = newHandler(new InterviewProperties(null, null, null, null, null, null, null, null, null,
                reports.toString(), null, null, null, null, null));
        ws = new FakeWebSocketSession("ws-1");
    }

    InterviewWebSocketHandler newHandler(InterviewProperties props) {
        JudgeProperties judgeProps = new JudgeProperties(false, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);      // модель выключена — решает правила
        return new InterviewWebSocketHandler(TestKb.get(), new DecisionPolicy(props.maxFollowUps(), props.weakCoverage()),
                props, new ExcelReportService(props), MAPPER, new FridaJudge(judgeProps), judgeProps);
    }

    void send(Map<String, Object> event) throws Exception {
        handler.handleTextMessage(ws.session, new TextMessage(MAPPER.writeValueAsString(event)));
    }

    Map<String, Object> event(String type, Object... kv) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        e.put("sessionId", "s1");
        e.put("sequence", ++seq);
        e.put("attemptId", attempt);
        for (int i = 0; i < kv.length; i += 2) e.put((String) kv[i], kv[i + 1]);
        return e;
    }

    void start(Object... kv) throws Exception {
        Map<String, Object> start = new LinkedHashMap<>(Map.of("type", "start", "sessionId", "s1", "sequence", 0, "attemptId", 0,
                "levels", List.of("Стажёр")));
        for (int i = 0; i < kv.length; i += 2) start.put((String) kv[i], kv[i + 1]);
        send(start);
    }

    void ready(long audioStart) throws Exception {
        attempt++;
        send(event("ready", "audioStartSample", audioStart));
    }

    /** Кадры в том виде, как их шлёт Orchestrator: голос, затем тишина до мягкого конца. */
    void speakAndPause(String text, int speechFrames, int silenceFrames, long firstSample) throws Exception {
        EndpointDetector detector = new EndpointDetector(new EndpointDetector.Config());
        String[] words = text.split(" ");
        long sample = firstSample;
        for (int i = 1; i <= speechFrames + silenceFrames; i++) {
            boolean voiced = i <= speechFrames;
            byte[] pcm = new byte[1600];
            if (voiced) for (int k = 0; k < 800; k++) { short s = (short) (6000 * Math.sin(k / 3.0)); pcm[2 * k] = (byte) s; pcm[2 * k + 1] = (byte) (s >> 8); }
            int shown = voiced ? Math.max(1, words.length * i / speechFrames) : words.length;
            String partial = String.join(" ", java.util.Arrays.copyOfRange(words, 0, shown));
            EndpointDetector.State prior = detector.state();
            EndpointDetector.State vad = detector.onFrame(pcm, pcm.length, shown, false);
            String type = voiced ? "partial" : "";
            String body = voiced ? partial : "";
            if (vad == EndpointDetector.State.ENDPOINT && prior != EndpointDetector.State.ENDPOINT) { type = "final"; body = partial; }
            send(event("frame", "audioStartSample", sample, "audioEndSample", sample + 800, "samples", 800,
                    "rms", voiced ? 4000.0 : 0.0, "asrMs", 1.0, "asrType", type, "text", body, "vadState", vad.name(),
                    "audioMs", detector.audioMs(), "speechStartMs", detector.speechStartMs(),
                    "speechDurationMs", detector.speechDurationMs(), "silenceMs", detector.silenceMs()));
            sample += 800;
        }
    }

    // ---------------------------------------------------------------- старт

    @Test
    void start_creates_the_interview_announces_status_and_the_first_question() throws Exception {
        start();
        JsonNode status = ws.last("service_status");
        assertEquals("disabled", status.path("whisper").asText());
        assertEquals("rules", status.path("judge").asText(), "модель выключена — работают правила");
        assertEquals("s1", status.path("sessionId").asText());
        assertEquals(2, status.path("sequence").asInt(), "первый вопрос уходит раньше статуса сервисов");
        JsonNode question = ws.last("question");
        assertNotNull(question);
        assertEquals(1, question.path("sequence").asInt(), "нумерация исходящих событий сквозная");
        assertEquals(0, question.path("attemptId").asInt());
        assertFalse(question.path("text").asText().isBlank());
        assertFalse(question.path("followUp").asBoolean());
    }

    @Test
    void start_flags_are_reflected_and_levels_are_optional() throws Exception {
        start("whisperEnabled", true, "testMode", true, "candidate", "Иван", "levels", List.of());
        assertEquals("configured", ws.last("service_status").path("whisper").asText());
    }

    @Test
    void start_applies_the_configured_timings_to_questions() throws Exception {
        handler = newHandler(new InterviewProperties(null, null, null, 1, 300, 5000, 0.1, false, 1234, reports.toString(),
                false, 100, 200, 300, 777));
        start();
        seq = 0;
        ready(0);
        speakAndPause(ANSWER, 40, 20, 0);
        assertEquals(1, ws.count("decision"));
        assertEquals(777, ws.last("question").path("graceMs").asInt(), "окно отмены берётся из настроек");
    }

    @Test
    void invalid_starts_are_rejected_and_close_the_connection() throws Exception {
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("type", "start", "sessionId", "", "sequence", 0, "attemptId", 0),
                Map.of("type", "start", "sessionId", "s1", "sequence", 1, "attemptId", 0),
                Map.of("type", "start", "sessionId", "s1", "sequence", 0, "attemptId", 1),
                Map.of("type", "start", "sessionId", "s1", "sequence", 0, "attemptId", 0, "levels", List.of("архитектор")))) {
            FakeWebSocketSession fresh = new FakeWebSocketSession("bad-" + bad.hashCode());
            handler.handleTextMessage(fresh.session, new TextMessage(MAPPER.writeValueAsString(bad)));
            assertEquals("error", fresh.sent.get(0).path("type").asText(), bad.toString());
            assertEquals(CloseStatus.POLICY_VIOLATION, fresh.closedWith);
        }
    }

    @Test
    void second_start_on_the_same_connection_is_an_error() throws Exception {
        start();
        start();
        assertEquals("Интервью уже началось", ws.last("error").path("message").asText());
        assertEquals(CloseStatus.POLICY_VIOLATION, ws.closedWith);
    }

    // ---------------------------------------------------------------- проверки протокола

    @Test
    void events_before_start_and_with_a_foreign_session_are_rejected() throws Exception {
        send(event("ready"));
        assertEquals("Сначала отправьте start", ws.last("error").path("message").asText());
        assertNull(ws.last("error").get("sequence"), "до старта у ответа нет сессионной нумерации");

        ws = new FakeWebSocketSession("ws-2");
        start();
        seq = 0;
        Map<String, Object> foreign = event("ready");
        foreign.put("sessionId", "чужой");
        send(foreign);
        assertEquals("Неверный sessionId", ws.last("error").path("message").asText());
        assertEquals(CloseStatus.POLICY_VIOLATION, ws.closedWith);
    }

    @Test
    void duplicates_are_ignored_but_gaps_are_fatal() throws Exception {
        start();
        seq = 0;
        attempt = 1;
        Map<String, Object> ready = event("ready", "audioStartSample", 0);
        send(ready);
        int events = ws.sent.size();
        send(ready);                                              // повторная доставка: молча пропускаем
        assertEquals(events, ws.sent.size());
        assertTrue(ws.open);

        seq += 2;                                                 // потеряно событие
        send(event("skip"));
        assertEquals("Пропущено событие STT: ожидалось 2", ws.last("error").path("message").asText());
        assertEquals(CloseStatus.POLICY_VIOLATION, ws.closedWith);
    }

    @Test
    void attempt_ids_must_be_present_consecutive_and_current() throws Exception {
        start();
        seq = 0;
        Map<String, Object> noAttempt = event("ready");
        noAttempt.remove("attemptId");
        send(noAttempt);
        assertEquals("Отсутствует attemptId", ws.last("error").path("message").asText());

        ws = new FakeWebSocketSession("ws-3");
        start();
        seq = 0;
        attempt = 5;                                              // ready обязан нести следующую попытку (1)
        send(event("ready"));
        assertTrue(ws.last("error").path("message").asText().startsWith("Неверный attemptId для нового ответа"));

        ws = new FakeWebSocketSession("ws-4");
        start();
        seq = 0;
        attempt = 0;
        ready(0);
        attempt = 7;                                              // кадр от чужой попытки
        send(event("frame", "samples", 800));
        assertTrue(ws.last("error").path("message").asText().startsWith("Событие относится к неактивному ответу"));
    }

    @Test
    void unknown_events_and_malformed_json_are_errors() throws Exception {
        start();
        seq = 0;
        ready(0);
        send(event("telepathy"));
        assertEquals("Неизвестное событие: telepathy", ws.last("error").path("message").asText());
        assertThrows(IOException.class, () -> handler.handleTextMessage(ws.session, new TextMessage("{не json")));
    }

    @Test
    void binary_audio_is_forbidden() throws Exception {
        handler.handleBinaryMessage(ws.session, new BinaryMessage(new byte[]{1, 2}));
        assertEquals("Analytics принимает события, а не бинарное аудио.", ws.last("error").path("message").asText());
        assertEquals(CloseStatus.POLICY_VIOLATION, ws.closedWith);
    }

    // ---------------------------------------------------------------- боевой поток

    @Test
    void frames_produce_a_decision_and_the_next_question_with_consistent_numbering() throws Exception {
        start();
        seq = 0;
        ready(0);
        speakAndPause(ANSWER, 40, 20, 0);
        JsonNode decision = ws.last("decision");
        assertNotNull(decision, "решение принято по кадрам STT");
        assertEquals("NEXT_QUESTION", decision.path("action").asText());
        assertEquals(1, decision.path("attemptId").asInt());
        assertEquals(1, decision.path("answerId").asInt());
        long audioEnd = 60 * 800L;
        assertTrue(decision.path("audioSample").asLong() > 0 && decision.path("audioSample").asLong() <= audioEnd);
        assertTrue(decision.path("timing").path("silenceMs").asLong() <= 3000);

        long previous = 0;
        synchronized (ws.sent) {
            for (JsonNode n : ws.sent) {
                assertEquals(previous + 1, n.path("sequence").asLong(), "исходящая нумерация без пропусков: " + n);
                previous = n.path("sequence").asLong();
            }
        }
        assertEquals(1, ws.last("question").path("attemptId").asInt(), "вопрос после решения привязан к текущей попытке");
    }

    @Test
    void refinement_must_match_the_attempt_of_its_answer() throws Exception {
        start();
        seq = 0;
        ready(0);
        speakAndPause(ANSWER, 40, 20, 0);
        assertNotNull(ws.last("decision"));
        send(event("refined", "answerId", 1, "text", "точный текст ответа джей ви эм"));
        assertNotNull(ws.last("refined"));
        send(event("refine_done", "answerId", 1));
        assertTrue(ws.open);

        send(event("refined", "answerId", 99, "text", "x"));
        assertEquals("attemptId не соответствует answerId 99", ws.last("error").path("message").asText());
    }

    @Test
    void tts_skip_peek_stop_and_refine_error_are_routed() throws Exception {
        start();
        seq = 0;
        ready(0);
        send(event("tts_end", "afterMs", 1500, "audioEndSample", 100));
        send(event("peek", "requestId", "peek-1", "text", ANSWER, "audioEndSample", 4000));
        assertEquals("peek-1", ws.last("preview").path("requestId").asText());
        send(event("skip", "audioStartSample", 4000, "audioEndSample", 4000));
        assertEquals("no_answer", ws.last("decision").path("reason").asText());
        send(event("stop", "audioEndSample", 4000));
        assertNotNull(ws.last("report"));
    }

    @Test
    void report_is_saved_as_an_excel_file_and_reported_with_a_download_link() throws Exception {
        start("candidate", "Пётр Иванов", "levels", List.of());
        seq = 0;
        ready(0);
        speakAndPause(ANSWER, 40, 20, 0);
        send(event("stop", "audioEndSample", 60 * 800L));
        JsonNode report = ws.last("report");
        assertNotNull(report);
        String file = report.path("excel").path("file").asText();
        assertTrue(file.endsWith(".xlsx"), file);
        assertTrue(Files.isRegularFile(reports.resolve(file)));
        assertTrue(report.path("excel").path("url").asText().startsWith("/api/reports/"));
    }

    // ---------------------------------------------------------------- соединение

    @Test
    void closing_the_connection_releases_the_session() throws Exception {
        start();
        handler.afterConnectionClosed(ws.session, CloseStatus.NORMAL);
        handler.afterConnectionClosed(ws.session, CloseStatus.NORMAL);   // повторное закрытие безопасно
        send(event("ready"));
        assertEquals("Сначала отправьте start", ws.last("error").path("message").asText());
    }

    @Test
    void transport_errors_are_logged_without_throwing() {
        assertDoesNotThrow(() -> handler.handleTransportError(ws.session, new IOException("обрыв")));
    }

    @Test
    void nothing_is_sent_to_a_closed_socket() throws Exception {
        start();
        int before = ws.sent.size();
        ws.open = false;
        seq = 0;
        send(event("ready"));                                      // ответ-ошибка уходит в закрытый сокет — молча
        assertEquals(before, ws.sent.size());
    }

    @Test
    void send_failure_is_wrapped_into_an_unchecked_exception() throws Exception {
        ws.failOnSend = new IOException("сеть упала");
        assertThrows(java.io.UncheckedIOException.class, () -> handler.handleBinaryMessage(ws.session, new BinaryMessage(new byte[1])));
    }

    @Test
    void sessions_on_different_connections_are_independent() throws Exception {
        FakeWebSocketSession a = new FakeWebSocketSession("a"), b = new FakeWebSocketSession("b");
        handler.handleTextMessage(a.session, new TextMessage(MAPPER.writeValueAsString(
                Map.of("type", "start", "sessionId", "A", "sequence", 0, "attemptId", 0, "levels", new ArrayList<>()))));
        handler.handleTextMessage(b.session, new TextMessage(MAPPER.writeValueAsString(
                Map.of("type", "start", "sessionId", "B", "sequence", 0, "attemptId", 0, "levels", new ArrayList<>()))));
        assertEquals("A", a.last("question").path("sessionId").asText());
        assertEquals("B", b.last("question").path("sessionId").asText());
        assertEquals(a.last("question").path("sequence").asInt(), b.last("question").path("sequence").asInt());
    }
}
