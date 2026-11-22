package ru.sttplatform.gateway.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.sttplatform.common.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class InterviewEventJournalTest {
    @Test void persists_ordered_snapshots_and_prevents_overwrite(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("events.jsonl");
        var fields = new java.util.HashMap<String, Object>();
        fields.put("questionId", "Q1");
        try (var journal = new InterviewEventJournal(path, "session", 16)) {
            journal.append("ready", 100, fields);
            fields.put("questionId", "Q2");
            journal.append("decision", 220, Map.of("answerId", 1));
        }
        var lines = Files.readAllLines(path);
        assertEquals(2, lines.size());
        var first = Json.MAPPER.readTree(lines.get(0));
        var second = Json.MAPPER.readTree(lines.get(1));
        assertEquals("Q1", first.path("questionId").asText());
        assertEquals("session", first.path("sessionId").asText());
        assertEquals(0, first.path("eventSequence").asInt());
        assertEquals(1, second.path("eventSequence").asInt());
        assertEquals(220, second.path("audioSample").asLong());
        assertThrows(java.io.IOException.class, () -> new InterviewEventJournal(path, "session", 16));
    }
    @Test void rejects_oversized_events_without_consuming_sequence(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("events.jsonl");
        try (var journal = new InterviewEventJournal(path, "session", 16)) {
            assertThrows(java.io.IOException.class,
                    () -> journal.append("ready", 0, Map.of("text", "я".repeat(20_000))));
            journal.append("ready", 1, Map.of());
        }
        assertEquals(0, Json.MAPPER.readTree(Files.readString(path)).path("eventSequence").asInt());
    }

}
