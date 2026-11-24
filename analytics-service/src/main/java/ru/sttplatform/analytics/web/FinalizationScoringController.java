package ru.sttplatform.analytics.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ru.sttplatform.analytics.archive.CatalogStore;
import ru.sttplatform.analytics.archive.FinalizationJobStore;
import ru.sttplatform.analytics.engine.*;
import ru.sttplatform.analytics.export.InterviewExcelReport;
import ru.sttplatform.analytics.judge.FridaJudge;
import ru.sttplatform.analytics.judge.JudgeProperties;
import ru.sttplatform.analytics.live.EventSpeechEngine;
import ru.sttplatform.analytics.live.LiveInterview;
import ru.sttplatform.analytics.live.ReportExporter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Восстанавливает аналитику из сохранённого снимка для действующей попытки обработчика. */
@RestController
@RequestMapping("/internal/finalization")
@ConditionalOnProperty(name = "finalization.worker-enabled", havingValue = "true")
public final class FinalizationScoringController {
    private final FinalizationJobStore jobs;
    private final CatalogStore catalog;
    private final InterviewProperties properties;
    private final DecisionPolicy policy;
    private final FridaJudge judge;
    private final JudgeProperties judgeProperties;
    private final ObjectMapper mapper;
    private final byte[] token;

    public FinalizationScoringController(FinalizationJobStore jobs, CatalogStore catalog,
                                         InterviewProperties properties, DecisionPolicy policy,
                                         FridaJudge judge, JudgeProperties judgeProperties, ObjectMapper mapper,
                                         @Value("${interview.events.token:}") String token) {
        if (token.length() < 32) throw new IllegalStateException("Не задан внутренний секрет");
        this.jobs = jobs; this.catalog = catalog; this.properties = properties; this.policy = policy;
        this.judge = judge; this.judgeProperties = judgeProperties; this.mapper = mapper;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    /** Итоговый текст одной принятой попытки. */
    public record Answer(int answerId, String questionId, String followUpId, String text,
                         String source, boolean transcriptIncomplete) {}
    /** Ответы и источник транскрипции. */
    public record Request(String transcriptSource, List<Answer> answers) {}

    /** Строит XLSX по закреплённой версии банка; публикацию выполняет обработчик.
     * @param presented внутренний секрет
     * @param id интервью
     * @param leaseToken токен попытки
     * @param request итоговые ответы
     * @return байты временного результата
     * @throws Exception ошибка восстановления или экспорта */
    @PostMapping(value = "/{id}/score", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> score(@RequestHeader("X-Internal-Token") String presented,
                                        @PathVariable UUID id, @RequestHeader("X-Lease-Token") UUID leaseToken,
                                        @RequestBody Request request) throws Exception {
        if (!MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var lease = jobs.current(id, leaseToken);
        if (lease == null || lease.resultManifest() != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Аренда истекла или результат уже закреплён");
        var saved = mapper.readTree(lease.snapshot());
        var registered = catalog.find(id);
        if (saved.path("schemaVersion").asInt() != 1 || !saved.path("levels").isArray()
                || !id.toString().equals(saved.path("interviewId").asText()) || registered == null
                || saved.path("catalogVersion").asLong(-1) != registered.catalogVersion())
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Неизвестная версия снимка");
        if (request.answers() == null || request.answers().size() > 10000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неверный список ответов");
        List<Level> levels = new java.util.ArrayList<>();
        saved.path("levels").forEach(level -> levels.add(Level.parse(level.asText())));
        var knowledge = InterviewConfig.catalogKnowledge(properties, catalog.bind(id));
        var speech = new EventSpeechEngine();
        AtomicReference<byte[]> xlsx = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        var live = new LiveInterview(speech, new InterviewSession(knowledge, levels, policy),
                new EndpointDetector(new EndpointDetector.Config()), event -> {
                    if (event.containsKey("excelError")) error.set(String.valueOf(event.get("excelError")));
                });
        live.setJudge(judgeProperties.enabled() ? judge : null, judgeProperties.decisionDeadlineMs());
        live.setRefinementWait(properties.whisperWaitMs());
        live.setExport(details -> {
            xlsx.set(InterviewExcelReport.build(details));
            return new ReportExporter.Result("report.xlsx", "", null);
        }, "", Map.of());
        try {
            live.restoreFinalizationSnapshot(mapper.treeToValue(saved.path("snapshot"), LiveInterview.FinalizationSnapshot.class));
            live.onFinalSource(request.transcriptSource());
            for (var answer : request.answers()) {
                if (answer == null) throw new IllegalArgumentException("Пустой ответ");
                live.onFinalAnswer(answer.answerId(), answer.questionId(), answer.followUpId(), answer.text(),
                        answer.source(), answer.transcriptIncomplete());
            }
            live.onStop();
            if (error.get() != null || xlsx.get() == null)
                throw new IllegalStateException("Ошибка формирования XLSX: " + error.get());
            var current = jobs.current(id, leaseToken);
            if (current == null || current.resultManifest() != null)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Аренда истекла во время оценки");
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(xlsx.get());
        } finally {
            live.close();
            speech.close();
        }
    }
}
