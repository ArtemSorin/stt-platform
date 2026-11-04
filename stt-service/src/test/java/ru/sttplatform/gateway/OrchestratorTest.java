package ru.sttplatform.gateway.session;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;

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
    void quiet_leading_frame_waits_when_next_frame_has_unconfirmed_voice() {
        byte[] quiet = { (byte) 185, 0, (byte) 185, 0 };
        byte[] speech = {0x10, 0x27, 0x10, 0x27};
        assertFalse(Orchestrator.awaitsVoiceRole(10, quiet, List.of()));
        assertTrue(Orchestrator.awaitsRoleWithLookahead(10, quiet, speech, List.of()));
        assertFalse(Orchestrator.awaitsRoleWithLookahead(10, quiet, speech,
                List.of(new Orchestrator.VoiceSpan(10, 14, "Кандидат", "1", false))));
        assertFalse(Orchestrator.awaitsRoleWithLookahead(10, quiet, null, List.of()));
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

    @Test
    void test_mode_does_not_send_words_outside_an_enabled_answer_to_preview() {
        var orchestrator = mock(Orchestrator.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(orchestrator, "testCandidateEnabledRanges",
                List.of(new Orchestrator.SampleRange(16_000, 32_000)));
        assertFalse(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(orchestrator, "testWordEnabled", Map.of("start", .2, "end", .4))));
        assertTrue(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(orchestrator, "testWordEnabled", Map.of("start", 1.2, "end", 1.4))));
        assertFalse(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(orchestrator, "testWordEnabled", Map.of("start", 2.2, "end", 2.4))));
    }

    @Test
    void live_whisper_accepts_only_confirmed_candidate_words() {
        var orchestrator = mock(Orchestrator.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(orchestrator, "spans", List.of(
                new Orchestrator.VoiceSpan(0, 16_000, "Кандидат", "candidate", false),
                new Orchestrator.VoiceSpan(16_000, 32_000, "Интервьюер 2", "other", false),
                new Orchestrator.VoiceSpan(32_000, 48_000, "Кандидат", "candidate", true)));
        assertTrue(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(orchestrator, "confirmedCandidateWord", 100L, 1000L)));
        assertFalse(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(orchestrator, "confirmedCandidateWord", 17_000L, 18_000L)));
        assertFalse(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(orchestrator, "confirmedCandidateWord", 33_000L, 34_000L)));
    }
}
