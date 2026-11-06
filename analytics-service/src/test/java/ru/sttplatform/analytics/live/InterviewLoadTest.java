package ru.sttplatform.analytics.live;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.sttplatform.analytics.engine.Level;
import ru.sttplatform.analytics.engine.TestKb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static ru.sttplatform.analytics.live.LiveInterviewSlaTest.*;

/**
 * Нагрузка на ядро аналитики: много одновременных интервью в одном процессе, у каждого модель с «рваной» задержкой.
 * Запуск: {@code mvn -pl analytics-service test -Dgroups=load} (в обычный прогон не входит).
 * Параметры: {@code -Dload.sessions=200 -Dload.questions=5}.
 */
@Tag("load")
class InterviewLoadTest {
    private static final int SESSIONS = Integer.getInteger("load.sessions", 100);
    private static final int QUESTIONS = Integer.getInteger("load.questions", 4);

    @Test
    void many_parallel_interviews_keep_every_decision_inside_the_budget() throws Exception {
        int threads = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Long> decisionMicros = Collections.synchronizedList(new ArrayList<>());
        List<Long> decisionSilence = Collections.synchronizedList(new ArrayList<>());
        AtomicLong frames = new AtomicLong();
        long started = System.nanoTime();
        List<Future<?>> futures = new ArrayList<>();
        for (int s = 0; s < SESSIONS; s++) {
            final int seed = s;
            futures.add(pool.submit(() -> {
                long latency = (seed % 5) * 300L;                    // 0…1200 мс виртуального времени
                var judge = new SttSim.VirtualJudge(latency, MODEL, seed % 7 == 0);
                SttSim sim = SttSim.create(TestKb.get(), List.of(Level.INTERN, Level.JUNIOR), null, judge, 1200);
                sim.live.start();
                for (int q = 0; q < QUESTIONS; q++) {
                    sim.ready();
                    sim.silence(200);
                    sim.speak((seed + q) % 3 == 0 ? PARTIAL : STRONG, 40);
                    assertTrue(sim.silenceUntil("decision", 6000) > 0);
                    var d = sim.last("decision");
                    decisionSilence.add(SttSim.silenceMsOf(d));
                    decisionMicros.add(((Number) ((java.util.Map<?, ?>) d.get("timing")).get("decisionMicros")).longValue());
                    sim.silence(GRACE + 50);
                }
                sim.live.onStop();
                frames.addAndGet(sim.frames.get());
                assertEquals(1, sim.count("report"));
            }));
        }
        for (Future<?> f : futures) f.get(120, TimeUnit.SECONDS);
        pool.shutdown();
        double seconds = (System.nanoTime() - started) / 1e9;

        long maxSilence = decisionSilence.stream().mapToLong(Long::longValue).max().orElse(0);
        long p99micros = percentile(decisionMicros, 0.99);
        System.out.printf("load: %d сессий × %d вопросов, %d кадров за %.2f с (%.0f кадр/с), p99 расчёта решения %d мкс, макс. тишина до решения %d мс%n",
                SESSIONS, QUESTIONS, frames.get(), seconds, frames.get() / seconds, p99micros, maxSilence);
        assertEquals((long) SESSIONS * QUESTIONS, decisionSilence.size());
        assertTrue(maxSilence <= BUDGET_MS, "бюджет 3 с нарушен: " + maxSilence);
        assertTrue(p99micros < 50_000, "p99 расчёта решения " + p99micros + " мкс");
        // каждый кадр 50 мс аудио: ядро должно обрабатывать поток в тысячи раз быстрее реального времени
        assertTrue(frames.get() / seconds > 20 * SESSIONS, "слишком медленно: " + frames.get() / seconds + " кадр/с");
    }

    private static long percentile(List<Long> v, double p) {
        List<Long> s = new ArrayList<>(v);
        Collections.sort(s);
        return s.get((int) Math.min(s.size() - 1, Math.floor(p * s.size())));
    }

    private static final ru.sttplatform.analytics.engine.Verdict MODEL = new ru.sttplatform.analytics.engine.Verdict(
            List.of(new ru.sttplatform.analytics.engine.Verdict.Fact("факт", 0.6)), null, null,
            new int[]{0}, new double[]{1}, null, null, null, "frida", 20, false);
}
