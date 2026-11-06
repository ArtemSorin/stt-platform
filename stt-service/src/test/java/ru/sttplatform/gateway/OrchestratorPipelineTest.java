package ru.sttplatform.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.gateway.session.Orchestrator;
import ru.sttplatform.stt.integration.IntegrationProperties;
import ru.sttplatform.stt.vosk.SpeechSession;
import ru.sttplatform.stt.vosk.SpeechSessionFactory;
import ru.sttplatform.testsupport.MiniWsServer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static ru.sttplatform.testsupport.MiniWsServer.await;

/**
 * Настоящий сетевой мост stt-service ⇄ analytics-service: Orchestrator подключается по WebSocket к двум мини-серверам
 * (STT и аналитика), Vosk подменён сценарием. Проверяется: порядок и нумерация событий, передача «конца ответа»,
 * реакция на решение/откат, защита от чужих и несогласованных событий, завершение сеанса.
 * Требует переменную окружения INTERVIEW_EVENTS_TOKEN (≥ 32 символов) — её задаёт maven-surefire.
 */
class OrchestratorPipelineTest {

    static final int FRAME_BYTES = 1600;                   // 50 мс PCM16 / 16 кГц

    @TempDir Path dir;
    MiniWsServer stt, analytics;
    final List<JsonNode> received = new CopyOnWriteArrayList<>();
    Orchestrator orchestrator;
    SpeechSessionFactory factory;
    final AtomicInteger acceptCalls = new AtomicInteger();
    volatile String partialText = "джей ви эм это виртуальная машина";
    long nextSample;
    final AtomicInteger outSeq = new AtomicInteger(1);
    volatile boolean autoReport = true;

    @BeforeEach
    void start() throws Exception {
        stt = new MiniWsServer();
        stt.onText = (conn, text) -> { if ("eof".equals(text)) conn.close(); };
        analytics = new MiniWsServer();
        analytics.onText = (conn, text) -> {
            try {
                JsonNode node = Json.MAPPER.readTree(text);
                received.add(node);
                if (autoReport && "stop".equals(node.path("type").asText()))
                    conn.sendText(Json.write(Json.map("type", "report", "sessionId", "s1", "sequence", outSeq.getAndIncrement(),
                            "report", Map.of("total", 1), "excel", Map.of("file", "r.xlsx", "url", "/r.xlsx", "path", "/secret"),
                            "excelError", "")));
            } catch (Exception e) { throw new IllegalStateException(e); }
        };

        SpeechSession speech = mock(SpeechSession.class);
        when(speech.accept(any(), anyInt())).thenAnswer(inv -> {
            int n = acceptCalls.incrementAndGet();
            byte[] frame = inv.getArgument(0);
            boolean voiced = false;
            for (byte b : frame) if (b != 0) { voiced = true; break; }
            return voiced ? new SpeechSession.Event("partial", partialText + " " + n) : null;
        });
        when(speech.finish()).thenReturn(new SpeechSession.Event("final", partialText));
        factory = mock(SpeechSessionFactory.class);
        when(factory.create()).thenReturn(speech);

        orchestrator = new Orchestrator(dir, stt.httpUrl(), analytics.httpUrl(), "s1", false, factory,
                new IntegrationProperties(null, null), null, true, "Иван");
    }

    @AfterEach
    void stop() {
        if (orchestrator != null) orchestrator.close();
        stt.close();
        analytics.close();
    }

    // ---------------------------------------------------------------- вспомогательные

    private static byte[] voiced() {
        byte[] b = new byte[FRAME_BYTES];
        for (int i = 0; i < FRAME_BYTES / 2; i++) {
            short s = (short) Math.round(6000 * Math.sin(2 * Math.PI * 220 * i / 16_000.0));
            b[2 * i] = (byte) (s & 0xff);
            b[2 * i + 1] = (byte) ((s >> 8) & 0xff);
        }
        return b;
    }

    private void feed(byte[] frame, int count) {
        for (int i = 0; i < count; i++) {
            orchestrator.submit(nextSample, frame);
            nextSample += frame.length / 2;
        }
    }

    private void sendFromAnalytics(Map<String, Object> event) throws Exception {
        analytics.awaitConnection(0, 3000).sendText(Json.write(event));
    }

    /** Событие аналитики с очередным порядковым номером. */
    private void push(String type, Object... fields) throws Exception {
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("type", type);
        event.put("sessionId", "s1");
        event.put("sequence", outSeq.getAndIncrement());
        for (int i = 0; i < fields.length; i += 2) event.put(String.valueOf(fields[i]), fields[i + 1]);
        sendFromAnalytics(event);
    }

    private JsonNode firstOfType(String type) {
        for (JsonNode n : received) if (type.equals(n.path("type").asText())) return n;
        return null;
    }

    private List<JsonNode> allOfType(String type) {
        List<JsonNode> out = new java.util.ArrayList<>();
        for (JsonNode n : received) if (type.equals(n.path("type").asText())) out.add(n);
        return out;
    }

    private void provideQuestion() throws Exception {
        assertTrue(await(() -> firstOfType("start") != null, 3000), "start не дошёл до аналитики");
        push("question", "graceMs", 0, "text", "Что такое JVM?", "followUp", false);
        assertTrue(await(() -> orchestrator.status().get("question") != null, 3000));
    }

    // ---------------------------------------------------------------- тесты

    @Test
    void connects_to_both_services_and_announces_the_session() throws Exception {
        assertTrue(await(() -> firstOfType("start") != null, 3000));
        JsonNode start = firstOfType("start");
        assertEquals("s1", start.path("sessionId").asText());
        assertEquals(0, start.path("sequence").asInt());
        assertEquals("Иван", start.path("candidate").asText());
        assertTrue(start.path("testMode").asBoolean());
        assertEquals(1, stt.connections.size());
        assertEquals("/ws/stt", stt.connections.get(0).path);
        assertEquals("/ws/interview-events", analytics.connections.get(0).path);
        assertEquals(System.getenv("INTERVIEW_EVENTS_TOKEN"), analytics.connections.get(0).headers.get("X-Internal-Token"),
                "внутренний токен должен уходить в заголовке рукопожатия");
    }

    @Test
    void candidate_audio_becomes_ordered_frame_events_and_the_end_of_answer_is_forwarded_promptly() throws Exception {
        provideQuestion();
        orchestrator.questionAsked();
        assertTrue(await(() -> firstOfType("ready") != null, 3000));
        JsonNode ready = firstOfType("ready");
        assertEquals(1, ready.path("attemptId").asInt());
        assertEquals(1, ready.path("sequence").asInt());

        feed(new byte[FRAME_BYTES], 4);                      // 200 мс тишины
        feed(voiced(), 30);                                  // 1,5 с речи
        long silenceStart = System.nanoTime();
        feed(new byte[FRAME_BYTES], 12);                     // 600 мс тишины → мягкий конец на 450 мс

        assertTrue(await(() -> allOfType("frame").stream().anyMatch(f -> "ENDPOINT".equals(f.path("vadState").asText())), 3000),
                "мягкий конец ответа не дошёл до аналитики");
        double toEndpointMs = (System.nanoTime() - silenceStart) / 1e6;
        assertTrue(toEndpointMs < 1500, "конвейер STT→аналитика не должен задерживать конец ответа: " + toEndpointMs + " мс");

        List<JsonNode> frames = allOfType("frame");
        long previous = 0;
        for (JsonNode f : received) {
            long seq = f.path("sequence").asLong();
            if (f.path("type").asText().equals("start")) continue;
            assertEquals(previous + 1, seq, "порядковые номера событий идут без пропусков");
            previous = seq;
            assertEquals(1, f.path("attemptId").asInt());
        }
        JsonNode endpoint = frames.stream().filter(f -> "ENDPOINT".equals(f.path("vadState").asText())).findFirst().orElseThrow();
        assertTrue(endpoint.path("silenceMs").asLong() >= 450);
        assertEquals("final", endpoint.path("asrType").asText(), "на конце ответа Vosk «дожимается» до final");
        assertEquals(partialText, endpoint.path("text").asText());
        assertEquals(800, endpoint.path("samples").asInt());
        assertTrue(frames.stream().anyMatch(f -> "SPEAKING".equals(f.path("vadState").asText())));
        assertTrue(frames.stream().anyMatch(f -> "partial".equals(f.path("asrType").asText())));
        verify(factory, times(2)).create();                  // при старте потока и при «ready»
    }

    @Test
    void decision_and_retract_from_analytics_are_reflected_in_the_status() throws Exception {
        provideQuestion();
        orchestrator.questionAsked();
        feed(voiced(), 20);
        push("decision", "answerId", 1, "attemptId", 1, "action", "ASK_FOLLOW_UP", "reason", "needs_detail",
                "audioSample", nextSample);
        assertTrue(await(() -> orchestrator.status().get("decision") != null, 3000));
        Map<?, ?> decision = (Map<?, ?>) orchestrator.status().get("decision");
        assertEquals("ASK_FOLLOW_UP", decision.get("action"));
        assertNotNull(decision.get("event_id"));
        assertEquals(false, orchestrator.status().get("decision_retracted"));

        push("retract", "reason", "speech_resumed", "question", Map.of("text", "Что такое JVM?", "followUp", false));
        assertTrue(await(() -> orchestrator.status().get("decision") == null, 3000));
        assertEquals(true, orchestrator.status().get("decision_retracted"));
        Map<?, ?> question = (Map<?, ?>) orchestrator.status().get("question");
        assertEquals("Что такое JVM?", question.get("text"));
    }

    @Test
    void events_with_a_foreign_session_or_a_broken_sequence_are_rejected() throws Exception {
        provideQuestion();
        sendFromAnalytics(Json.map("type", "decision", "sessionId", "ЧУЖОЙ", "sequence", 2, "answerId", 1));
        assertTrue(await(() -> orchestrator.status().get("bridge_error") != null, 3000));
        assertEquals("Analytics прислал событие с чужим sessionId.", orchestrator.status().get("bridge_error"));
        assertNull(orchestrator.status().get("decision"));

        sendFromAnalytics(Json.map("type", "decision", "sessionId", "s1", "sequence", 9, "answerId", 1));
        assertTrue(await(() -> "Нарушен порядок событий analytics-service.".equals(orchestrator.status().get("bridge_error")), 3000));
        assertNull(orchestrator.status().get("decision"), "решение вне очереди применять нельзя");
    }

    @Test
    void question_and_skip_and_tts_signals_are_forwarded() throws Exception {
        assertThrows(IllegalStateException.class, () -> orchestrator.questionAsked(), "вопроса ещё нет");
        provideQuestion();
        orchestrator.questionAsked();
        assertTrue(await(() -> firstOfType("ready") != null, 3000));

        feed(new byte[FRAME_BYTES], 4);
        orchestrator.ttsEvent("start", "Что такое JVM?", "v", "ru");
        orchestrator.ttsEvent("bogus", "x", "v", "ru");
        orchestrator.ttsEvent("finish", "Что такое JVM?", "v", "ru");
        assertTrue(await(() -> firstOfType("tts_end") != null, 3000));
        assertEquals(1, allOfType("tts_end").size(), "только finish превращается в tts_end");
        assertEquals(200, firstOfType("tts_end").path("afterMs").asLong());

        orchestrator.questionSkipped();
        assertTrue(await(() -> firstOfType("skip") != null, 3000));
    }

    @Test
    void audio_must_be_contiguous_and_is_ignored_after_close() {
        feed(voiced(), 2);
        assertThrows(IllegalStateException.class, () -> orchestrator.submit(nextSample + 5, voiced()),
                "пропуск в шкале сэмплов — потеря аудио, молча проглатывать нельзя");
        orchestrator.close();
        assertDoesNotThrow(() -> orchestrator.submit(0, voiced()));
        assertDoesNotThrow(() -> orchestrator.close());
    }

    @Test
    void finishing_flushes_the_recognizer_and_closes_the_stt_stream() throws Exception {
        provideQuestion();
        orchestrator.questionAsked();
        feed(voiced(), 10);
        orchestrator.finish(0.5, List.of());
        orchestrator.finish(0.5, List.of());                 // повторный вызов безопасен
        assertTrue(await(() -> allOfType("frame").stream().anyMatch(f -> f.path("samples").asInt() == 0), 5000),
                "завершающий кадр с остатком распознавания");
        assertTrue(stt.connections.get(0).texts.contains("eof"));
        assertNotNull(firstOfType("stop"));
        assertNull(orchestrator.status().get("bridge_error"));
        assertEquals(Map.of("total", 1), orchestrator.status().get("report"));
        assertEquals(Map.of("file", "r.xlsx", "url", "/r.xlsx"), orchestrator.status().get("excel"),
                "наружу отдаются только имя файла и ссылка, но не локальный путь");
    }

    @Test
    void audio_is_mirrored_to_the_vosk_stream() throws Exception {
        feed(voiced(), 3);
        assertTrue(await(() -> stt.connections.get(0).binaries.size() >= 3, 3000));
        assertEquals(FRAME_BYTES, stt.connections.get(0).binaries.get(0).length);
        assertEquals(3 * FRAME_BYTES / 2 / 16_000.0, (Double) orchestrator.status().get("audio_seconds"), 1e-9);
    }

    @Test
    void connection_failure_is_reported_with_a_clear_message() {
        var e = assertThrows(IllegalStateException.class, () -> new Orchestrator(dir.resolve("x"),
                "http://127.0.0.1:1", analytics.httpUrl(), "s2", false, factory, new IntegrationProperties(null, null), null, true));
        assertTrue(e.getMessage().contains("Не удалось подключиться к stt-service"));
    }

    @Test
    void analytics_connection_drop_is_surfaced_as_a_bridge_error() throws Exception {
        assertTrue(await(() -> !analytics.connections.isEmpty(), 3000));
        analytics.connections.get(0).drop();
        assertTrue(await(() -> orchestrator.status().get("bridge_error") != null, 5000));
        assertTrue(String.valueOf(orchestrator.status().get("bridge_error")).contains("аналитики"));
    }

    @Test
    void integration_properties_define_the_detector_thresholds() {
        var detector = new IntegrationProperties(300, 5000).newDetector();
        assertNotNull(detector);
        var defaults = new IntegrationProperties(null, null);
        assertEquals(450, defaults.endSilenceMs());
        assertEquals(15_000, defaults.noSpeechTimeoutMs());
    }

    @Test
    void finish_reports_a_failure_when_stop_cannot_be_delivered() throws Exception {
        autoReport = false;
        assertTrue(await(() -> !analytics.connections.isEmpty(), 3000));
        analytics.connections.get(0).drop();
        await(() -> orchestrator.status().get("bridge_error") != null, 5000);
        orchestrator.finish(0.1, List.of());
        assertNotNull(orchestrator.status().get("bridge_error"));
    }

    @Test
    void analytics_status_events_update_the_whisper_state_and_errors() throws Exception {
        provideQuestion();
        push("service_status", "whisper", "configured");
        assertTrue(await(() -> "готов".equals(orchestrator.status().get("whisper_state")), 3000));
        push("service_status", "whisper", "disabled");
        assertTrue(await(() -> "выключен".equals(orchestrator.status().get("whisper_state")), 3000));
        push("whisper_error", "message", "модель недоступна");
        assertTrue(await(() -> "ошибка: модель недоступна".equals(orchestrator.status().get("whisper_state")), 3000));
        push("finalizing", "message", "…");
        assertTrue(await(() -> "обработка".equals(orchestrator.status().get("whisper_state")), 3000));
        push("error", "message", "аналитика упала");
        assertTrue(await(() -> "аналитика упала".equals(orchestrator.status().get("bridge_error")), 3000));
    }

    @Test
    void preview_from_analytics_is_shown_only_for_candidate_speech_and_dropped_after_the_decision() throws Exception {
        provideQuestion();
        orchestrator.questionAsked();
        feed(voiced(), 20);
        push("preview", "requestId", "peek-1", "action", "ASK_FOLLOW_UP", "computeMs", 4.2);
        // в тестовом режиме роль «Кандидат» подтверждается только после появления текста распознавания
        await(() -> orchestrator.status().get("preview") != null, 1500);
        push("preview_retract", "reason", "updated");
        assertTrue(await(() -> orchestrator.status().get("preview") == null, 3000));
        push("decision", "answerId", 1, "attemptId", 1, "action", "NEXT_QUESTION", "audioSample", nextSample);
        assertTrue(await(() -> orchestrator.status().get("decision") != null, 3000));
        assertNull(orchestrator.status().get("preview"));
    }

    @Test
    void decision_measures_the_delay_between_the_end_of_speech_and_its_arrival() throws Exception {
        provideQuestion();
        orchestrator.questionAsked();
        feed(voiced(), 20);
        feed(new byte[FRAME_BYTES], 10);
        push("decision", "answerId", 1, "attemptId", 1, "action", "NEXT_QUESTION", "audioSample", nextSample);
        assertTrue(await(() -> orchestrator.status().get("decision") != null, 3000));
        Map<?, ?> decision = (Map<?, ?>) orchestrator.status().get("decision");
        assertEquals(nextSample / 16_000.0, ((Number) decision.get("answer_end_s")).doubleValue(), 0.002);
        assertNotNull(decision.get("end_to_decision_ms"));
    }
}
