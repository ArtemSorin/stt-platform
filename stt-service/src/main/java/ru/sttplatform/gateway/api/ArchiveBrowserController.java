package ru.sttplatform.gateway.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ru.sttplatform.common.archive.ArchiveQueue;
import ru.sttplatform.common.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

/** Локальный интерфейс зарегистрированных результатов интервью. */
@RestController
@RequestMapping("/api/interviews")
@ConditionalOnProperty(name = "archive.enabled", havingValue = "true")
public class ArchiveBrowserController {
    private static final Map<String, String> TYPES = Map.of(
            "audio_wav", "audio/wav", "audio_mono_wav", "audio/wav", "report_xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "transcript_md", "text/markdown", "transcript_timed_md", "text/markdown", "transcripts_zip", "application/zip");
    private static final Map<String, String> NAMES = Map.of(
            "audio_wav", "interview.wav", "audio_mono_wav", "interview-mono.wav", "report_xlsx", "report.xlsx",
            "transcript_md", "transcript.md", "transcript_timed_md", "transcript-timed.md", "transcripts_zip", "transcripts.zip");
    private final ArchiveQueue archive;

    /** @param archive очередь и клиент реестра */
    public ArchiveBrowserController(ArchiveQueue archive) { this.archive = archive; }

    /** @param id идентификатор интервью
     * @return состояния и версия банка
     * @throws IOException ошибка реестра
     * @throws InterruptedException прерванный запрос */
    @GetMapping("/{id}")
    public ResponseEntity<ObjectNode> find(@PathVariable UUID id) throws IOException, InterruptedException {
        var interview = archive.find(id);
        if (interview == null) return ResponseEntity.notFound().build();
        boolean audio = false, report = false;
        var kinds = Json.MAPPER.createArrayNode();
        for (var artifact : interview.path("artifacts")) {
            String kind = artifact.path("kind").asText();
            if (TYPES.containsKey(kind)) kinds.add(kind);
            audio |= kind.equals("audio_wav") || kind.equals("audio_mono_wav");
            report |= kind.equals("report_xlsx");
        }
        ObjectNode result = Json.MAPPER.createObjectNode();
        result.put("id", id.toString()); result.put("catalogVersion", interview.path("catalogVersion").asLong());
        result.put("callStatus", interview.path("callStatus").asText());
        result.put("audioStatus", audio ? "saved" : archive.hasConflict(id, "audio_wav") || archive.hasConflict(id, "audio_mono_wav") ? "error" : "pending");
        result.put("reportStatus", report ? "ready" : "pending");
        result.set("artifacts", kinds);
        return ResponseEntity.ok(result);
    }

    /** @param id идентификатор интервью
     * @param kind разрешённый тип файла
     * @return поток файла
     * @throws IOException ошибка реестра
     * @throws InterruptedException прерванный запрос */
    @GetMapping("/{id}/artifacts/{kind}")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable UUID id, @PathVariable String kind) throws IOException, InterruptedException {
        if (!TYPES.containsKey(kind)) return ResponseEntity.notFound().build();
        InputStream stream = archive.open(id, kind);
        if (stream == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(TYPES.get(kind)))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + NAMES.get(kind) + "\"")
                .body(output -> { try (stream) { stream.transferTo(output); } });
    }
}
