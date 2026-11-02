package ru.sttplatform.stt.transcript;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.sttplatform.gateway.config.GatewayProperties;
import ru.sttplatform.gateway.session.SessionManager;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class TranscriptStoreTest {
    @TempDir Path root;

    private Map<String, Object> word(double start, double end, String role, String text, String status) {
        return Map.of("start", start, "end", end, "role", role, "word", text,
                "status", status, "source", "system", "word_id", text);
    }

    @Test void publishesOnlyFinalWordsWithRolesTimingAndZipAndSurvivesRestart() throws Exception {
        var store = new TranscriptStore(new GatewayProperties(null, null, root.toString()));
        String id = UUID.randomUUID().toString();
        Path session = Files.createDirectory(root.resolve("run"));
        assertNull(store.find(id, "transcript.md"));
        store.publish(session, id, List.of(
                word(4, 4.5, "Кандидат", "ответ", "final"),
                word(0, 1, "Интервьюер 2", "привет", "final"),
                word(1, 1.1, "Интервьюер 2", "!", "final"),
                word(2, 3, "Не определено", "слово", "final"),
                word(4, 4.5, "Кандидат", "устаревший", "replaced_by_whisper"),
                word(5, 5.1, "Кандидат", "частично", "preliminary"),
                word(7, 7.5, "Кандидат", "ещё", "final")));
        var restored = new TranscriptStore(new GatewayProperties(null, null, root.toString()));
        String plain = Files.readString(restored.find(id, "transcript.md"));
        assertEquals("Интервьюер 2: привет!\nНе определено: слово\nКандидат: ответ\nКандидат: ещё\n", plain);
        String timed = Files.readString(restored.find(id, "transcript-timed.md"));
        assertTrue(timed.contains("[00:00:00.000–00:00:01.100] Интервьюер 2: привет!"));
        assertNull(restored.find(UUID.randomUUID().toString(), "transcript.md"));
        assertNull(restored.find(id, "../ready.json"));
        try (var zip = new ZipInputStream(Files.newInputStream(restored.find(id, "transcripts.zip")), StandardCharsets.UTF_8)) {
            assertEquals("transcript.md", zip.getNextEntry().getName());
            assertEquals(plain, new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("transcript-timed.md", zip.getNextEntry().getName());
            assertEquals(timed, new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            assertNull(zip.getNextEntry());
        }
        store.publish(session, id, List.of());
        assertEquals(plain, Files.readString(restored.find(id, "transcript.md")));
    }

    @Test void invalidWordDoesNotPublishMarker() throws Exception {
        var store = new TranscriptStore(new GatewayProperties(null, null, root.toString()));
        String id = UUID.randomUUID().toString();
        Path session = Files.createDirectory(root.resolve("run"));
        assertThrows(java.io.IOException.class, () -> store.publish(session, id,
                List.of(word(2, 1, "Кандидат", "ошибка", "final"))));
        assertNull(store.find(id, "transcripts.zip"));
    }

    @Test void endpointIsUnavailableUntilSessionFinishes() throws Exception {
        var store = new TranscriptStore(new GatewayProperties(null, null, root.toString()));
        String id = UUID.randomUUID().toString();
        Path session = Files.createDirectory(root.resolve("run"));
        store.publish(session, id, List.of());
        SessionManager sessions = mock(SessionManager.class);
        var controller = new TranscriptController(store, sessions);
        assertEquals(404, controller.get(id, "transcripts.zip").getStatusCode().value());
        when(sessions.transcriptAccessible(id)).thenReturn(true);
        assertEquals(200, controller.get(id, "transcripts.zip").getStatusCode().value());
        assertEquals(200, controller.get(id, "transcript-timed.md").getStatusCode().value());
        assertEquals(404, controller.get(id, "../ready.json").getStatusCode().value());
    }

    @Test void emptyInterviewProducesTwoEmptyFiles() throws Exception {
        var store = new TranscriptStore(new GatewayProperties(null, null, root.toString()));
        String id = UUID.randomUUID().toString();
        Path session = Files.createDirectory(root.resolve("run"));
        store.publish(session, id, List.of());
        assertEquals("", Files.readString(store.find(id, "transcript.md")));
    }
}
