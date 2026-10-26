package ru.sttplatform.gateway.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrchestratorTest {
    @Test
    void rejects_preview_results_for_audio_already_covered_by_final_decision() {
        assertTrue(Orchestrator.isPreviewAtOrBeforeDecision(142_500, 144_429));
        assertTrue(Orchestrator.isPreviewAtOrBeforeDecision(144_429, 144_429));
        assertFalse(Orchestrator.isPreviewAtOrBeforeDecision(144_430, 144_429));
        assertFalse(Orchestrator.isPreviewAtOrBeforeDecision(10, 0));
    }
}
