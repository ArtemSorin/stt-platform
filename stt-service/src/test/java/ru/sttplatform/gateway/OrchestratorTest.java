package ru.sttplatform.gateway.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrchestratorTest {
    @Test
    void overlapping_late_role_is_not_reported_lost_when_audio_was_already_sent() {
        var sent = List.of(new Orchestrator.SampleRange(100, 1000));
        assertFalse(Orchestrator.hasUnsentCandidateAudio(500, 1030, sent));
        assertTrue(Orchestrator.hasUnsentCandidateAudio(500, 1100, sent));
        assertTrue(Orchestrator.hasUnsentCandidateAudio(0, 500, sent));
        assertFalse(Orchestrator.hasUnsentCandidateAudio(1000, 1000, sent));
    }

    @Test
    void audible_unconfirmed_audio_waits_for_a_real_role() {
        byte[] speech = {0x10, 0x27, 0x10, 0x27};
        assertTrue(Orchestrator.awaitsVoiceRole(10, speech, List.of()));
        assertTrue(Orchestrator.awaitsVoiceRole(10, speech,
                List.of(new Orchestrator.VoiceSpan(10, 12, "Кандидат", "1", true))));
        assertFalse(Orchestrator.awaitsVoiceRole(10, speech,
                List.of(new Orchestrator.VoiceSpan(10, 12, "Кандидат", "1", false))));
        assertFalse(Orchestrator.awaitsVoiceRole(10, new byte[4], List.of()));
    }

    @Test
    void separate_candidate_audio_survives_an_overlapping_interviewer_span() {
        byte[] system = {1, 1, 2, 2, 3, 3, 4, 4};
        var spans = List.of(new Orchestrator.VoiceSpan(10, 14, "Кандидат", "candidate", false),
                new Orchestrator.VoiceSpan(11, 13, "Интервьюер 1", "other", false));
        assertArrayEquals(system, Orchestrator.gateCandidateAudio(10, system, spans, 10, true));
        assertArrayEquals(new byte[]{1, 1, 0, 0, 0, 0, 4, 4},
                Orchestrator.gateCandidateAudio(10, system, spans, 10, false));
    }

    @Test
    void candidate_gate_excludes_unconfirmed_and_pre_answer_audio() {
        byte[] system = {1, 1, 2, 2, 3, 3, 4, 4};
        var spans = List.of(new Orchestrator.VoiceSpan(10, 12, "Кандидат", "candidate", false),
                new Orchestrator.VoiceSpan(12, 14, "Кандидат", "candidate", true));
        assertArrayEquals(new byte[]{0, 0, 2, 2, 0, 0, 0, 0},
                Orchestrator.gateCandidateAudio(10, system, spans, 11, true));
    }

    @Test
    void rejects_preview_results_for_audio_already_covered_by_final_decision() {
        assertTrue(Orchestrator.isPreviewAtOrBeforeDecision(142_500, 144_429));
        assertTrue(Orchestrator.isPreviewAtOrBeforeDecision(144_429, 144_429));
        assertFalse(Orchestrator.isPreviewAtOrBeforeDecision(144_430, 144_429));
        assertFalse(Orchestrator.isPreviewAtOrBeforeDecision(10, 0));
    }
}
