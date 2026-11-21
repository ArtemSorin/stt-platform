package ru.sttplatform.gateway.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class InterviewJournalReplayTest {
    @Test void reconstructs_retracted_answer_and_next_question(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("events.jsonl");
        try (var log = new InterviewEventJournal(path, "s", 32)) {
            log.append("ready", 100, Map.of("attemptId", 1, "questionId", "Q1"));
            log.append("decision", 200, Map.of("attemptId", 1, "questionId", "Q1", "answerId", 1));
            log.append("retract", 210, Map.of("attemptId", 1, "answerId", 1));
            log.append("decision", 300, Map.of("attemptId", 1, "questionId", "Q1", "answerId", 2));
            log.append("ready", 310, Map.of("attemptId", 2, "questionId", "Q1", "followUpId", "F1"));
            log.append("decision", 400, Map.of("attemptId", 2, "questionId", "Q1", "followUpId", "F1", "answerId", 3));
            log.append("journal_complete", 400, Map.of("attemptId", 2));
        }
        var intervals = new InterviewJournalReplay().read(path, "s", 400);
        assertEquals(2, intervals.size());
        assertEquals(2, intervals.get(0).answerId());
        assertEquals(100, intervals.get(0).start());
        assertEquals("F1", intervals.get(1).followUpId());
        assertThrows(java.io.IOException.class, () -> new InterviewJournalReplay().read(path, "different", 400));
    }
    @Test void rejects_corrupted_event_sequence_and_invalid_position(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("events.jsonl");
        java.nio.file.Files.writeString(path, "{\"sessionId\":\"s\",\"eventSequence\":1,\"audioSample\":0,\"attemptId\":0}\n");
        assertThrows(java.io.IOException.class, () -> new InterviewJournalReplay().read(path, "s", 400));
        java.nio.file.Files.writeString(path, "{\"sessionId\":\"s\",\"eventSequence\":0,\"audioSample\":401,\"attemptId\":0}\n");
        assertThrows(java.io.IOException.class, () -> new InterviewJournalReplay().read(path, "s", 400));
    }

    @Test void rejects_cross_attempt_retraction_and_reused_answer_id(@TempDir Path dir) throws Exception {
        Path wrongAttempt = dir.resolve("wrong.jsonl");
        try (var log = new InterviewEventJournal(wrongAttempt, "s", 32)) {
            log.append("ready", 10, Map.of("attemptId", 1, "questionId", "Q1"));
            log.append("decision", 20, Map.of("attemptId", 1, "questionId", "Q1", "answerId", 1));
            log.append("retract", 21, Map.of("attemptId", 2, "answerId", 1));
        }
        assertThrows(java.io.IOException.class, () -> new InterviewJournalReplay().read(wrongAttempt, "s", 100));
        Path duplicate = dir.resolve("duplicate.jsonl");
        try (var log = new InterviewEventJournal(duplicate, "s", 32)) {
            log.append("ready", 10, Map.of("attemptId", 1, "questionId", "Q1"));
            log.append("decision", 20, Map.of("attemptId", 1, "questionId", "Q1", "answerId", 1));
            log.append("retract", 21, Map.of("attemptId", 1, "answerId", 1));
            log.append("decision", 30, Map.of("attemptId", 1, "questionId", "Q1", "answerId", 1));
        }
        assertThrows(java.io.IOException.class, () -> new InterviewJournalReplay().read(duplicate, "s", 100));
    }

    @Test void seeded_questions_and_followups_map_only_candidate_words(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("seeded.jsonl");
        var random = new java.util.Random(20261113L);
        var words = new java.util.ArrayList<FinalAnswerAssembler.Word>();
        var expected = new java.util.ArrayList<String>();
        long position = 0;
        try (var journal = new InterviewEventJournal(path, "seeded", 128)) {
            for (int i = 1; i <= 12; i++) {
                String question = "Q" + ((i + 1) / 2);
                String followup = i % 2 == 0 ? question + "-F1" : null;
                long start = position + random.nextInt(20);
                long end = start + 100 + random.nextInt(50);
                var context = new java.util.LinkedHashMap<String, Object>();
                context.put("attemptId", i); context.put("questionId", question);
                if (followup != null) context.put("followUpId", followup);
                journal.append("ready", start, context);
                var decision = new java.util.LinkedHashMap<>(context);
                decision.put("answerId", i);
                journal.append("decision", end, decision);
                String text = "ответ_" + i;
                expected.add(text);
                words.add(new FinalAnswerAssembler.Word(text, start + 10, start + 20, "Кандидат", "system", "whisper"));
                words.add(new FinalAnswerAssembler.Word("интервьюер_" + i, start + 10, start + 20, "Интервьюер 1", "microphone", "whisper"));
                position = end + 10;
            }
            journal.append("journal_complete", position, Map.of("attemptId", 12));
        }
        var intervals = new InterviewJournalReplay().read(path, "seeded", position);
        var answers = new FinalAnswerAssembler().assemble(intervals, words, false);
        assertEquals(expected, answers.stream().map(FinalAnswerAssembler.Answer::text).toList());
        assertEquals("Q1-F1", answers.get(1).interval().followUpId());
        assertEquals("Q6-F1", answers.get(11).interval().followUpId());
        assertTrue(answers.stream().allMatch(answer -> answer.warnings().isEmpty()));
    }

    @Test void rejects_a_truncated_journal_without_completion_marker(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("truncated.jsonl");
        try (var log = new InterviewEventJournal(path, "s", 16)) {
            log.append("ready", 10, Map.of("attemptId", 1, "questionId", "Q1"));
            log.append("decision", 20, Map.of("attemptId", 1, "questionId", "Q1", "answerId", 1));
        }
        assertThrows(java.io.IOException.class, () -> new InterviewJournalReplay().read(path, "s", 20));
    }

}
