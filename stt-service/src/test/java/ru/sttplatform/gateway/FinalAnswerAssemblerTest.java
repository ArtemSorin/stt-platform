package ru.sttplatform.gateway.session;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class FinalAnswerAssemblerTest {
    @Test void assigns_candidate_words_once_with_seeded_times_and_flags_uncertainty() {
        var random = new Random(42);
        long offset = 1000 + random.nextInt(1000);
        var intervals = List.of(new FinalAnswerAssembler.Interval("Q1", null, 1, 1, offset, offset + 100),
                new FinalAnswerAssembler.Interval("Q1", "Q1-F1", 2, 2, offset + 100, offset + 200));
        var words = List.of(new FinalAnswerAssembler.Word("первый", offset + 10, offset + 20, "Кандидат", "system", "whisper"),
                new FinalAnswerAssembler.Word("граница", offset + 90, offset + 110, "Кандидат", "system", "whisper"),
                new FinalAnswerAssembler.Word("другой", offset + 110, offset + 120, "Интервьюер 1", "microphone", "whisper"),
                new FinalAnswerAssembler.Word("неизвестно", offset + 120, offset + 130, "Не определено", "system", "whisper"));
        var answers = new FinalAnswerAssembler().assemble(intervals, words, false);
        assertEquals("первый", answers.get(0).text());
        assertEquals("граница", answers.get(1).text());
        assertEquals(2, answers.get(1).warnings().size());
    }

    @Test void rejects_overlapping_intervals() {
        var intervals = List.of(new FinalAnswerAssembler.Interval("Q1", null, 1, 1, 0, 100),
                new FinalAnswerAssembler.Interval("Q2", null, 2, 2, 99, 200));
        assertThrows(IllegalArgumentException.class, () -> new FinalAnswerAssembler().assemble(intervals, List.of(), false));
    }
    @Test void reports_a_gap_that_overlaps_an_answer_even_if_its_midpoint_is_outside() {
        var interval = new FinalAnswerAssembler.Interval("Q1", null, 1, 1, 100, 200);
        var gap = new FinalAnswerAssembler.Word("", 150, 1000, "Не определено", "system", "unavailable");
        var answer = new FinalAnswerAssembler().assemble(List.of(interval), List.of(gap), false).get(0);
        assertTrue(answer.text().isEmpty());
        assertFalse(answer.warnings().isEmpty());
    }

}
