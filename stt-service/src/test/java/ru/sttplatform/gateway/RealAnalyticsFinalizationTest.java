package ru.sttplatform.gateway;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import ru.sttplatform.analytics.engine.DecisionPolicy;
import ru.sttplatform.analytics.judge.FridaJudge;
import ru.sttplatform.analytics.judge.JudgeProperties;
import ru.sttplatform.analytics.web.ExcelReportService;
import ru.sttplatform.analytics.web.InterviewConfig;
import ru.sttplatform.analytics.web.InterviewProperties;
import ru.sttplatform.analytics.web.InterviewWebSocketHandler;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.gateway.session.Orchestrator;
import ru.sttplatform.stt.integration.IntegrationProperties;
import ru.sttplatform.stt.whisper.WhisperClient;
import ru.sttplatform.testsupport.MiniWsServer;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static ru.sttplatform.testsupport.MiniWsServer.await;

/** Проверяет связь настоящих оркестратора и обработчика аналитики с подставными моделями. */
class RealAnalyticsFinalizationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void answer_reaches_real_analytics_after_whisper(boolean gap, @TempDir Path directory) throws Exception {
        var props = new InterviewProperties(null, null, null, null, null, null, null, null, null,
                directory.resolve("reports").toString(), null, null, null, null, null);
        var judgeProps = new JudgeProperties(false, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        var handler = new InterviewWebSocketHandler(new InterviewConfig().interviewKnowledge(props),
                new DecisionPolicy(props.maxFollowUps(), props.weakCoverage()), props,
                new ExcelReportService(props), Json.MAPPER, new FridaJudge(judgeProps), judgeProps);
        var sent = new CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        try (var stt = new MiniWsServer(); var analytics = new MiniWsServer()) {
            stt.onText = (conn, text) -> { if ("eof".equals(text)) conn.close(); };
            WebSocketSession ws = mock(WebSocketSession.class);
            when(ws.getId()).thenReturn("real-analytics");
            when(ws.isOpen()).thenReturn(true);
            var connection = new java.util.concurrent.atomic.AtomicReference<MiniWsServer.Conn>();
            doAnswer(call -> { connection.get().sendText(((TextMessage) call.getArgument(0)).getPayload()); return null; })
                    .when(ws).sendMessage(any());
            analytics.onText = (conn, text) -> {
                try {
                    connection.set(conn);
                    var node = Json.tree(text);
                    sent.add(node);
                    handler.handleMessage(ws, new TextMessage(text));
                } catch (Exception error) { throw new IllegalStateException(error); }
            };
            WhisperClient whisper = mock(WhisperClient.class);
            when(whisper.post(anyString(), any())).thenReturn(new WhisperClient.Reply(200,
                    "{\"words\":[{\"word\":\"финальный\",\"start\":0.2,\"end\":0.4}]}"));
            try (var orchestrator = new Orchestrator(directory.resolve("out"), stt.httpUrl(), analytics.httpUrl(),
                    "s", true, new IntegrationProperties(null, null), whisper, true, "", false,
                    directory.resolve("spool"), true)) {
                assertTrue(await(() -> orchestrator.status().get("question") != null, 5000));
                orchestrator.questionAsked();
                assertTrue(await(() -> sent.stream().anyMatch(e -> "ready".equals(e.path("type").asText())), 5000));
                byte[] voiced = new byte[1600];
                for (int i = 0; i < 800; i++) {
                    short sample = (short) (6000 * Math.sin(i / 3.0));
                    voiced[2 * i] = (byte) sample;
                    voiced[2 * i + 1] = (byte) (sample >> 8);
                }
                long position = 0;
                for (int i = 0; i < 100; i++) {
                    byte[] pcm = i < 35 ? voiced : new byte[1600];
                    orchestrator.submit(position, pcm);
                    position += 800;
                }
                if (gap) {
                    stt.awaitConnection(0, 3000).sendText(Json.write(Json.map("type", "final", "text", "резерв",
                            "words", List.of(Json.map("word", "резерв", "start", 1.2, "end", 1.4)))));
                    assertTrue(await(() -> String.valueOf(orchestrator.status().get("mixed_transcript")).contains("резерв"), 3000));
                }
                assertTrue(await(() -> orchestrator.status().get("decision") != null, 10000));
                orchestrator.finish(5, List.of());
                assertNull(orchestrator.status().get("bridge_error"));
                assertTrue(sent.stream().anyMatch(e -> "final_answer".equals(e.path("type").asText())
                        && e.path("text").asText().contains("финальный")
                        && (!gap || e.path("text").asText().contains("резерв"))));
                if (gap) assertEquals("mixed", orchestrator.status().get("report_transcript_source"));
                assertTrue(sent.stream().anyMatch(e -> "stop".equals(e.path("type").asText())));
                Path report;
                try (var files = java.nio.file.Files.list(directory.resolve("reports"))) {
                    report = files.filter(p -> p.toString().endsWith(".xlsx")).findFirst().orElseThrow();
                }
                try (var zip = new java.util.zip.ZipFile(report.toFile())) {
                    var strings = zip.getEntry("xl/sharedStrings.xml");
                    assertNotNull(strings);
                    try (var input = zip.getInputStream(strings)) {
                        String text = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        assertTrue(text.contains("финальный"), "В XLSX должен попасть текст итогового Whisper");
                        if (gap) assertTrue(text.contains("резерв"), "В XLSX должен попасть резервный текст");
                    }
                }
            }
        }
    }
}
