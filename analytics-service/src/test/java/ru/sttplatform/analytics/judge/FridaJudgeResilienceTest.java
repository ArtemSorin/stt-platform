package ru.sttplatform.analytics.judge;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Сбои сервера модели, которые не должны ломать интервью: битый JSON, статус сервера, недоступность. */
class FridaJudgeResilienceTest {
    private HttpServer server;

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    private String serve(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static JudgeProperties props(String url, String protocol) {
        return new JudgeProperties(true, url, protocol, null, 2000, 5000, 1200, 1800, true, 3, 400, 16,
                null, null, null, null, null, null);
    }

    @Test
    void malformed_json_from_the_model_fails_the_future_with_a_clear_message() throws Exception {
        FridaJudge judge = new FridaJudge(props(serve(200, "это не JSON {"), "sidecar"));
        var e = assertThrows(ExecutionException.class,
                () -> judge.decide(FridaPromptBuilderTest.inputOfFirstQuestion()).get(5, TimeUnit.SECONDS));
        assertTrue(e.getCause().getMessage().contains("Некорректный JSON"));
        assertEquals(1L, judge.stats().get("failures"));
    }

    @Test
    void long_error_body_is_shortened_in_the_message() throws Exception {
        FridaJudge judge = new FridaJudge(props(serve(503, "x".repeat(500)), "sidecar"));
        var e = assertThrows(ExecutionException.class,
                () -> judge.decide(FridaPromptBuilderTest.inputOfFirstQuestion()).get(5, TimeUnit.SECONDS));
        String msg = e.getCause().getMessage();
        assertTrue(msg.contains("HTTP 503"));
        assertTrue(msg.length() < 300, "тело ответа обрезается: " + msg.length());
    }

    @Test
    void server_status_reports_sidecar_state_http_errors_and_unreachable_server() throws Exception {
        FridaJudge ok = new FridaJudge(props(serve(200, "{\"queue\":2}"), "sidecar"));
        assertEquals(2, ((Number) ok.serverStatus().get("queue")).intValue());
        server.stop(0);

        FridaJudge bad = new FridaJudge(props(serve(500, "ошибка"), "sidecar"));
        assertEquals("HTTP 500", bad.serverStatus().get("error"));
        server.stop(0);

        Map<String, Object> down = new FridaJudge(props("http://127.0.0.1:1/", "sidecar")).serverStatus();
        assertNotNull(down.get("error"));

        Map<String, Object> vllm = new FridaJudge(props("http://127.0.0.1:1", "vllm")).serverStatus();
        assertTrue(String.valueOf(vllm.get("note")).contains("vLLM"));
    }

    @Test
    void disabled_model_is_never_ready_and_fails_fast() {
        FridaJudge off = new FridaJudge(new JudgeProperties(false, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null));
        assertFalse(off.ready());
        assertNull(off.preview(FridaPromptBuilderTest.inputOfFirstQuestion()));
        assertTrue(off.score(FridaPromptBuilderTest.inputOfFirstQuestion()).isCompletedExceptionally());
        assertNotNull(off.prompts());
    }
}
