package ru.sttplatform.analytics.live;

import org.junit.jupiter.api.Test;
import ru.sttplatform.analytics.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Конвейер ответа с моделью оценки: ожидание заключения, откат на правила, фоновая оценка. */
class LiveInterviewJudgeTest {

    /** Управляемая модель: заключение выдаётся тестом, когда нужно. */
    static final class FakeJudge implements AnswerJudge {
        final List<CompletableFuture<Verdict>> decisions = new ArrayList<>();
        final List<CompletableFuture<Verdict>> scorings = new ArrayList<>();
        final List<JudgeInput> decisionInputs = new ArrayList<>();
        final AtomicInteger previews = new AtomicInteger();
        boolean ready = true;
        Verdict immediate;

        @Override public CompletableFuture<Verdict> decide(JudgeInput input) {
            decisionInputs.add(input);
            CompletableFuture<Verdict> f = immediate != null ? CompletableFuture.completedFuture(immediate) : new CompletableFuture<>();
            decisions.add(f);
            return f;
        }

        @Override public CompletableFuture<Verdict> preview(JudgeInput input) {
            previews.incrementAndGet();
            return null;
        }

        @Override public CompletableFuture<Verdict> score(JudgeInput input) {
            CompletableFuture<Verdict> f = new CompletableFuture<>();
            scorings.add(f);
            return f;
        }

        @Override public boolean ready() { return ready; }
    }

    private static Verdict verdict(double p) {
        List<Verdict.Fact> facts = List.of(new Verdict.Fact("а", p), new Verdict.Fact("б", p), new Verdict.Fact("в", p));
        return new Verdict(facts, 0.02, 0.02, new int[]{0, 1, 2, 3, 4}, new double[]{.1, .5, .2, .1, .1},
                null, null, null, "frida", 35, false);
    }

    private static final class Run {
        final List<Map<String, Object>> events = new ArrayList<>();
        final LiveInterview live;
        final FakeJudge judge = new FakeJudge();

        Run(String answer, int voiced, long deadlineMs) {
            InterviewSession session = new InterviewSession(TestKb.get(), List.of(Level.INTERN), new DecisionPolicy(2, 0.08));
            live = new LiveInterview(new LiveInterviewTest.ScriptedEngine(answer, voiced), session,
                    new EndpointDetector(new EndpointDetector.Config()), events::add);
            live.setJudge(judge, deadlineMs);
        }

        Map<String, Object> last(String type) {
            for (int i = events.size() - 1; i >= 0; i--) if (type.equals(events.get(i).get("type"))) return events.get(i);
            return null;
        }
        int count(String type) {
            int n = 0;
            for (var e : events) if (type.equals(e.get("type"))) n++;
            return n;
        }
        void frames(boolean voiced, int n) {
            for (int i = 0; i < n; i++) live.onAudio(Pcm.frame(voiced), Pcm.FRAME_SAMPLES * 2);
        }
        /** Тишина, пока не появится решение; возвращает число кадров или -1. */
        int silenceUntilDecision(int max) {
            for (int i = 1; i <= max; i++) {
                frames(false, 1);
                if (count("decision") >= 1) return i;
            }
            return -1;
        }
        void speak(int speechFrames) {
            live.onReady();
            frames(false, 8);
            frames(true, speechFrames);
        }
    }

    private static final String ANSWER = "джава это виртуальная машина она запускает программы а джи ди кей это для разработки там компилятор";

    @Test
    void decision_waits_for_the_model_and_uses_its_verdict() {
        Run r = new Run(ANSWER, 40, 5000);
        r.live.start();
        r.speak(40);
        r.frames(false, 30);                                           // 1,5 с тишины, а модель ещё не ответила
        assertEquals(0, r.count("decision"), "решение не должно приниматься до заключения модели");
        assertEquals(1, r.judge.decisions.size());
        assertTrue(r.judge.decisionInputs.get(0).turns().get(0).answer().length() > 10);

        r.judge.decisions.get(0).complete(verdict(0.95));
        assertTrue(r.silenceUntilDecision(5) > 0);
        Map<String, Object> d = r.last("decision");
        assertEquals("NEXT_QUESTION", d.get("action"));
        assertEquals("sufficient", d.get("reason"));
        assertEquals("frida", d.get("engine"));
        assertEquals(3, ((Map<?, ?>) d.get("judge")).get("factsTotal"));
    }

    @Test
    void weak_answer_by_the_models_verdict_gets_the_follow_up_the_model_chose() {
        Run r = new Run(ANSWER, 40, 5000);
        r.live.start();
        r.speak(40);
        r.judge.decisions.isEmpty();
        r.frames(false, 12);
        r.judge.decisions.get(0).complete(verdict(0.15));
        assertTrue(r.silenceUntilDecision(60) > 0);
        assertEquals("ASK_FOLLOW_UP", r.last("decision").get("action"));
        assertEquals(TestKb.get().questions()[0].followUps()[1].text(), r.last("question").get("text"));
    }

    @Test
    void fast_model_does_not_add_delay_beyond_the_usual_pause() {
        Run r = new Run(ANSWER, 40, 5000);
        r.judge.immediate = verdict(0.95);
        r.live.start();
        r.speak(40);
        int frames = r.silenceUntilDecision(100);
        long silenceMs = (Long) ((Map<?, ?>) r.last("decision").get("timing")).get("silenceMs");
        assertTrue(frames > 0 && silenceMs >= 450 && silenceMs <= 700, "тишина до решения: " + silenceMs + " мс");
    }

    @Test
    void model_too_slow_falls_back_to_keyword_rules() {
        Run r = new Run(ANSWER, 40, 400);
        r.live.start();
        r.speak(40);
        assertTrue(r.silenceUntilDecision(100) > 0);                  // заключение так и не пришло
        assertEquals("rules", r.last("decision").get("engine"));
        assertNull(r.last("decision").get("judge"));
    }

    @Test
    void failed_model_call_falls_back_to_rules_without_waiting_for_the_deadline() {
        Run r = new Run(ANSWER, 40, 60_000);
        r.live.start();
        r.speak(40);
        r.frames(false, 12);
        r.judge.decisions.get(0).completeExceptionally(new IllegalStateException("сервер упал"));
        assertTrue(r.silenceUntilDecision(60) > 0);
        assertEquals("rules", r.last("decision").get("engine"));
    }

    @Test
    void model_that_is_not_ready_is_not_even_asked() {
        Run r = new Run(ANSWER, 40, 5000);
        r.judge.ready = false;
        r.live.start();
        r.speak(40);
        assertTrue(r.silenceUntilDecision(100) > 0);
        assertTrue(r.judge.decisions.isEmpty());
        assertEquals("rules", r.last("decision").get("engine"));
    }

    @Test
    void person_who_continues_speaking_cancels_the_pending_decision() {
        Run r = new Run(ANSWER, 80, 5000);
        r.live.start();
        r.speak(30);
        r.frames(false, 12);                                           // мягкий конец, запрос к модели ушёл
        assertEquals(1, r.judge.decisions.size());
        r.frames(true, 20);                                            // человек продолжил
        r.judge.decisions.get(0).complete(verdict(0.95));              // запоздалое заключение по старому тексту
        r.frames(true, 5);
        assertEquals(0, r.count("decision"));
    }

    @Test
    void scoring_runs_in_background_and_report_waits_for_it() {
        Run r = new Run(ANSWER, 40, 5000);
        r.judge.immediate = verdict(0.95);
        r.live.start();
        r.speak(40);
        assertTrue(r.silenceUntilDecision(100) > 0);
        assertEquals(1, r.judge.scorings.size(), "оценка по критериям запускается после решения");

        r.judge.scorings.get(0).complete(new Verdict(List.of(new Verdict.Fact("а", .9), new Verdict.Fact("б", .9), new Verdict.Fact("в", .9)),
                null, null, null, null, 0.9, 0.9, 0.9, "frida", 80, false));
        r.live.onStop();
        InterviewReport report = (InterviewReport) r.last("report").get("report");
        assertTrue(report.technical() > 7, "точность должна опираться на оценку модели: " + report.technical());
    }
}
