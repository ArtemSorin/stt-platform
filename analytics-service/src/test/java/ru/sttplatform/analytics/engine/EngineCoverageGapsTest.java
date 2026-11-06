package ru.sttplatform.analytics.engine;

import org.junit.jupiter.api.Test;
import ru.sttplatform.analytics.export.InterviewExcelReport;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Остатки: откат основного ответа, чтение «h2/p95», размер словаря и отчёт с неотвеченными уточнениями. */
class EngineCoverageGapsTest {

    private static final String WEAK = "джей ви эм это виртуальная машина которая исполняет байткод";

    @Test
    void knowledge_base_reports_a_non_empty_vocabulary() {
        assertTrue(TestKb.get().vocabSize() > 100);
    }

    @Test
    void undoing_the_main_answer_returns_the_main_question_again() {
        InterviewSession s = new InterviewSession(TestKb.get(), List.of(Level.INTERN), new DecisionPolicy(2, 0.08));
        InterviewSession.Prompt first = s.start();
        InterviewSession.Step step = s.submit(TestKb.get().byId(first.questionId()).question().reference(), new AnswerTiming(1000, 5000));
        assertNotNull(step.next(), "после основного ответа есть следующий вопрос");
        InterviewSession.Prompt back = s.undoLast();
        assertEquals(first.questionId(), back.questionId());
        assertFalse(back.followUp());
        assertEquals(0, back.followUpNumber());
    }

    @Test
    void undoing_a_follow_up_answer_returns_that_follow_up_again() {
        InterviewSession s = new InterviewSession(TestKb.get(), List.of(Level.INTERN), new DecisionPolicy(2, 0.08));
        s.start();
        InterviewSession.Step weak = s.submit(WEAK, new AnswerTiming(1000, 5000));
        assertEquals(InterviewSession.Action.ASK_FOLLOW_UP, weak.action());
        s.submit("не знаю", new AnswerTiming(1000, 2000));          // ответ на уточнение
        InterviewSession.Prompt back = s.undoLast();
        assertTrue(back.followUp(), "откат ответа на уточнение возвращает это уточнение");
    }

    @Test
    void spoken_text_reads_letters_with_digits_part_by_part() {
        SpokenText spoken = new SpokenText(Map.of("h", "эйч", "p", "пи"));
        String out = spoken.speak("h2 p95 3d");
        assertTrue(out.contains("2") && out.contains("95"), out);
        assertTrue(out.contains("эйч") && out.contains("пи"), out);
    }

    private static Map<String, byte[]> unzip(byte[] xlsx) throws Exception {
        Map<String, byte[]> parts = new java.util.HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) parts.put(e.getName(), in.readAllBytes());
        }
        return parts;
    }

    @Test
    void report_marks_follow_ups_that_were_asked_but_not_answered_because_the_interview_ended() throws Exception {
        InterviewSession s = new InterviewSession(TestKb.get(), List.of(Level.INTERN), new DecisionPolicy(2, 0.08));
        s.start();
        assertEquals(InterviewSession.Action.ASK_FOLLOW_UP, s.submit(WEAK, new AnswerTiming(1000, 5000)).action());
        InterviewDetails details = s.details("Мария", Instant.now().minusSeconds(120), Map.of());
        String strings = new String(unzip(InterviewExcelReport.build(details)).get("xl/sharedStrings.xml"), StandardCharsets.UTF_8);
        assertTrue(strings.contains("— нет ответа —"), "уточнение без ответа помечено в отчёте");
        assertTrue(strings.contains("— интервью завершено до ответа —"), "и в подробном листе");
        assertTrue(strings.contains("Мария"));
    }
}
