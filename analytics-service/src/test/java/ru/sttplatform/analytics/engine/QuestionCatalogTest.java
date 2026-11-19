package ru.sttplatform.analytics.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuestionCatalogTest {
    private static final String QUESTIONS = "Q2\t2\tJunior\tJava\tВторой?\tОтвет\tПримечание\n"
            + "Q1\t1\tJunior\tJava\tПервый?\tЭталон\t\n";
    private static final String FOLLOWUPS = "Q1-F2\tQ1\tКак?\tТак.\tПроверить\n";

    @Test void retains_ids_and_orders_questions_without_relying_on_row_number() {
        var catalog = QuestionCatalog.parse(QUESTIONS, FOLLOWUPS);
        assertEquals("Q1", catalog.get(0).id());
        assertEquals("Q1-F2", catalog.get(0).followUps().get(0).id());
        assertEquals("Так.", catalog.get(0).followUps().get(0).reference());
        assertEquals("Q2", catalog.get(1).id());
        var converted = QuestionCatalog.toQuestions(catalog);
        assertEquals("Q1", converted.get(0).stableId());
        assertEquals("Q1-F2", converted.get(0).followUpDetails().get(0).id());
        assertEquals("Так.", converted.get(0).followUpDetails().get(0).reference());
        assertThrows(UnsupportedOperationException.class, () -> catalog.get(0).followUps().clear());
    }

    @Test void rejects_duplicates_unknown_parent_and_missing_reference() {
        assertThrows(IllegalArgumentException.class, () -> QuestionCatalog.parse(QUESTIONS, FOLLOWUPS + FOLLOWUPS));
        assertThrows(IllegalArgumentException.class, () -> QuestionCatalog.parse(QUESTIONS, "Q1\tQ2\tВопрос\tОтвет\t\n"));
        assertThrows(IllegalArgumentException.class, () -> QuestionCatalog.parse(QUESTIONS, "F\tmissing\tВопрос\tОтвет\t\n"));
        assertThrows(IllegalArgumentException.class, () -> QuestionCatalog.parse(QUESTIONS, "F\tQ1\tВопрос\t\t\n"));
        assertThrows(IllegalArgumentException.class, () -> QuestionCatalog.parse(QUESTIONS + QUESTIONS, FOLLOWUPS));
    }
    @Test void example_files_load_into_live_knowledge_and_preserve_followup_data() throws Exception {
        var root = java.nio.file.Path.of("demo");
        var entries = QuestionCatalog.parse(java.nio.file.Files.readString(root.resolve("catalog-questions.tsv")),
                java.nio.file.Files.readString(root.resolve("catalog-followups.tsv")));
        var knowledge = InterviewKnowledge.build(QuestionCatalog.toQuestions(entries), Glossary.parse(""));
        var first = knowledge.questions()[0].question();
        assertEquals("Q1", first.stableId());
        assertEquals("Q1-F1", first.followUpDetails().get(0).id());
        assertTrue(first.followUpDetails().get(0).reference().contains("байткод"));
    }

    @Test void explicit_order_is_preserved_across_levels() {
        var entries = QuestionCatalog.parse("legacy-custom\t1\tSenior\tJava\tПервый\tЭталон\t\n"
                + "Q2\t2\tJunior\tJava\tВторой\tЭталон\t\n", "");
        var knowledge = InterviewKnowledge.build(QuestionCatalog.toQuestions(entries), Glossary.parse(""));
        var session = new InterviewSession(knowledge, java.util.List.of(), new DecisionPolicy(0, 0.08));
        assertEquals("legacy-custom", session.start().stableQuestionId());
    }

    @Test void followup_evaluation_uses_its_own_reference() {
        var entries = QuestionCatalog.parse("Q1\t1\tJunior\tJava\tВопрос\tОсновной эталон\t\n",
                "Q1-F1\tQ1\tУточнение\tСвой эталон\tСвои критерии\n");
        var knowledge = InterviewKnowledge.build(QuestionCatalog.toQuestions(entries), Glossary.parse(""));
        var session = new InterviewSession(knowledge, java.util.List.of(), new DecisionPolicy(2, 0.08));
        session.start();
        var verdict = new Verdict(java.util.List.of(new Verdict.Fact("Факт", 0.1)), 0.0, 0.0,
                new int[]{0}, new double[]{1.0}, null, null, null, "frida", 1, false);
        var step = session.submit("немного знаю", "немного знаю", new AnswerTiming(100, 200), verdict);
        assertTrue(step.next().followUp());
        session.submit("Ответ уточнения", new AnswerTiming(100, 200));
        var input = session.followUpJudgeInput(session.lastAttempt());
        assertEquals("Свой эталон", input.reference());
        assertEquals("Свои критерии", input.notes());
        assertEquals("Ответ уточнения", input.turns().get(0).answer());
        var before = session.report();
        var followUpVerdict = new Verdict(java.util.List.of(new Verdict.Fact("Факт уточнения", 1.0)), null, null,
                null, null, 1.0, 1.0, 1.0, "frida", 1, false);
        session.applyFollowUpScoring(session.lastAttempt(), followUpVerdict);
        var after = session.report();
        assertTrue(after.completeness() > before.completeness());
        assertTrue(after.technical() > before.technical());
        assertTrue(after.terminology() > before.terminology());
        assertEquals(before.speed(), after.speed());
    }

    @Test void rejects_followup_attached_to_the_wrong_parent_or_text() {
        var wrongParent = new QuestionCatalog.FollowUp("F1", "Q2", "Уточнение", "Эталон", "");
        assertThrows(IllegalArgumentException.class, () -> new Question(1, Level.JUNIOR, "Java", "Вопрос", "Эталон", "",
                java.util.List.of("Уточнение"), "Q1", java.util.List.of(wrongParent), true));
        var wrongText = new QuestionCatalog.FollowUp("F1", "Q1", "Другой текст", "Эталон", "");
        assertThrows(IllegalArgumentException.class, () -> new Question(1, Level.JUNIOR, "Java", "Вопрос", "Эталон", "",
                java.util.List.of("Уточнение"), "Q1", java.util.List.of(wrongText), true));
    }

    @Test void full_migration_draft_cannot_be_loaded_without_followup_references() throws Exception {
        var root = java.nio.file.Path.of("demo");
        String questions = java.nio.file.Files.readString(root.resolve("catalog-full-draft-questions.tsv"));
        String followups = java.nio.file.Files.readString(root.resolve("catalog-full-draft-followups.tsv"));
        assertEquals(50, QuestionCatalog.parse(questions, "").size());
        assertThrows(IllegalArgumentException.class, () -> QuestionCatalog.parse(questions, followups));
        var temporary = QuestionCatalog.parseWithoutFollowUpReferences(questions, followups);
        assertEquals(50, temporary.size());
        assertEquals("Q001-F01", temporary.get(0).followUps().get(0).id());
        assertTrue(temporary.get(0).followUps().get(0).reference().isBlank());
    }

    @Test void utf8_bom_before_comments_is_accepted() {
        String questions = "\uFEFF# questionId\torder\tlevel\ttopic\tquestion\treference\tnotes\n" + QUESTIONS;
        String followups = "\uFEFF# followUpId\tquestionId\tquestion\treference\tnotes\n" + FOLLOWUPS;
        var result = QuestionCatalog.parse(questions, followups);
        assertEquals("Q1-F2", result.get(0).followUps().get(0).id());
    }

}
