package ru.sttplatform.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.gateway.session.Orchestrator;
import ru.sttplatform.stt.integration.IntegrationProperties;
import ru.sttplatform.stt.vosk.SpeechSession;
import ru.sttplatform.stt.vosk.SpeechSessionFactory;
import ru.sttplatform.stt.whisper.WhisperClient;
import ru.sttplatform.testsupport.MiniWsServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static ru.sttplatform.testsupport.MiniWsServer.await;

/**
 * Whisper на живом пути: после решения аналитики Orchestrator берёт накопленное аудио ответа, отправляет его в Whisper
 * и возвращает аналитике {@code refined} + {@code refine_done}. Whisper НЕ участвует в принятии решения —
 * решение уже принято по тексту Vosk; уточнение только исправляет текст отчёта.
 */
class OrchestratorWhisperPipelineTest {

    /** Предел текстового сообщения во внутреннем канале аналитики (WebSocketConfig.setMaxTextMessageBufferSize). */
    static final int ANALYTICS_MAX_TEXT_BYTES = 32 * 1024;

    @TempDir Path dir;
    MiniWsServer stt, analytics;
    final List<JsonNode> received = new CopyOnWriteArrayList<>();
    final List<Integer> receivedBytes = new CopyOnWriteArrayList<>();
    final AtomicInteger outSeq = new AtomicInteger(1);
    Orchestrator orchestrator;
    WhisperClient whisper;
    long nextSample;

    @BeforeEach
    void start() throws Exception {
        stt = new MiniWsServer();
        stt.onText = (conn, text) -> { if ("eof".equals(text)) conn.close(); };
        analytics = new MiniWsServer();
        analytics.onText = (conn, text) -> {
            try {
                received.add(Json.MAPPER.readTree(text));
                receivedBytes.add(text.getBytes(StandardCharsets.UTF_8).length);
            } catch (Exception e) { throw new IllegalStateException(e); }
        };
        SpeechSession speech = mock(SpeechSession.class);
        when(speech.accept(any(), anyInt())).thenReturn(null);
        when(speech.finish()).thenReturn(new SpeechSession.Event("final", ""));
        SpeechSessionFactory factory = mock(SpeechSessionFactory.class);
        when(factory.create()).thenReturn(speech);
        whisper = mock(WhisperClient.class);
        orchestrator = new Orchestrator(dir.resolve("out"), stt.httpUrl(), analytics.httpUrl(), "w1", true, factory,
                new IntegrationProperties(null, null), whisper, true, "Иван", false, dir.resolve("spool"));
    }

    @AfterEach
    void stop() {
        orchestrator.close();
        stt.close();
        analytics.close();
    }

    private void push(String type, Object... fields) throws Exception {
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("type", type);
        event.put("sessionId", "w1");
        event.put("sequence", outSeq.getAndIncrement());
        for (int i = 0; i < fields.length; i += 2) event.put(String.valueOf(fields[i]), fields[i + 1]);
        analytics.awaitConnection(0, 3000).sendText(Json.write(event));
    }

    private JsonNode first(String type) {
        for (JsonNode n : received) if (type.equals(n.path("type").asText())) return n;
        return null;
    }

    private static byte[] voiced(int bytes) {
        byte[] b = new byte[bytes];
        for (int i = 0; i < bytes / 2; i++) {
            short s = (short) Math.round(6000 * Math.sin(2 * Math.PI * 220 * i / 16_000.0));
            b[2 * i] = (byte) (s & 0xff);
            b[2 * i + 1] = (byte) ((s >> 8) & 0xff);
        }
        return b;
    }

    private void answerAndDecide(double seconds) throws Exception {
        assertTrue(await(() -> first("start") != null, 3000));
        push("question", "graceMs", 0, "text", "Что такое JVM?");
        assertTrue(await(() -> orchestrator.status().get("question") != null, 3000));
        orchestrator.questionAsked();
        byte[] block = voiced(16_000);                         // 0,5 с
        for (int i = 0; i < (int) (seconds * 2); i++) {
            orchestrator.submit(nextSample, block);
            nextSample += block.length / 2;
        }
        push("decision", "answerId", 1, "attemptId", 1, "action", "NEXT_QUESTION", "audioSample", nextSample);
    }

    private static String whisperWords(int count) {
        StringBuilder sb = new StringBuilder("{\"words\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            double start = i * 0.2;
            sb.append("{\"word\":\"слово").append(i).append("\",\"start\":").append(start)
                    .append(",\"end\":").append(start + 0.15).append(",\"confidence\":0.93}");
        }
        return sb.append("]}").toString();
    }

    @Test
    void whisper_refines_the_text_after_the_decision_and_never_delays_it() throws Exception {
        when(whisper.post(anyString(), any())).thenAnswer(inv -> {
            Thread.sleep(300);                                  // Whisper медленнее решения
            return new WhisperClient.Reply(200, whisperWords(5));
        });
        long t0 = System.nanoTime();
        answerAndDecide(3);
        assertTrue(await(() -> orchestrator.status().get("decision") != null, 3000));
        double decisionMs = (System.nanoTime() - t0) / 1e6;
        assertNull(first("refined"), "в момент решения уточнения Whisper ещё нет: решение не ждёт Whisper");
        assertTrue(await(() -> first("refined") != null, 5000));
        assertTrue(await(() -> first("refine_done") != null, 5000));
        assertEquals("слово0 слово1 слово2 слово3 слово4", first("refined").path("text").asText());
        assertEquals(1, first("refined").path("answerId").asInt());
        assertEquals("", first("refine_done").path("error").asText(""));
        assertTrue(decisionMs < 3000, "решение применено за " + decisionMs + " мс");
        push("refined", "answerId", 1, "attemptId", 1, "text", "эхо");
        assertTrue(await(() -> orchestrator.status().get("refined") != null, 3000));
        assertEquals("получен", orchestrator.status().get("whisper_state"));
    }

    @Test
    void whisper_failure_is_reported_to_analytics_as_a_refinement_error() throws Exception {
        when(whisper.post(anyString(), any())).thenReturn(new WhisperClient.Reply(500, "boom"));
        answerAndDecide(2);
        assertTrue(await(() -> first("refine_done") != null, 5000));
        assertTrue(first("refine_done").path("error").asText().startsWith("Ошибка Whisper"), first("refine_done").toString());
        assertNull(first("refined"));
    }

    @Test
    void whisper_without_speech_words_reports_that_nothing_was_confirmed() throws Exception {
        when(whisper.post(anyString(), any())).thenReturn(new WhisperClient.Reply(200, "{\"words\":[]}"));
        answerAndDecide(2);
        assertTrue(await(() -> first("refine_done") != null, 5000));
        assertEquals("Whisper не вернул подтверждённую речь кандидата.", first("refine_done").path("error").asText());
    }

    @Test
    void retracted_decision_cancels_the_whisper_result() throws Exception {
        when(whisper.post(anyString(), any())).thenAnswer(inv -> {
            Thread.sleep(500);
            return new WhisperClient.Reply(200, whisperWords(3));
        });
        answerAndDecide(2);
        assertTrue(await(() -> orchestrator.status().get("decision") != null, 3000));
        push("retract", "reason", "speech_resumed");
        assertTrue(await(() -> orchestrator.status().get("decision") == null, 3000));
        Thread.sleep(900);
        assertNull(first("refined"), "результат отменённого ответа в аналитику не уходит");
        push("refined", "answerId", 1, "attemptId", 1, "text", "поздний");
        Thread.sleep(200);
        assertNull(orchestrator.status().get("refined"));
    }

    /** Известный дефект: тест падает, пока refined не будет разбит на части или предел не увеличен. Запуск: -Pknown-defects. */
    @Tag("known-defect")
    @Test
    void refined_message_for_a_long_answer_fits_the_analytics_websocket_limit() throws Exception {
        // 150 слов ≈ 30–45 секунд речи — обычный развёрнутый ответ на вопрос для middle/senior.
        when(whisper.post(anyString(), any())).thenReturn(new WhisperClient.Reply(200, whisperWords(150)));
        answerAndDecide(40);
        assertTrue(await(() -> first("refined") != null, 8000), "refined не дошёл");
        int index = -1;
        for (int i = 0; i < received.size(); i++) if ("refined".equals(received.get(i).path("type").asText())) index = i;
        int size = receivedBytes.get(index);
        assertTrue(size <= ANALYTICS_MAX_TEXT_BYTES,
                "refined на 150 слов весит " + size + " байт, а analytics-service принимает не более "
                        + ANALYTICS_MAX_TEXT_BYTES + " — соединение будет закрыто кодом 1009");
    }
}
