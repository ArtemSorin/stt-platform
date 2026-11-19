package ru.sttplatform.gateway;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.gateway.session.Orchestrator;
import ru.sttplatform.stt.integration.IntegrationProperties;
import ru.sttplatform.stt.whisper.WhisperClient;
import ru.sttplatform.testsupport.MiniWsServer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static ru.sttplatform.testsupport.MiniWsServer.await;

class DeferredFinalizationPipelineTest {
    @ParameterizedTest
    @CsvSource({"false,false,false,false,false,false", "true,false,false,false,false,false", "false,true,false,false,false,false", "true,true,false,false,false,false", "false,false,true,false,false,false", "false,false,false,true,false,false", "false,false,false,false,true,false", "false,false,false,false,true,true"})
    void full_or_fallback_answer_is_sent_before_report(boolean whisperFails, boolean twoChannels, boolean captureLost,
                                                       boolean whisperDisabled, boolean voskGap, boolean overlap, @TempDir Path dir) throws Exception {
        try (var stt = new MiniWsServer(); var analytics = new MiniWsServer()) {
            stt.onText = (conn, text) -> { if ("eof".equals(text)) conn.close(); };
            var seen = new CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
            AtomicInteger seq = new AtomicInteger(1);
            analytics.onText = (conn, text) -> {
                try {
                    var event = Json.tree(text); seen.add(event);
                    if ("start".equals(event.path("type").asText()))
                        conn.sendText(Json.write(Json.map("type", "question", "questionId", "Q1", "text", "Вопрос?", "sessionId", "s", "sequence", seq.getAndIncrement(), "attemptId", 0, "audioSample", 0)));
                    if ("prepare_final".equals(event.path("type").asText()))
                        conn.sendText(Json.write(Json.map("type", "finalization_ready", "sessionId", "s", "sequence", seq.getAndIncrement(), "attemptId", 1, "audioSample", 16000)));
                    if ("stop".equals(event.path("type").asText()))
                        conn.sendText(Json.write(Json.map("type", "report", "sessionId", "s", "sequence", seq.getAndIncrement(), "attemptId", 1, "audioSample", 16000, "report", java.util.Map.of("total", 5))));
                } catch (Exception e) { throw new RuntimeException(e); }
            };
            WhisperClient whisper = mock(WhisperClient.class);
            AtomicInteger whisperCalls = new AtomicInteger();
            when(whisper.post(anyString(), any())).thenAnswer(call -> {
                int callIndex = whisperCalls.getAndIncrement();
                if (overlap && callIndex < 2) return new WhisperClient.Reply(503, "error");
                if (whisperFails && (!twoChannels || callIndex > 0)) return new WhisperClient.Reply(503, "error");
                String word = callIndex == 0 || overlap ? "финальный" : "интервьюер";
                return new WhisperClient.Reply(200, "{\"words\":[{\"word\":\"" + word + "\",\"start\":0.1,\"end\":0.4}]}");
            });
            try (var orchestrator = new Orchestrator(dir.resolve("out"), stt.httpUrl(), analytics.httpUrl(), "s", !whisperDisabled,
                    new IntegrationProperties(null, null), whisper, !twoChannels, "", twoChannels, dir.resolve("spool"), true)) {
                assertTrue(await(() -> orchestrator.status().get("question") != null, 3000));
                orchestrator.questionAsked();
                assertTrue(await(() -> seen.stream().anyMatch(e -> "ready".equals(e.path("type").asText())), 3000));
                if (twoChannels) orchestrator.submitChannels(0, new byte[32000], new byte[32000]);
                else orchestrator.submit(0, new byte[32000]);
                if (voskGap) {
                    var words = new java.util.ArrayList<java.util.Map<String, Object>>();
                    if (overlap) words.add(Json.map("word", "дубликат", "start", 0.2, "end", 0.3));
                    words.add(Json.map("word", "резерв", "start", 0.7, "end", 0.9));
                    stt.awaitConnection(0, 3000).sendText(Json.write(Json.map("type", "final", "text", "резерв", "words", words)));
                    assertTrue(await(() -> String.valueOf(orchestrator.status().get("mixed_transcript")).contains("резерв"), 3000));
                }
                analytics.awaitConnection(0, 3000).sendText(Json.write(Json.map("type", "decision", "answerId", 1,
                        "sessionId", "s", "sequence", seq.getAndIncrement(), "attemptId", 1, "audioSample", 16000,
                        "recognized", "live")));
                assertTrue(await(() -> orchestrator.status().get("decision") != null, 3000));
                // До завершения интервью итоговый распознаватель не должен получать аудио.
                verify(whisper, never()).post(anyString(), any());
                if (captureLost) orchestrator.audioCaptureLost("Канал потерян");
                var spans = twoChannels ? List.<java.util.Map<String,Object>>of(java.util.Map.of(
                        "start", 0.0, "end", 1.0, "label", "Кандидат", "source_speaker_id", "candidate")) : List.<java.util.Map<String,Object>>of();
                orchestrator.finish(1, spans);
                assertNull(orchestrator.status().get("bridge_error"));
                int finalIndex = -1, stopIndex = -1;
                for (int i = 0; i < seen.size(); i++) {
                    if ("final_answer".equals(seen.get(i).path("type").asText())) finalIndex = i;
                    if ("stop".equals(seen.get(i).path("type").asText())) stopIndex = i;
                }
                assertTrue(finalIndex >= 0 && finalIndex < stopIndex);
                if (voskGap) {
                    assertTrue(seen.get(finalIndex).path("text").asText().contains("резерв"));
                    assertTrue(orchestrator.finalTranscriptWords().stream().anyMatch(w -> "vosk".equals(w.get("model")) && "резерв".equals(w.get("word"))));
                    if (overlap) assertFalse(seen.get(finalIndex).path("text").asText().contains("дубликат"));
                } else assertEquals((whisperFails && !twoChannels) || captureLost || whisperDisabled ? "live" : "финальный", seen.get(finalIndex).path("text").asText());
                assertEquals("Q1", seen.get(finalIndex).path("questionId").asText());
                assertEquals((whisperFails && !twoChannels) || captureLost || whisperDisabled ? "vosk" : whisperFails || voskGap ? "mixed" : "whisper", seen.get(finalIndex).path("source").asText());
                if ((whisperFails && !twoChannels) || captureLost || whisperDisabled) assertNotNull(orchestrator.transcriptError());
                if (captureLost || whisperDisabled) {
                    try (var files = java.nio.file.Files.list(dir.resolve("spool"))) {
                        var file = files.findFirst().orElseThrow();
                        assertEquals(32000, java.nio.file.Files.size(file));
                    }
                    verify(whisper, never()).post(anyString(), any());
                }
                if (twoChannels && !whisperFails) {
                    assertEquals(2, whisperCalls.get());
                    var words = orchestrator.finalTranscriptWords();
                    assertTrue(words.stream().anyMatch(w -> "интервьюер".equals(w.get("word")) && "microphone".equals(w.get("source"))));
                    assertFalse(seen.get(finalIndex).path("text").asText().contains("интервьюер"));
                }
                if (twoChannels && whisperFails) {
                    assertTrue(orchestrator.transcriptComplete());
                    assertTrue(orchestrator.finalTranscriptWords().stream().anyMatch(w -> "unavailable".equals(w.get("model"))));
                }
            }
        }
    }
}
