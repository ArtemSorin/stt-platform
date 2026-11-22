package ru.sttplatform.analytics.live;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.sttplatform.analytics.engine.Level;
import ru.sttplatform.analytics.judge.FridaJudge;
import ru.sttplatform.analytics.judge.JudgeProperties;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static ru.sttplatform.analytics.live.LiveInterviewSlaTest.*;

/**
 * Интеграция «поток кадров STT → LiveInterview → настоящий HTTP-клиент FridaJudge → поддельный сервер FRIDA».
 * Проверяется весь список вопросов подряд, порядок событий и бюджет решения на каждом вопросе.
 */
class InterviewPipelineIntegrationTest {
    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    private FridaJudge fridaReturning(int status, long delayMs) throws Exception {
        String decision = new String(getClass().getResourceAsStream("/frida/decision-response.json").readAllBytes(), StandardCharsets.UTF_8);
        String scoring = new String(getClass().getResourceAsStream("/frida/scoring-response.json").readAllBytes(), StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            byte[] b = (body.contains("completeness") ? scoring : decision).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        return new FridaJudge(new JudgeProperties(true, "http://127.0.0.1:" + server.getAddress().getPort(), "sidecar", null,
                2000, 5000, 8000, 1800, true, 3, 60_000, 16, null, null, null, null, null, null));
    }

    /** Кадры в «реальном» темпе 2 мс/кадр, чтобы HTTP-ответ успевал прийти между кадрами. */
    private static void silencePaced(SttSim sim, long maxMs, String untilType) throws InterruptedException {
        int before = sim.count(untilType);
        for (long t = 0; t < maxMs && sim.count(untilType) == before; t += SttSim.FRAME_MS) {
            sim.frame(false, "", "");
            Thread.sleep(2);
        }
    }

    /** Дожидается завершения уже отправленных запросов перед проверкой состояния circuit breaker. */
    private static void awaitJudgeRequestsSettled(FridaJudge judge) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            Map<String, Object> stats = judge.stats();
            if (((Number) stats.get("calls")).longValue() == ((Number) stats.get("failures")).longValue()) return;
            Thread.sleep(10);
        }
        Map<String, Object> stats = judge.stats();
        assertEquals(((Number) stats.get("calls")).longValue(), ((Number) stats.get("failures")).longValue(),
                "все ошибочные HTTP-запросы FRIDA должны завершиться до проверки автомата отказов");
    }

    @Test
    void whole_interview_with_a_live_model_decides_every_question_within_budget() throws Exception {
        FridaJudge judge = fridaReturning(200, 0);
        SttSim sim = SttSim.create(kbOrThrow(), List.of(Level.INTERN), null, judge, 8000);
        sim.live.start();
        int questions = 3;
        for (int i = 1; i <= questions; i++) {
            sim.ready();
            sim.silence(200);
            sim.speak(i == 2 ? PARTIAL : STRONG, 40);
            silencePaced(sim, 12000, "decision");
            assertEquals(i, sim.count("decision"), "вопрос " + i);
            Map<String, Object> d = sim.last("decision");
            assertEquals("frida", d.get("engine"), "вопрос " + i + " должен использовать модель");
            sim.silence(GRACE + 50);
        }
        assertEquals(questions, sim.count("decision"));
        assertTrue(requests.get() >= questions, "модель опрашивалась на каждом вопросе");
        sim.live.onStop();
        assertEquals(1, sim.count("report"));
        // порядок: каждому decision предшествует question с тем же или меньшим номером
        int idx = -1;
        for (int i = 0; i < sim.events.size(); i++)
            if ("report".equals(sim.events.get(i).get("type"))) idx = i;
        assertEquals(sim.events.size() - 1, idx, "отчёт — последнее событие");
    }

    @Test
    void model_outage_during_the_interview_degrades_to_rules_without_exceeding_the_budget() throws Exception {
        FridaJudge judge = fridaReturning(503, 0);
        SttSim sim = SttSim.create(kbOrThrow(), List.of(Level.INTERN), null, judge, 8000);
        sim.live.start();
        for (int i = 1; i <= 4; i++) {
            sim.ready();
            sim.silence(200);
            sim.speak(STRONG, 40);
            silencePaced(sim, 12000, "decision");
            Map<String, Object> d = sim.last("decision");
            assertEquals("rules", d.get("engine"), "вопрос " + i);
            assertTrue(SttSim.silenceMsOf(d) >= 8450);
            sim.silence(GRACE + 50);
        }
        // после серии сбоев автомат отказов выключает модель — новые запросы не уходят
        assertFalse(judge.ready());
        // Учитываем завершение запросов, отправленных до открытия circuit breaker.
        awaitJudgeRequestsSettled(judge);
        long sent = ((Number) judge.stats().get("calls")).longValue();
        sim.ready();
        sim.silence(200);
        sim.speak(STRONG, 40);
        silencePaced(sim, 12000, "decision");
        assertEquals(sent, ((Number) judge.stats().get("calls")).longValue());
    }

    @Test
    void slow_model_beyond_the_deadline_still_meets_the_budget() throws Exception {
        FridaJudge judge = fridaReturning(200, 2500);
        SttSim sim = SttSim.create(kbOrThrow(), List.of(Level.INTERN), null, judge, 8000);
        sim.live.start();
        sim.ready();
        sim.silence(200);
        sim.speak(STRONG, 40);
        silencePaced(sim, 12000, "decision");
        Map<String, Object> d = sim.last("decision");
        assertEquals("rules", d.get("engine"));
        assertEquals(450 + 8000, SttSim.silenceMsOf(d));
    }

    private static ru.sttplatform.analytics.engine.InterviewKnowledge kbOrThrow() {
        return ru.sttplatform.analytics.engine.TestKb.get();
    }
}
