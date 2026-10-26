package ru.sttplatform.analytics.judge;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.sttplatform.analytics.engine.AnswerJudge;
import ru.sttplatform.analytics.engine.JudgeInput;
import ru.sttplatform.analytics.engine.Verdict;
import ru.sttplatform.common.json.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Клиент модели FRIDA-Decisions по HTTP. Умеет два протокола: собственный сервер {@code judge/frida_server.py}
 * ({@code POST /judge}) и сервер vLLM ({@code POST /pooling}).
 * <ul>
 *   <li><b>Кэш и общий полёт.</b> Одинаковый запрос не отправляется дважды: подсказка на паузе и решение в конце
 *       ответа чаще всего про один и тот же текст.</li>
 *   <li><b>Автомат отказов.</b> После серии сбоев модель на время «выбита», и вызывающий код сразу работает по
 *       правилам, а не ждёт таймаута на каждом ответе.</li>
 *   <li><b>Приоритеты.</b> Решение идёт с высоким приоритетом, фоновая оценка — с низким, подсказка пропускается,
 *       если модель занята.</li>
 * </ul>
 *
 * @version 1.0.0
 */
public final class FridaJudge implements AnswerJudge {
    private static final Logger log = LoggerFactory.getLogger(FridaJudge.class);

    private final JudgeProperties props;
    private final FridaPromptBuilder prompts;
    private final HttpClient http;
    private final URI endpoint;
    private final Map<String, CompletableFuture<Verdict>> cache = new ConcurrentHashMap<>();
    private final List<String> order = new ArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong brokenUntilMs = new AtomicLong();
    private final AtomicLong calls = new AtomicLong(), failures = new AtomicLong(), truncated = new AtomicLong(),
            totalMs = new AtomicLong(), cacheHits = new AtomicLong(), skippedPreviews = new AtomicLong();
    private final java.util.concurrent.ConcurrentLinkedQueue<Double> recent = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * Создаёт клиент.
     *
     * @param props настройки модели
     */
    public FridaJudge(JudgeProperties props) {
        this.props = props;
        this.prompts = new FridaPromptBuilder(props.maxStateChars());
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        String base = props.url().replaceAll("/+$", "");
        this.endpoint = URI.create(base + ("vllm".equals(props.protocol()) ? "/pooling" : "/judge"));
    }

    @Override
    public boolean ready() {
        return props.enabled() && System.currentTimeMillis() >= brokenUntilMs.get();
    }

    @Override
    public CompletableFuture<Verdict> decide(JudgeInput input) {
        return run("d", input, prompts.decisionRequest(input), "high", props.requestTimeoutMs());
    }

    @Override
    public CompletableFuture<Verdict> preview(JudgeInput input) {
        if (!props.previewEnabled() || !ready() || inFlight.get() > 0) {
            skippedPreviews.incrementAndGet();
            return null;
        }
        return run("d", input, prompts.decisionRequest(input), "high", props.requestTimeoutMs());
    }

    @Override
    public CompletableFuture<Verdict> score(JudgeInput input) {
        return run("s", input, prompts.scoringRequest(input), "low", props.scoreTimeoutMs());
    }

    private CompletableFuture<Verdict> run(String kind, JudgeInput input, Map<String, Object> request,
                                           String priority, int timeoutMs) {
        if (!ready()) return CompletableFuture.failedFuture(new IllegalStateException("Модель оценки сейчас недоступна"));
        String key = kind + Json.write(request);
        CompletableFuture<Verdict> hit = cache.get(key);
        if (hit != null && !(hit.isDone() && hit.isCompletedExceptionally())) {
            cacheHits.incrementAndGet();
            return hit;
        }
        CompletableFuture<Verdict> future = send(request, input, priority, timeoutMs);
        remember(key, future);
        return future;
    }

    private synchronized void remember(String key, CompletableFuture<Verdict> future) {
        cache.put(key, future);
        order.add(key);
        while (order.size() > props.cacheSize()) cache.remove(order.remove(0));
        future.whenComplete((v, e) -> {
            if (e != null) cache.remove(key, future);   // сбои не кэшируем
        });
    }

    private CompletableFuture<Verdict> send(Map<String, Object> request, JudgeInput input, String priority, int timeoutMs) {
        Object body = "vllm".equals(props.protocol())
                ? Json.map("model", props.model(), "data", request) : request;
        HttpRequest http = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json; charset=utf-8").header("X-Priority", priority)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8)).build();
        long started = System.nanoTime();
        inFlight.incrementAndGet();
        calls.incrementAndGet();
        return this.http.sendAsync(http, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() != 200)
                        throw new IllegalStateException("Модель вернула HTTP " + response.statusCode() + ": " + shorten(response.body()));
                    try {
                        JsonNode root = Json.tree(response.body());
                        JsonNode payload = "vllm".equals(props.protocol()) ? root.path("data") : root;
                        Verdict verdict = FridaVerdictParser.parse(payload, input);
                        long ms = Math.round((System.nanoTime() - started) / 1e6);
                        // latencyMs в Verdict — время самой модели; для статистики берём полное время запроса
                        record(ms, verdict);
                        return verdict;
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException("Некорректный JSON от модели", e);
                    }
                })
                .whenComplete((v, e) -> {
                    inFlight.decrementAndGet();
                    if (e != null) onFailure(e);
                    else consecutiveFailures.set(0);
                });
    }

    private void record(long ms, Verdict verdict) {
        totalMs.addAndGet(ms);
        if (verdict.stateTruncated()) truncated.incrementAndGet();
        recent.add((double) ms);
        while (recent.size() > 200) recent.poll();
    }

    private void onFailure(Throwable error) {
        failures.incrementAndGet();
        int n = consecutiveFailures.incrementAndGet();
        log.warn("Оценка моделью не удалась ({} подряд): {}", n, String.valueOf(error.getCause() == null ? error : error.getCause()));
        if (n >= props.breakerFailures()) {
            brokenUntilMs.set(System.currentTimeMillis() + props.breakerPauseMs());
            consecutiveFailures.set(0);
            log.warn("Модель оценки выключена на {} с: интервью продолжается по правилам.", props.breakerPauseMs() / 1000);
        }
    }

    private static String shorten(String s) {
        return s == null ? "" : s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /**
     * Статистика вызовов для проверки «укладывается ли модель в бюджет времени».
     *
     * @return счётчики и задержки (полное время HTTP-запроса, мс)
     */
    public Map<String, Object> stats() {
        List<Double> sample = new ArrayList<>(recent);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.enabled());
        m.put("ready", ready());
        m.put("url", endpoint.toString());
        m.put("protocol", props.protocol());
        m.put("calls", calls.get());
        m.put("failures", failures.get());
        m.put("cacheHits", cacheHits.get());
        m.put("skippedPreviews", skippedPreviews.get());
        m.put("stateTruncated", truncated.get());
        m.put("latencyMs", Json.distribution(sample));
        m.put("decisionDeadlineMs", props.decisionDeadlineMs());
        return m;
    }

    /**
     * Запрос состояния сервера модели ({@code GET /status}); для vLLM не поддерживается.
     *
     * @return ответ сервера или карта с ошибкой
     */
    public Map<String, Object> serverStatus() {
        if ("vllm".equals(props.protocol())) return Json.map("note", "для vLLM состояние сервера не запрашивается");
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(props.url().replaceAll("/+$", "") + "/status"))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return r.statusCode() == 200 ? Json.toMap(Json.tree(r.body())) : Json.map("error", "HTTP " + r.statusCode());
        } catch (Exception e) {
            return Json.map("error", String.valueOf(e.getMessage()));
        }
    }

    /**
     * Построитель запросов (нужен проверке и тестам).
     *
     * @return построитель
     */
    public FridaPromptBuilder prompts() {
        return prompts;
    }
}
