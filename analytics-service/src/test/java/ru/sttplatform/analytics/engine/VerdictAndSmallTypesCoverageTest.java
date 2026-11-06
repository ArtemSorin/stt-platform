package ru.sttplatform.analytics.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Мелкие типы движка: Verdict, Reasons, Level, QuestionBank, LongIntMap, AnswerAnalysis.withVerdict. */
class VerdictAndSmallTypesCoverageTest {

    private static Verdict v(List<Verdict.Fact> facts, Double ign, Double off, int[] idx, double[] share,
                             Double comp, Double corr, Double term, String src, long ms, boolean trunc) {
        return new Verdict(facts, ign, off, idx, share, comp, corr, term, src, ms, trunc);
    }

    // ---------------------------------------------------------------- Verdict

    @Test
    void facts_presence_and_average_probability() {
        assertFalse(v(null, null, null, null, null, null, null, null, "frida", 0, false).hasFacts());
        assertFalse(v(List.of(), null, null, null, null, null, null, null, "frida", 0, false).hasFacts());
        assertEquals(0, v(null, null, null, null, null, null, null, null, "frida", 0, false).factsCoverage());
        Verdict two = v(List.of(new Verdict.Fact("a", 1), new Verdict.Fact("b", 0.5)), null, null, null, null,
                null, null, null, "frida", 0, false);
        assertTrue(two.hasFacts());
        assertEquals(0.75, two.factsCoverage(), 1e-9);
    }

    @Test
    void merge_with_null_returns_the_same_verdict() {
        Verdict a = v(List.of(new Verdict.Fact("a", 1)), 0.1, 0.2, new int[]{0}, new double[]{1}, 0.3, 0.4, 0.5, "frida", 7, false);
        assertSame(a, a.mergedWith(null));
    }

    @Test
    void merge_prefers_non_empty_fields_of_the_later_verdict() {
        List<Verdict.Fact> f1 = List.of(new Verdict.Fact("old", 0.1)), f2 = List.of(new Verdict.Fact("new", 0.9));
        Verdict fast = v(f1, 0.1, 0.2, new int[]{0}, new double[]{1}, null, null, null, "frida", 30, true);
        Verdict slow = v(f2, 0.7, 0.8, new int[]{1}, new double[]{0.4}, 0.3, 0.4, 0.5, "other", 90, false);
        Verdict m = fast.mergedWith(slow);
        assertSame(f2, m.facts());
        assertEquals(0.7, m.ignorance());
        assertEquals(0.8, m.offTopic());
        assertArrayEquals(new int[]{1}, m.followUpIndex());
        assertArrayEquals(new double[]{0.4}, m.followUpShare());
        assertEquals(0.3, m.completeness());
        assertEquals(0.4, m.correctness());
        assertEquals(0.5, m.terminology());
        assertEquals("frida", m.source());                 // источник остаётся от быстрого заключения
        assertEquals(90, m.latencyMs());
        assertTrue(m.stateTruncated());                    // обрезка «липкая»
    }

    @Test
    void merge_keeps_earlier_fields_when_later_ones_are_missing() {
        List<Verdict.Fact> f1 = List.of(new Verdict.Fact("old", 0.1));
        Verdict fast = v(f1, 0.1, 0.2, new int[]{0}, new double[]{1}, 0.6, 0.7, 0.8, "frida", 100, false);
        Verdict empty = v(null, null, null, null, null, null, null, null, "frida", 5, true);
        Verdict m = fast.mergedWith(empty);
        assertSame(f1, m.facts());
        assertEquals(0.1, m.ignorance());
        assertEquals(0.2, m.offTopic());
        assertArrayEquals(new int[]{0}, m.followUpIndex());
        assertArrayEquals(new double[]{1}, m.followUpShare());
        assertEquals(0.6, m.completeness());
        assertEquals(0.7, m.correctness());
        assertEquals(0.8, m.terminology());
        assertEquals(100, m.latencyMs());
        assertTrue(m.stateTruncated());
        // пустой список фактов у позднего заключения не затирает ранний
        Verdict emptyFacts = v(List.of(), null, null, null, null, null, null, null, "frida", 1, false);
        assertSame(f1, fast.mergedWith(emptyFacts).facts());
    }

    // ---------------------------------------------------------------- AnswerAnalysis.withVerdict

    @Test
    void with_verdict_blends_coverage_and_ignorance_from_the_model() {
        AnswerAnalysis lexical = new AnswerAnalysis(12, 3, 2, 0.10, 0.10, 1, 2, true, 4, new int[]{1});
        assertSame(lexical, lexical.withVerdict(null));
        assertSame(lexical, lexical.withVerdict(v(null, null, null, null, null, null, null, null, "frida", 0, false)));

        Verdict plain = v(List.of(new Verdict.Fact("a", 1.0)), null, null, null, null, null, null, null, "frida", 0, false);
        AnswerAnalysis a = lexical.withVerdict(plain);
        assertEquals(1.0, a.coverage(), 1e-9);
        assertEquals(1.0, a.coreCoverage(), 1e-9);
        assertTrue(a.ignorance());                         // модель ничего не сказала про «не знаю» — остаётся лексическое
        assertEquals(12, a.words());
        assertEquals(2, a.hedgeCount());

        Verdict blended = v(List.of(new Verdict.Fact("a", 1.0)), 0.2, null, null, null, 0.0, null, null, "frida", 0, false);
        AnswerAnalysis b = lexical.withVerdict(blended);
        assertEquals(0.6, b.coverage(), 1e-9);             // 0,6·1,0 + 0,4·0,0
        assertFalse(b.ignorance());                        // модель: вероятность «не знаю» 0,2 < 0,5
        assertTrue(lexical.withVerdict(v(List.of(new Verdict.Fact("a", 1.0)), 0.5, null, null, null, null, null, null,
                "frida", 0, false)).ignorance());
    }

    // ---------------------------------------------------------------- Reasons

    @Test
    void reasons_cover_every_known_code_and_pass_unknown_through() {
        String[] known = {"sufficient", "needs_detail", "ignorance", "no_answer", "no_signal", "off_topic",
                "follow_up_budget", "stagnation", "no_follow_ups"};
        for (String k : known) {
            assertNotEquals(k, Reasons.text(k), k);
            assertFalse(Reasons.text(k).isBlank());
        }
        assertEquals("", Reasons.text(null));
        assertEquals("custom", Reasons.text("custom"));
        assertEquals("уточняющий вопрос", Reasons.action("ASK_FOLLOW_UP"));
        assertEquals("следующий вопрос", Reasons.action("NEXT_QUESTION"));
        assertEquals("конец списка", Reasons.action("FINISH"));
        assertEquals("", Reasons.action(null));
        assertEquals("OTHER", Reasons.action("OTHER"));
    }

    // ---------------------------------------------------------------- Level

    @Test
    void level_parses_russian_and_english_names_in_any_case() {
        assertEquals(Level.INTERN, Level.parse(" Стажёр "));
        assertEquals(Level.INTERN, Level.parse("стажер"));
        assertEquals(Level.INTERN, Level.parse("INTERN"));
        assertEquals(Level.JUNIOR, Level.parse("Junior"));
        assertEquals(Level.JUNIOR, Level.parse("джуниор"));
        assertEquals(Level.MIDDLE, Level.parse("middle"));
        assertEquals(Level.MIDDLE, Level.parse("миддл"));
        assertEquals(Level.MIDDLE, Level.parse("мидл"));
        assertEquals(Level.SENIOR, Level.parse("Senior"));
        assertEquals(Level.SENIOR, Level.parse("сеньор"));
        assertThrows(IllegalArgumentException.class, () -> Level.parse("архитектор"));
        assertTrue(Level.INTERN.sufficientCoverage < Level.SENIOR.sufficientCoverage);
    }

    // ---------------------------------------------------------------- QuestionBank

    @Test
    void question_bank_skips_blank_and_comment_lines_and_trims_follow_ups() {
        String tsv = "# комментарий\n\nСтажёр\tJava\tВопрос?\tЭталон\tЗаметка\t а ||  б ||\n"
                + "Senior\tСети\tВопрос 2?\tЭталон 2\t\t\r\n";
        List<Question> qs = QuestionBank.parse(tsv);
        assertEquals(2, qs.size());
        assertEquals(1, qs.get(0).id());
        assertEquals(Level.INTERN, qs.get(0).level());
        assertEquals(List.of("а", "б"), qs.get(0).followUps());
        assertEquals(2, qs.get(1).id());
        assertEquals(List.of(), qs.get(1).followUps());
        assertEquals("", qs.get(1).notes());
    }

    @Test
    void question_bank_rejects_short_rows() {
        var e = assertThrows(IllegalArgumentException.class, () -> QuestionBank.parse("Стажёр\tJava\tВопрос?"));
        assertTrue(e.getMessage().contains("6"));
    }

    // ---------------------------------------------------------------- LongIntMap

    @Test
    void long_int_map_stores_overwrites_grows_and_returns_default() {
        LongIntMap m = new LongIntMap(1);
        assertEquals(-7, m.get(42, -7));
        m.put(42, 1);
        m.put(42, 2);                                      // перезапись не увеличивает размер
        assertEquals(1, m.size());
        assertEquals(2, m.get(42, -7));
        for (int i = 0; i < 500; i++) m.put(1_000_000_007L * i + 3, i);   // несколько удвоений таблицы
        assertEquals(501, m.size());
        for (int i = 0; i < 500; i++) assertEquals(i, m.get(1_000_000_007L * i + 3, -1), "ключ " + i);
        assertEquals(2, m.get(42, -7));
        assertEquals(-1, m.get(-123456789L, -1));
    }

    @Test
    void long_int_map_handles_colliding_slots_with_linear_probing() {
        LongIntMap m = new LongIntMap(4);
        long[] keys = new long[64];
        for (int i = 0; i < keys.length; i++) { keys[i] = (long) i << 40; m.put(keys[i], i); }
        for (int i = 0; i < keys.length; i++) assertEquals(i, m.get(keys[i], -1));
        assertEquals(keys.length, m.size());
    }
}
