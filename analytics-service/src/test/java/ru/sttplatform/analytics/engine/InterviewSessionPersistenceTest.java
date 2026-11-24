package ru.sttplatform.analytics.engine;

import org.junit.jupiter.api.Test;
import ru.sttplatform.common.json.Json;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InterviewSessionPersistenceTest {
    private InterviewSession session() {
        return new InterviewSession(TestKb.get(), List.of(Level.INTERN), new DecisionPolicy(2, 0.08));
    }

    @Test
    void restoresAcceptedFollowupAndAnswerReferencesWithoutRepeatingDecisions() throws Exception {
        InterviewSession original = session();
        var first = original.start();
        var step = original.submit("джава виртуальная машина она запускает программы а джи ди кей это для разработки там компилятор",
                new AnswerTiming(900, 20000));
        assertEquals(InterviewSession.Action.ASK_FOLLOW_UP, step.action());
        var primaryRef = original.saveAttemptRef(original.lastAttempt());
        String followUpId = step.next().followUpId();
        original.submit("не знаю не помню", new AnswerTiming(400, 1200));
        var followUpRef = original.saveAttemptRef(original.lastAttempt());

        String json = Json.MAPPER.writeValueAsString(original.saveState());
        var saved = Json.MAPPER.readValue(json, InterviewSession.SavedState.class);
        InterviewSession restored = session();
        restored.restoreState(saved);

        assertEquals(saved, restored.saveState());
        assertTrue(restored.matchesAnswer(restored.restoreAttemptRef(primaryRef), first.stableQuestionId(), null));
        assertTrue(restored.matchesAnswer(restored.restoreAttemptRef(followUpRef), first.stableQuestionId(), followUpId));
        restored.replaceFinalAnswer(restored.restoreAttemptRef(followUpRef), "новый ответ", "новый ответ", true);
        var turns = restored.judgeInput(restored.restoreAttemptRef(followUpRef)).turns();
        assertEquals("новый ответ", turns.get(turns.size() - 1).answer());
    }

    @Test
    void rejectsInconsistentSnapshotWithoutChangingExistingSession() {
        InterviewSession original = session();
        original.start();
        var before = original.saveState();
        var first = before.runs().get(0);
        var wrongRun = new InterviewSession.SavedRun("wrong-id", first.attempts(), first.followUpAsked(),
                first.followUpsUsed(), first.lastCoverage());
        var corrupt = new InterviewSession.SavedState(List.of(wrongRun), before.position(),
                before.currentRun(), null, false);

        assertThrows(IllegalArgumentException.class, () -> original.restoreState(corrupt));
        assertEquals(before, original.saveState());
        assertThrows(IllegalArgumentException.class,
                () -> original.restoreAttemptRef(new InterviewSession.SavedAttemptRef(0, 99)));
    }
}
