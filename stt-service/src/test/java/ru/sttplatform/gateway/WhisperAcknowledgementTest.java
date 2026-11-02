package ru.sttplatform.gateway.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import ru.sttplatform.common.json.Json;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WhisperAcknowledgementTest {
    @TempDir Path directory;

    @Test void acknowledgementUsesCachedWordsAndRetractionRestoresVosk() throws Exception {
        Orchestrator orchestrator = mock(Orchestrator.class, CALLS_REAL_METHODS);
        var rows = new ArrayList<Map<String, Object>>();
        rows.add(new LinkedHashMap<>(Map.of("word", "прежний", "start", 1.0, "end", 1.5,
                "role", "Кандидат", "source", "system", "model", "vosk", "status", "final", "word_id", "a")));
        rows.add(new LinkedHashMap<>(Map.of("word", "вопрос", "start", 1.0, "end", 1.5,
                "role", "Интервьюер 1", "source", "microphone", "model", "vosk", "status", "final", "word_id", "b")));
        set(orchestrator, "sessionId", "session");
        set(orchestrator, "directory", directory);
        set(orchestrator, "timelineLog", new BufferedWriter(new StringWriter()));
        set(orchestrator, "expectedAnalyticsSequence", 1L);
        set(orchestrator, "finalWords", rows);
        set(orchestrator, "preliminaryWords", new HashMap<>());
        set(orchestrator, "decisionTimes", new HashMap<>());
        set(orchestrator, "whisperLatencies", new ArrayList<>());
        set(orchestrator, "answerBases", new HashMap<>(Map.of(3, 16000L)));
        set(orchestrator, "decisionAudioEnds", new HashMap<>(Map.of(3, 32000L)));
        set(orchestrator, "cancelledWhisperAnswers", new HashSet<>());
        set(orchestrator, "acceptedWhisperRanges", new HashMap<>());
        set(orchestrator, "whisperCompletion", new ConcurrentHashMap<>());
        set(orchestrator, "pendingWhisperWords", new ConcurrentHashMap<>(Map.of(3,
                Json.tree("[{\"word\":\"новый\",\"start\":0.1,\"end\":0.5}]"))));
        set(orchestrator, "lastWhisperAnswerId", 3);
        ReflectionTestUtils.invokeMethod(orchestrator, "onInterview",
                Json.tree("{\"type\":\"refined\",\"sessionId\":\"session\",\"sequence\":1,\"answerId\":3,\"text\":\"новый\"}"));
        assertEquals("replaced_by_whisper", rows.get(0).get("status"));
        assertEquals("final", rows.get(1).get("status"));
        assertEquals("новый", rows.get(2).get("word"));
        assertEquals(1.1, rows.get(2).get("start"));
        ReflectionTestUtils.invokeMethod(orchestrator, "onInterview",
                Json.tree("{\"type\":\"retract\",\"sessionId\":\"session\",\"sequence\":2}"));
        assertEquals(2, rows.size());
        assertEquals("final", rows.get(0).get("status"));
    }

    private static void set(Object target, String name, Object value) {
        ReflectionTestUtils.setField(target, name, value);
    }
}
