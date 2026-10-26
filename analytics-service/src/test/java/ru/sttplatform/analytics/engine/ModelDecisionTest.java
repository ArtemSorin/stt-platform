package ru.sttplatform.analytics.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Решения и оценки по заключению модели: политика, сессия, откат, отчёт. Модель заменена готовыми заключениями. */
class ModelDecisionTest {
    private static final DecisionPolicy POLICY = new DecisionPolicy(2, 0.08);

    private static Verdict verdict(double[] factP, Double ignorance, Double offTopic, int[] followIdx, double[] share,
                                   Double completeness, Double correctness, Double terminology) {
        List<Verdict.Fact> facts = new java.util.ArrayList<>();
        for (int i = 0; i < factP.length; i++) facts.add(new Verdict.Fact("факт " + i, factP[i]));
        return new Verdict(facts, ignorance, offTopic, followIdx, share, completeness, correctness, terminology, "frida", 40, false);
    }

    private static Verdict covering(double p) {
        return verdict(new double[]{p, p, p}, 0.02, 0.02, new int[]{0, 1, 2, 3, 4}, new double[]{.1, .6, .1, .1, .1}, null, null, null);
    }

    private static InterviewSession session(Level... levels) {
        return new InterviewSession(TestKb.get(), List.of(levels), POLICY);
    }

    private static final AnswerTiming TIMING = new AnswerTiming(1200, 8000);

    @Test
    void well_covered_answer_is_sufficient() {
        InterviewSession s = session(Level.INTERN);
        s.start();
        var step = s.submit("ответ", "ответ про джаву и виртуальную машину", TIMING, covering(0.9));
        assertEquals(InterviewSession.Action.NEXT_QUESTION, step.action());
        assertEquals("sufficient", step.reason());
        assertEquals("frida", step.engine());
    }

    @Test
    void poorly_covered_answer_asks_the_follow_up_the_model_picked() {
        InterviewSession s = session(Level.INTERN);
        s.start();
        var step = s.submit("ответ", "ну это про джаву", TIMING, covering(0.2));
        assertEquals(InterviewSession.Action.ASK_FOLLOW_UP, step.action());
        assertEquals("needs_detail", step.reason());
        String expected = TestKb.get().questions()[0].followUps()[1].text();      // модель дала 0.6 уточнению с индексом 1
        assertEquals(expected, step.next().text());
    }

    @Test
    void a_very_short_but_on_topic_answer_still_gets_a_follow_up() {
        // старые правила в этом случае молча шли дальше («почти ничего не сказано»)
        InterviewSession s = session(Level.INTERN);
        s.start();
        var step = s.submit("виртуальная машина", "виртуальная машина", TIMING, covering(0.1));
        assertEquals("needs_detail", step.reason());
    }

    @Test
    void follow_ups_already_asked_are_not_repeated() {
        InterviewSession s = session(Level.INTERN);
        s.start();
        var first = s.submit("а", "а", TIMING, covering(0.2));
        var second = s.submit("б", "б", TIMING, verdict(new double[]{.3, .3, .3}, .02, .02, new int[]{0, 2, 3, 4},
                new double[]{.1, .2, .6, .1}, null, null, null));
        assertNotEquals(first.next().text(), second.next().text());
        assertEquals(TestKb.get().questions()[0].followUps()[3].text(), second.next().text());
    }

    @Test
    void refusal_is_not_pushed_with_follow_ups() {
        InterviewSession s = session(Level.INTERN);
        s.start();
        var step = s.submit("не знаю", "не знаю", TIMING,
                verdict(new double[]{0, 0, 0}, 0.95, 0.1, new int[]{0, 1}, new double[]{.5, .5}, null, null, null));
        assertEquals("ignorance", step.reason());
        assertEquals(InterviewSession.Action.NEXT_QUESTION, step.action());
    }

    @Test
    void off_topic_answer_moves_on() {
        InterviewSession s = session(Level.INTERN);
        s.start();
        var step = s.submit("вчера купил хлеб", "вчера купил хлеб", TIMING,
                verdict(new double[]{0, 0, 0}, 0.1, 0.9, new int[]{0, 1}, new double[]{.5, .5}, null, null, null));
        assertEquals("off_topic", step.reason());
    }

    @Test
    void refusal_with_some_substance_is_still_followed_up() {
        InterviewSession s = session(Level.INTERN);
        s.start();
        // «не знаю про JRE, но JVM — виртуальная машина»: покрытие выше порога «не знаю» — спрашиваем дальше
        var step = s.submit("а", "а", TIMING, verdict(new double[]{.6, .1, .1}, 0.8, 0.1, new int[]{0, 1},
                new double[]{.3, .7}, null, null, null));
        assertEquals("needs_detail", step.reason());
    }

    @Test
    void follow_up_budget_and_stagnation_stop_the_questioning() {
        InterviewSession s = session(Level.INTERN);
        s.start();
        s.submit("а", "а", TIMING, covering(0.2));
        var stagnant = s.submit("б", "б", TIMING, covering(0.21));
        assertEquals("stagnation", stagnant.reason());

        InterviewSession t = session(Level.INTERN);
        t.start();
        t.submit("а", "а", TIMING, covering(0.2));
        t.submit("б", "б", TIMING, covering(0.4));
        var third = t.submit("в", "в", TIMING, covering(0.6 - 0.25));   // бюджет в два уточнения исчерпан
        assertEquals("follow_up_budget", third.reason());
    }

    @Test
    void without_a_verdict_the_old_keyword_rules_decide() {
        InterviewSession s = session(Level.INTERN);
        s.start();
        var step = s.submit("", "", TIMING, null);
        assertEquals("rules", step.engine());
        assertEquals("no_answer", step.reason());
    }

    @Test
    void peek_with_verdict_does_not_change_the_interview() {
        InterviewSession s = session(Level.INTERN);
        s.start();
        var peek = s.peek("ответ", covering(0.2));
        assertTrue(peek.followUp());
        assertEquals("frida", peek.engine());
        assertEquals(1, s.judgeInput("x").turns().size());   // сессия по-прежнему на первом вопросе, без попыток
        assertEquals(TestKb.get().questions()[0].question().text(), s.judgeInput("x").question());
    }

    @Test
    void undo_restores_verdict_state() {
        InterviewSession s = session(Level.INTERN);
        s.start();
        s.submit("а", "а", TIMING, covering(0.9));
        assertNotNull(s.undoLast());
        var again = s.submit("а", "а", TIMING, covering(0.2));
        assertEquals("needs_detail", again.reason());
    }

    @Test
    void judge_input_carries_earlier_turns_and_only_unasked_follow_ups() {
        InterviewSession s = session(Level.INTERN);
        s.start();
        JudgeInput first = s.judgeInput("первый ответ");
        assertEquals(1, first.turns().size());
        assertEquals(TestKb.get().questions()[0].followUps().length, first.followUps().size());
        s.submit("первый ответ", "первый ответ", TIMING, covering(0.2));
        JudgeInput second = s.judgeInput("второй ответ");
        assertEquals(2, second.turns().size());
        assertEquals("первый ответ", second.turns().get(0).answer());
        assertEquals("второй ответ", second.turns().get(1).answer());
        assertEquals(first.followUps().size() - 1, second.followUps().size());
    }

    // ------------------------------------------------------------------ оценки по критериям

    private static InterviewReport reportFor(Verdict decision, Verdict scoring) {
        InterviewSession s = session(Level.INTERN);
        s.start();
        s.submit("текст", "джей ви эм это виртуальная машина которая исполняет байткод а джи ди кей включает компилятор",
                new AnswerTiming(1000, 9000), decision);
        if (scoring != null) s.applyScoring(s.lastAttempt(), scoring);
        return s.report();
    }

    @Test
    void scores_follow_the_models_semantic_assessment() {
        Verdict decision = covering(0.9);
        InterviewReport good = reportFor(decision, verdict(new double[]{.9, .9, .9}, null, null, null, null, 0.9, 0.9, 0.8));
        InterviewReport poor = reportFor(covering(0.1), verdict(new double[]{.1, .1, .1}, null, null, null, null, 0.1, 0.2, 0.2));
        assertTrue(good.completeness() > poor.completeness() + 4, good.completeness() + " vs " + poor.completeness());
        assertTrue(good.technical() > poor.technical() + 4, good.technical() + " vs " + poor.technical());
        assertTrue(good.terminology() > poor.terminology(), good.terminology() + " vs " + poor.terminology());
        assertTrue(good.total() <= 10);
    }

    @Test
    void same_words_but_wrong_content_scores_low_technical_even_with_matching_keywords() {
        Verdict scoring = verdict(new double[]{.8, .8, .8}, null, null, null, null, 0.8, 0.05, 0.5);
        InterviewReport wrong = reportFor(covering(0.8), scoring);
        InterviewReport right = reportFor(covering(0.8), verdict(new double[]{.8, .8, .8}, null, null, null, null, 0.8, 0.95, 0.5));
        assertTrue(right.technical() - wrong.technical() > 3.5, right.technical() + " vs " + wrong.technical());
    }

    @Test
    void report_lists_missing_facts_instead_of_random_keywords() {
        InterviewSession s = session(Level.INTERN);
        s.start();
        s.submit("т", "ответ", TIMING, verdict(new double[]{.9, .1, .1}, .02, .02, new int[]{0}, new double[]{1}, null, null, null));
        InterviewReport report = s.report();
        assertEquals(List.of("факт 1", "факт 2"), report.questions().get(0).missing());
        InterviewDetails details = s.details("Кандидат", java.time.Instant.now(), java.util.Map.of());
        var concepts = details.questions().get(0).concepts();
        assertEquals(3, concepts.size());
        assertTrue(concepts.get(0).said());
        assertFalse(concepts.get(1).said());
        assertTrue(details.questions().get(0).comment().contains("смысловая оценка модели"));
    }

    @Test
    void new_text_from_whisper_discards_the_stale_scoring_but_keeps_the_decision() {
        InterviewSession s = session(Level.INTERN);
        s.start();
        s.submit("старый текст", "старый текст", TIMING, covering(0.9));
        var ref = s.lastAttempt();
        s.applyScoring(ref, verdict(new double[]{.9, .9, .9}, null, null, null, null, 0.9, 0.9, 0.9));
        s.refine(ref, "новый уточнённый текст", "новый уточнённый текст", 2);
        InterviewDetails d = s.details("К", java.time.Instant.now(), java.util.Map.of());
        assertTrue(d.questions().get(0).comment().contains("смысловая оценка модели"));
        assertFalse(d.questions().get(0).comment().contains("точность"), "старая оценка точности не должна жить: " + d.questions().get(0).comment());
    }
}
