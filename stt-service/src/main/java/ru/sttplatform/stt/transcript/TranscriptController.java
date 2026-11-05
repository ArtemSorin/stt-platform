package ru.sttplatform.stt.transcript;

import org.springframework.http.HttpHeaders;
import ru.sttplatform.gateway.session.SessionManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import java.io.IOException;
import java.nio.file.Files;

/** Доступ к готовым транскриптам по UUID сессии. */
@RestController
public class TranscriptController {
    private final TranscriptStore store;
    private final SessionManager sessions;
    /** @param store хранилище транскриптов
     * @param sessions менеджер активной сессии
     */
    public TranscriptController(TranscriptStore store, SessionManager sessions) { this.store = store; this.sessions = sessions; }

    /** @param id UUID интервью
     * @param name фиксированное имя результата
     * @return содержимое файла или 404
     * @throws IOException при ошибке чтения
     */
    @GetMapping("/api/interviews/{id}/transcripts/{name}")
    public ResponseEntity<byte[]> get(@PathVariable String id, @PathVariable String name) throws IOException {
        if (!sessions.transcriptAccessible(id)) return ResponseEntity.notFound().build();
        var path = store.find(id, name);
        if (path == null) return ResponseEntity.notFound().build();
        boolean zip = name.equals("transcripts.zip");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, zip ? "application/zip" : "text/markdown; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, (zip ? "attachment" : "inline") + "; filename=\"" + name + "\"")
                .body(Files.readAllBytes(path));
    }
}
