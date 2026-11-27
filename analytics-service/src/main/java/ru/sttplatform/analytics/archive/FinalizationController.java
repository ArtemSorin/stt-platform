package ru.sttplatform.analytics.archive;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

/** Внутренний протокол выдачи и подтверждения заданий фоновой финализации. */
@RestController
@RequestMapping("/internal/finalization")
@ConditionalOnProperty(name = "finalization.worker-enabled", havingValue = "true")
public final class FinalizationController {
    private final FinalizationJobStore jobs;
    private final byte[] token;

    /** @param jobs постоянная очередь
     * @param token общий секрет сервисов */
    public FinalizationController(FinalizationJobStore jobs, @Value("${interview.events.token:}") String token) {
        if (token.length() < 32) throw new IllegalStateException("Не задан внутренний секрет длиной не менее 32 символов");
        this.jobs = jobs;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    private void authenticate(String presented) {
        if (!MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    /** Запрос на выдачу или продление аренды.
     * @param seconds длительность в секундах */
    public record DurationRequest(long seconds) {}

    /** Подтверждение действия конкретной попытки.
     * @param leaseToken токен аренды */
    public record LeaseRequest(UUID leaseToken) {}

    /** Манифест подготовленных файлов.
     * @param leaseToken токен аренды
     * @param manifest JSON-манифест результата */
    public record ResultRequest(UUID leaseToken, com.fasterxml.jackson.databind.JsonNode manifest) {}

    /** Описание временного или постоянного отказа.
     * @param leaseToken токен аренды
     * @param delaySeconds задержка повтора либо null для окончательной ошибки
     * @param errorCode безопасный код ошибки */
    public record FailureRequest(UUID leaseToken, Long delaySeconds, String errorCode) {}

    private static Duration duration(long seconds) {
        if (seconds < 1 || seconds > 3600)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Аренда должна быть от 1 до 3600 секунд");
        return Duration.ofSeconds(seconds);
    }

    private static UUID requireToken(UUID token) {
        if (token == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Отсутствует токен аренды");
        return token;
    }

    private static void requireLease(boolean updated) {
        if (!updated) throw new ResponseStatusException(HttpStatus.CONFLICT, "Аренда истекла или состояние изменилось");
    }

    /** Выдаёт готовое к обработке задание.
     * @param token внутренний секрет
     * @param request длительность аренды
     * @return задание или HTTP 204 при пустой очереди
     * @throws SQLException ошибка базы */
    @PostMapping("/claim")
    public ResponseEntity<FinalizationJobStore.Lease> claim(@RequestHeader("X-Internal-Token") String token,
                                                            @RequestBody DurationRequest request) throws SQLException {
        authenticate(token);
        var lease = jobs.claim(duration(request.seconds()));
        return lease == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(lease);
    }

    /** Проверяет состояние после неясного ответа на подтверждение готовности.
     * @param token внутренний секрет
     * @param id ID интервью
     * @return текущее состояние задания
     * @throws SQLException ошибка базы */
    @GetMapping("/{id}/status")
    public ResponseEntity<FinalizationJobStore.Status> status(@RequestHeader("X-Internal-Token") String token,
                                                               @PathVariable UUID id) throws SQLException {
        authenticate(token);
        var status = jobs.status(id);
        return status == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(status);
    }

    /** Продлевает аренду текущей попытки.
     * @param token внутренний секрет
     * @param id ID интервью
     * @param leaseToken токен аренды
     * @param request длительность аренды
     * @throws SQLException ошибка базы */
    @PostMapping("/{id}/renew")
    public void renew(@RequestHeader("X-Internal-Token") String token, @PathVariable UUID id,
                      @RequestHeader("X-Lease-Token") UUID leaseToken, @RequestBody DurationRequest request)
            throws SQLException {
        authenticate(token);
        requireLease(jobs.renew(id, requireToken(leaseToken), duration(request.seconds())));
    }

    /** Закрепляет неизменяемый манифест результата.
     * @param token внутренний секрет
     * @param id ID интервью
     * @param request токен попытки и манифест
     * @throws SQLException ошибка базы */
    @PutMapping("/{id}/result")
    public void result(@RequestHeader("X-Internal-Token") String token, @PathVariable UUID id,
                       @RequestBody ResultRequest request) throws SQLException {
        authenticate(token);
        if (request.manifest() == null || !request.manifest().isObject())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Нужен объект манифеста");
        var manifest = request.manifest();
        var artifacts = manifest.path("artifacts");
        if (!id.toString().equals(manifest.path("interviewId").asText())
                || !java.util.Set.of("whisper", "mixed", "vosk", "vosk_fallback").contains(manifest.path("transcriptSource").asText())
                || !artifacts.isArray() || artifacts.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный манифест результата");
        var kinds = new java.util.HashSet<String>();
        for (var artifact : artifacts) {
            String kind = artifact.path("kind").asText();
            String key = artifact.path("stagingKey").asText();
            if (!kind.matches("[a-z][a-z0-9_]{0,63}") || !kinds.add(kind)
                    || !key.startsWith("staging/" + id + "/") || key.contains("..")
                    || !artifact.path("sha256").asText().matches("[0-9a-f]{64}")
                    || !artifact.path("sizeBytes").isIntegralNumber() || artifact.path("sizeBytes").asLong() < 0
                    || (kind.equals("report_xlsx") && artifact.path("sizeBytes").asLong() == 0))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный артефакт результата");
        }
        if (!kinds.contains("report_xlsx"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "В манифесте отсутствует отчёт");
        requireLease(jobs.saveResult(id, requireToken(request.leaseToken()), request.manifest().toString()));
    }

    /** Передаёт результат неудачной попытки.
     * @param token внутренний секрет
     * @param id ID интервью
     * @param request причина и параметры повтора
     * @throws SQLException ошибка базы */
    @PostMapping("/{id}/failure")
    public void failure(@RequestHeader("X-Internal-Token") String token, @PathVariable UUID id,
                        @RequestBody FailureRequest request) throws SQLException {
        authenticate(token);
        if (request.delaySeconds() != null && (request.delaySeconds() < 0 || request.delaySeconds() > 86400))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректная задержка повтора");
        if (request.errorCode() == null || !request.errorCode().matches("[a-z][a-z0-9_]{0,63}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный код ошибки");
        Duration delay = request.delaySeconds() == null ? null : Duration.ofSeconds(request.delaySeconds());
        requireLease(jobs.fail(id, requireToken(request.leaseToken()), delay, request.errorCode()));
    }

    /** Подтверждает готовность зарегистрированного отчёта.
     * @param token внутренний секрет
     * @param id ID интервью
     * @param request токен попытки
     * @throws SQLException ошибка базы */
    @PostMapping("/{id}/complete")
    public void complete(@RequestHeader("X-Internal-Token") String token, @PathVariable UUID id,
                         @RequestBody LeaseRequest request) throws SQLException {
        authenticate(token);
        requireLease(jobs.complete(id, requireToken(request.leaseToken())));
    }
}
