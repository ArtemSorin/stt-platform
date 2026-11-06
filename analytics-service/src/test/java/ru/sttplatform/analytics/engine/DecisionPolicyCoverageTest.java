package ru.sttplatform.analytics.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Решение «уточнить или идти дальше»: каждая ветка DecisionPolicy — и правила на ключевых словах (запасной путь),
 * и решение по заключению модели FRIDA. Объекты собираются вручную, без банка вопросов: тест не зависит от данных.
 */
class DecisionPolicyCoverageTest {

    private static final Level LEVEL = Level.INTERN;           // порог «достаточно» 0,40; с моделью — 0,50

    /** Вопрос с пулом из {@code pool} понятий и уточнениями, привязанными к индексам пула. */
    private static CompiledQuestion cq(double[] weights, int[]... followUpPools) {
        Question q = new Question(1, LEVEL, "тема", "вопрос?", "эталон", "", List.of());
        int n = weights.length;
        CompiledQuestion.FollowUp[] fus = new CompiledQuestion.FollowUp[followUpPools.length];
        for (int i = 0; i < fus.length; i++) fus[i] = new CompiledQuestion.FollowUp("уточнение " + i, followUpPools[i]);
        int[] ids = new int[n];
        boolean[] core = new boolean[n];
        int[] poolIndex = new int[n];
        for (int i = 0; i < n; i++) { ids[i] = i; poolIndex[i] = i; }
        return new CompiledQuestion(q, ids, weights, core, 1, 1, poolIndex, new boolean[n], fus, 10, 5, 0);
    }

    private static AnswerAnalysis an(int words, double coverage, boolean ignorance, double tech, double onTopic) {
        return new AnswerAnalysis(words, tech, onTopic, coverage, coverage, 0, 0, ignorance, 0, new int[0]);
    }

    private static Verdict verdict(Double ignorance, Double offTopic, int[] idx, double[] share) {
        return new Verdict(List.of(new Verdict.Fact("факт", 0.5)), ignorance, offTopic, idx, share,
                null, null, null, "frida", 10, false);
    }

    private static DecisionPolicy.Decision rules(DecisionPolicy p, CompiledQuestion q, AnswerAnalysis a, double prev,
                                                  int used, boolean[] asked, boolean[] hits) {
        return p.decide(q, LEVEL, a, prev, used, asked, hits);
    }

    // ------------------------------------------------------------------ правила на ключевых словах

    @Test
    void rules_no_words_means_no_answer() {
        var p = new DecisionPolicy(2, 0.08);
        var d = rules(p, cq(new double[]{1}, new int[]{0}), an(0, 0, false, 0, 0), 0, 0, new boolean[1], new boolean[1]);
        assertFalse(d.followUp());
        assertEquals("no_answer", d.reason());
        assertEquals(-1, d.followUpIndex());
    }

    @Test
    void rules_ignorance_with_weak_coverage_is_not_pressed() {
        var p = new DecisionPolicy(2, 0.08);
        var d = rules(p, cq(new double[]{1}, new int[]{0}), an(5, 0.02, true, 1, 1), 0, 0, new boolean[1], new boolean[1]);
        assertEquals("ignorance", d.reason());
    }

    @Test
    void rules_ignorance_with_decent_coverage_falls_through_to_sufficient() {
        var p = new DecisionPolicy(2, 0.08);
        var d = rules(p, cq(new double[]{1}, new int[]{0}), an(20, 0.5, true, 1, 1), 0, 0, new boolean[1], new boolean[1]);
        assertEquals("sufficient", d.reason());
    }

    @Test
    void rules_sufficient_coverage_moves_on_at_exact_threshold() {
        var p = new DecisionPolicy(2, 0.08);
        var d = rules(p, cq(new double[]{1}, new int[]{0}), an(20, LEVEL.sufficientCoverage, false, 1, 1), 0, 0,
                new boolean[1], new boolean[1]);
        assertFalse(d.followUp());
        assertEquals("sufficient", d.reason());
    }

    @Test
    void rules_follow_up_budget_is_respected() {
        var p = new DecisionPolicy(2, 0.08);
        var d = rules(p, cq(new double[]{1}, new int[]{0}), an(20, 0.2, false, 1, 1), 0, 2, new boolean[1], new boolean[1]);
        assertEquals("follow_up_budget", d.reason());
    }

    @Test
    void rules_confident_but_off_topic_answer_is_not_pressed() {
        var p = new DecisionPolicy(2, 0.08);
        var d = rules(p, cq(new double[]{1}, new int[]{0}), an(30, 0.01, false, 10, 1), 0, 0, new boolean[1], new boolean[1]);
        assertEquals("off_topic", d.reason());
    }

    @Test
    void rules_without_technical_tokens_focus_is_neutral_so_short_answer_is_no_signal() {
        var p = new DecisionPolicy(2, 0.08);
        var d = rules(p, cq(new double[]{1}, new int[]{0}), an(3, 0.01, false, 0, 0), 0, 0, new boolean[1], new boolean[1]);
        assertEquals("no_signal", d.reason());
    }

    @Test
    void rules_long_weak_answer_on_topic_still_asks() {
        var p = new DecisionPolicy(2, 0.08);
        var d = rules(p, cq(new double[]{1}, new int[]{0}), an(30, 0.05, false, 10, 8), 0, 0, new boolean[1], new boolean[1]);
        assertTrue(d.followUp());
        assertEquals("needs_detail", d.reason());
        assertEquals(0, d.followUpIndex());
    }

    @Test
    void rules_stagnation_after_a_follow_up_that_added_nothing() {
        var p = new DecisionPolicy(3, 0.08);
        var d = rules(p, cq(new double[]{1}, new int[]{0}, new int[]{0}), an(30, 0.20, false, 10, 8), 0.19, 1,
                new boolean[]{true, false}, new boolean[1]);
        assertEquals("stagnation", d.reason());
    }

    @Test
    void rules_progress_after_follow_up_keeps_asking() {
        var p = new DecisionPolicy(3, 0.08);
        var d = rules(p, cq(new double[]{1}, new int[]{0}, new int[]{0}), an(30, 0.30, false, 10, 8), 0.10, 1,
                new boolean[]{true, false}, new boolean[1]);
        assertTrue(d.followUp());
        assertEquals(1, d.followUpIndex());
    }

    @Test
    void rules_first_attempt_is_never_stagnation() {
        var p = new DecisionPolicy(3, 0.08);
        var d = rules(p, cq(new double[]{1}, new int[]{0}), an(30, 0.20, false, 10, 8), 0.20, 0, new boolean[1], new boolean[1]);
        assertTrue(d.followUp());
    }

    @Test
    void rules_pick_the_follow_up_that_hits_the_heaviest_unnamed_concept() {
        var p = new DecisionPolicy(3, 0.08);
        CompiledQuestion q = cq(new double[]{1, 5, 2}, new int[]{0}, new int[]{1, 2}, new int[]{2});
        boolean[] hits = {false, false, true};          // понятие 2 уже названо
        var d = rules(p, q, an(30, 0.2, false, 10, 8), 0, 0, new boolean[3], hits);
        assertEquals(1, d.followUpIndex());              // 5 против 1 против 0
    }

    @Test
    void rules_skip_asked_follow_ups_and_run_out() {
        var p = new DecisionPolicy(5, 0.08);
        CompiledQuestion q = cq(new double[]{1, 2}, new int[]{0}, new int[]{1});
        var d = rules(p, q, an(30, 0.2, false, 10, 8), 0, 0, new boolean[]{true, false}, new boolean[2]);
        assertEquals(1, d.followUpIndex());
        var none = rules(p, q, an(30, 0.2, false, 10, 8), 0, 0, new boolean[]{true, true}, new boolean[2]);
        assertEquals("no_follow_ups", none.reason());
    }

    @Test
    void rules_question_without_follow_ups_has_nothing_to_ask() {
        var p = new DecisionPolicy(2, 0.08);
        var d = rules(p, cq(new double[]{1}), an(30, 0.2, false, 10, 8), 0, 0, new boolean[0], new boolean[1]);
        assertEquals("no_follow_ups", d.reason());
    }

    @Test
    void rules_a_follow_up_with_zero_weight_is_still_selectable() {
        var p = new DecisionPolicy(2, 0.08);
        CompiledQuestion q = cq(new double[]{1}, new int[]{0});
        var d = rules(p, q, an(30, 0.2, false, 10, 8), 0, 0, new boolean[1], new boolean[]{true});
        assertTrue(d.followUp());
        assertEquals(0, d.followUpIndex());
    }

    // ------------------------------------------------------------------ по заключению модели

    private final DecisionPolicy model = new DecisionPolicy(2, 0.08, DecisionPolicy.Thresholds.defaults());

    private DecisionPolicy.Decision byModel(CompiledQuestion q, AnswerAnalysis a, double prev, int used,
                                            boolean[] asked, Verdict v) {
        return model.decide(q, LEVEL, a, prev, used, asked, new boolean[q.poolSize()], v);
    }

    @Test
    void model_null_or_factless_verdict_falls_back_to_rules() {
        CompiledQuestion q = cq(new double[]{1}, new int[]{0});
        var a = an(20, 0.5, false, 1, 1);
        assertEquals("sufficient", byModel(q, a, 0, 0, new boolean[1], null).reason());
        Verdict noFacts = new Verdict(null, 0.9, null, null, null, null, null, null, "frida", 1, false);
        assertEquals("sufficient", byModel(q, a, 0, 0, new boolean[1], noFacts).reason());
        Verdict emptyFacts = new Verdict(List.of(), 0.9, null, null, null, null, null, null, "frida", 1, false);
        assertEquals("sufficient", byModel(q, a, 0, 0, new boolean[1], emptyFacts).reason());
    }

    @Test
    void model_no_words_means_no_answer() {
        var d = byModel(cq(new double[]{1}, new int[]{0}), an(0, 0, false, 0, 0), 0, 0, new boolean[1],
                verdict(null, null, null, null));
        assertEquals("no_answer", d.reason());
    }

    @Test
    void model_ignorance_needs_both_probability_and_low_coverage() {
        CompiledQuestion q = cq(new double[]{1}, new int[]{0});
        assertEquals("ignorance", byModel(q, an(10, 0.10, false, 1, 1), 0, 0, new boolean[1],
                verdict(0.6, null, null, null)).reason());
        // вероятность ниже порога — модель не уверена, что кандидат не знает
        assertNotEquals("ignorance", byModel(q, an(10, 0.10, false, 1, 1), 0, 0, new boolean[1],
                verdict(0.59, null, null, null)).reason());
        // покрытие выше порога «не знаю» — отказ не засчитываем
        assertNotEquals("ignorance", byModel(q, an(10, 0.25, false, 1, 1), 0, 0, new boolean[1],
                verdict(0.9, null, null, null)).reason());
    }

    @Test
    void model_off_topic_needs_both_probability_and_low_coverage() {
        CompiledQuestion q = cq(new double[]{1}, new int[]{0});
        assertEquals("off_topic", byModel(q, an(10, 0.10, false, 1, 1), 0, 0, new boolean[1],
                verdict(null, 0.6, null, null)).reason());
        assertNotEquals("off_topic", byModel(q, an(10, 0.10, false, 1, 1), 0, 0, new boolean[1],
                verdict(null, 0.59, null, null)).reason());
        assertNotEquals("off_topic", byModel(q, an(10, 0.20, false, 1, 1), 0, 0, new boolean[1],
                verdict(null, 0.9, null, null)).reason());
    }

    @Test
    void model_sufficient_uses_level_threshold_plus_shift_capped_below_one() {
        CompiledQuestion q = cq(new double[]{1}, new int[]{0});
        assertEquals("sufficient", byModel(q, an(10, 0.50, false, 1, 1), 0, 0, new boolean[1],
                verdict(null, null, new int[]{0}, new double[]{1})).reason());
        assertNotEquals("sufficient", byModel(q, an(10, 0.49, false, 1, 1), 0, 0, new boolean[1],
                verdict(null, null, new int[]{0}, new double[]{1})).reason());
        // потолок 0,95: даже при сдвиге 0,6 порог не превышает 0,95
        var high = new DecisionPolicy(2, 0.08, new DecisionPolicy.Thresholds(.6, .25, .6, .2, .05, 0.6));
        var d = high.decide(q, Level.SENIOR, an(10, 0.95, false, 1, 1), 0, 0, new boolean[1], new boolean[1],
                verdict(null, null, null, null));
        assertEquals("sufficient", d.reason());
    }

    @Test
    void model_budget_and_stagnation() {
        CompiledQuestion q = cq(new double[]{1}, new int[]{0}, new int[]{0});
        Verdict v = verdict(null, null, new int[]{0, 1}, new double[]{.5, .5});
        assertEquals("follow_up_budget", byModel(q, an(10, 0.2, false, 1, 1), 0, 2, new boolean[2], v).reason());
        assertEquals("stagnation", byModel(q, an(10, 0.20, false, 1, 1), 0.16, 1, new boolean[2], v).reason());
        assertEquals("needs_detail", byModel(q, an(10, 0.20, false, 1, 1), 0.10, 1, new boolean[2], v).reason());
        // первая попытка: прирост не проверяется
        assertEquals("needs_detail", byModel(q, an(10, 0.20, false, 1, 1), 0.20, 0, new boolean[2], v).reason());
    }

    @Test
    void model_picks_the_highest_share_among_unasked_valid_indexes() {
        CompiledQuestion q = cq(new double[]{1}, new int[]{0}, new int[]{0}, new int[]{0});
        Verdict v = verdict(null, null, new int[]{-1, 0, 1, 2, 7}, new double[]{.9, .1, .5, .3, .99});
        var d = byModel(q, an(10, 0.2, false, 1, 1), 0, 0, new boolean[3], v);
        assertTrue(d.followUp());
        assertEquals(1, d.followUpIndex());                // -1 и 7 вне диапазона отброшены
        var skipped = byModel(q, an(10, 0.2, false, 1, 1), 0, 0, new boolean[]{false, true, false}, v);
        assertEquals(2, skipped.followUpIndex());          // 1 уже задан
    }

    @Test
    void model_with_everything_asked_or_empty_choice_has_no_follow_ups() {
        CompiledQuestion q = cq(new double[]{1}, new int[]{0});
        var d = byModel(q, an(10, 0.2, false, 1, 1), 0, 0, new boolean[]{true},
                verdict(null, null, new int[]{0}, new double[]{1}));
        assertEquals("no_follow_ups", d.reason());
        var empty = byModel(q, an(10, 0.2, false, 1, 1), 0, 0, new boolean[1],
                verdict(null, null, new int[0], new double[0]));
        assertEquals("no_follow_ups", empty.reason());
    }

    @Test
    void model_without_its_own_choice_uses_the_unnamed_concept_rule() {
        CompiledQuestion q = cq(new double[]{1, 5}, new int[]{0}, new int[]{1});
        // индекс есть, а долей нет (и наоборот) — выбор модели неполон, работает прежнее правило
        for (Verdict v : List.of(verdict(null, null, null, null), verdict(null, null, new int[]{0}, null),
                verdict(null, null, null, new double[]{1}))) {
            var d = model.decide(q, LEVEL, an(10, 0.2, false, 1, 1), 0, 0, new boolean[2], new boolean[2], v);
            assertTrue(d.followUp());
            assertEquals(1, d.followUpIndex());
        }
        var asked = model.decide(q, LEVEL, an(10, 0.2, false, 1, 1), 0, 0, new boolean[]{false, true}, new boolean[2],
                verdict(null, null, null, null));
        assertEquals(0, asked.followUpIndex());
        var none = model.decide(q, LEVEL, an(10, 0.2, false, 1, 1), 0, 0, new boolean[]{true, true}, new boolean[2],
                verdict(null, null, null, null));
        assertEquals("no_follow_ups", none.reason());
    }

    @Test
    void thresholds_defaults_match_documented_values() {
        var t = DecisionPolicy.Thresholds.defaults();
        assertEquals(0.6, t.ignoranceP());
        assertEquals(0.25, t.ignoranceMaxCov());
        assertEquals(0.6, t.offTopicP());
        assertEquals(0.20, t.offTopicMaxCov());
        assertEquals(0.05, t.stagnationDelta());
        assertEquals(0.10, t.sufficientShift());
    }
}
