package ru.sttplatform.analytics.judge;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.sttplatform.analytics.engine.JudgeInput;
import ru.sttplatform.analytics.engine.Verdict;
import ru.sttplatform.common.json.Json;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Клиент модели против поддельного сервера: протоколы, кэш, приоритеты, сбои и автомат отказов. */
class FridaJudgeTest {
    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> priorities = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile long delayMs = 0;
    private volatile boolean wrapData = false;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String serve() throws IOException {
        String decision = new String(getClass().getResourceAsStream("/frida/decision-response.json").readAllBytes(), StandardCharsets.UTF_8);
        String scoring = new String(getClass().getResourceAsStream("/frida/scoring-response.json").readAllBytes(), StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            paths.add(exchange.getRequestURI().getPath());
            priorities.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Priority")));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            String answer = body.contains("completeness") ? scoring : decision;
            if (wrapData) answer = "{\"data\":" + answer + "}";
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static JudgeProperties props(String url, String protocol, int timeoutMs, int failures) {
        return new JudgeProperties(true, url, protocol, null, timeoutMs, 5000, 1200, 1800, true, failures, 400, 16,
                null, null, null, null, null, null);
    }

    private static JudgeInput input() {
        return FridaPromptBuilderTest.inputOfFirstQuestion();
    }

    private static Verdict get(CompletableFuture<Verdict> f) throws Exception {
        return f.get(5, TimeUnit.SECONDS);
    }

    @Test
    void decision_goes_to_judge_endpoint_with_high_priority_and_returns_a_verdict() throws Exception {
        FridaJudge judge = new FridaJudge(props(serve(), "sidecar", 2000, 3));
        Verdict v = get(judge.decide(input()));
        assertEquals(3, v.facts().size());
        assertEquals(List.of("/judge"), paths);
        assertEquals(List.of("high"), priorities);
    }

    @Test
    void scoring_runs_with_low_priority() throws Exception {
        FridaJudge judge = new FridaJudge(props(serve(), "sidecar", 2000, 3));
        Verdict v = get(judge.score(input()));
        assertNotNull(v.completeness());
        assertEquals(List.of("low"), priorities);
    }

    @Test
    void identical_requests_are_answered_from_cache_and_share_the_flight() throws Exception {
        delayMs = 150;
        FridaJudge judge = new FridaJudge(props(serve(), "sidecar", 2000, 3));
        CompletableFuture<Verdict> a = judge.decide(input()), b = judge.decide(input());
        get(a);
        get(b);
        get(judge.decide(input()));
        assertEquals(1, hits.get());
        assertEquals(2L, judge.stats().get("cacheHits"));
    }

    @Test
    void vllm_protocol_wraps_the_request_and_unwraps_the_response() throws Exception {
        wrapData = true;
        FridaJudge judge = new FridaJudge(props(serve(), "vllm", 2000, 3));
        Verdict v = get(judge.decide(input()));
        assertEquals(3, v.facts().size());
        assertEquals(List.of("/pooling"), paths);
        var sent = Json.tree(bodies.get(0));
        assertEquals("ai-forever/FRIDA-Decisions", sent.path("model").asText());
        assertTrue(sent.path("data").path("questions").has("f0"));
    }

    @Test
    void server_error_fails_the_future_and_is_not_cached() throws Exception {
        status = 500;
        FridaJudge judge = new FridaJudge(props(serve(), "sidecar", 2000, 10));
        ExecutionException e = assertThrows(ExecutionException.class, () -> get(judge.decide(input())));
        assertTrue(e.getCause().getMessage().contains("500"), e.getCause().getMessage());
        status = 200;
        assertNotNull(get(judge.decide(input())), "после сбоя повторный запрос должен уйти на сервер");
        assertEquals(2, hits.get());
    }

    @Test
    void slow_server_times_out() throws Exception {
        delayMs = 600;
        FridaJudge judge = new FridaJudge(props(serve(), "sidecar", 150, 10));
        assertThrows(ExecutionException.class, () -> get(judge.decide(input())));
    }

    @Test
    void series_of_failures_switches_the_model_off_for_a_while_and_then_back_on() throws Exception {
        status = 500;
        FridaJudge judge = new FridaJudge(props(serve(), "sidecar", 2000, 2));
        for (int i = 0; i < 2; i++) {
            final int n = i;
            assertThrows(ExecutionException.class, () -> get(judge.decide(FridaPromptBuilderTest.inputOfFirstQuestion().turns().isEmpty() ? input() : variant(n))));
        }
        assertFalse(judge.ready());
        int before = hits.get();
        CompletableFuture<Verdict> fast = judge.decide(input());
        assertTrue(fast.isCompletedExceptionally(), "пока модель «выбита», вызов не должен ходить в сеть");
        assertEquals(before, hits.get());
        status = 200;
        Thread.sleep(500);
        assertTrue(judge.ready());
        assertNotNull(get(judge.decide(input())));
    }

    private static JudgeInput variant(int n) {
        JudgeInput in = input();
        return new JudgeInput(in.level(), in.topic(), in.question(), in.reference(), in.notes(), in.facts(),
                List.of(new JudgeInput.Turn(in.question(), "ответ номер " + n)), in.followUps());
    }

    @Test
    void preview_is_skipped_while_the_model_is_busy_and_when_disabled() throws Exception {
        delayMs = 300;
        FridaJudge judge = new FridaJudge(props(serve(), "sidecar", 3000, 3));
        CompletableFuture<Verdict> busy = judge.decide(input());
        assertNull(judge.preview(variant(1)), "модель занята — подсказку пропускаем");
        get(busy);
        assertNotNull(judge.preview(variant(2)));

        JudgeProperties off = new JudgeProperties(false, "http://127.0.0.1:1", null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        FridaJudge disabled = new FridaJudge(off);
        assertFalse(disabled.ready());
        assertTrue(disabled.decide(input()).isCompletedExceptionally());
    }

    @Test
    void invalid_protocol_is_rejected_at_startup() {
        assertThrows(IllegalArgumentException.class, () -> props("http://x", "grpc", 1000, 3));
    }
}
