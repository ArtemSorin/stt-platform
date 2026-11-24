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
import java.util.UUID;

/** Внутренний API реестра; доступен только по общему секрету сервисов. */
@RestController
@RequestMapping("/internal/archive")
@ConditionalOnProperty(name = "archive.database.url")
public class ArchiveController {
    private final CatalogStore store;
    private final byte[] token;
    private FinalizationJobStore finalizationJobs;

    /** Подключает очередь финализации.
     * @param jobs хранилище заданий */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setFinalizationJobs(FinalizationJobStore jobs) { this.finalizationJobs = jobs; }

    /** @param store реестр
     * @param token внутренний секрет */
    public ArchiveController(CatalogStore store, @Value("${interview.events.token:}") String token) {
        if (token.length() < 32) throw new IllegalStateException("Не задан внутренний секрет длиной не менее 32 символов");
        this.store = store;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    private void authenticate(String presented) {
        if (!MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    /** @param token секрет
     * @param id идентификатор
     * @return карточка интервью
     * @throws SQLException ошибка базы */
    @GetMapping("/interviews/{id}")
    public ResponseEntity<CatalogStore.Interview> find(@RequestHeader("X-Internal-Token") String token, @PathVariable UUID id) throws SQLException {
        authenticate(token);
        var interview = store.find(id);
        return interview == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(interview);
    }

    /** @param token секрет
     * @param id идентификатор
     * @param artifact сведения об объекте
     * @throws SQLException ошибка базы */
    @PutMapping("/interviews/{id}/artifacts")
    public void register(@RequestHeader("X-Internal-Token") String token, @PathVariable UUID id,
                         @RequestBody CatalogStore.Artifact artifact) throws SQLException {
        authenticate(token); store.register(id, artifact);
    }

    /** @param token секрет
     * @param id идентификатор
     * @throws SQLException ошибка базы */
    @PutMapping("/interviews/{id}/finish")
    public void finish(@RequestHeader("X-Internal-Token") String token, @PathVariable UUID id) throws SQLException {
        authenticate(token); store.finish(id);
    }

    /** Ставит завершённое интервью в очередь после регистрации всех входов.
     * @param token внутренний секрет
     * @param id идентификатор интервью
     * @throws SQLException ошибка базы */
    @PutMapping("/interviews/{id}/finalization")
    public void enqueueFinalization(@RequestHeader("X-Internal-Token") String token, @PathVariable UUID id)
            throws SQLException {
        authenticate(token);
        if (finalizationJobs == null || !finalizationJobs.enqueueRegistered(id))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ожидается регистрация исходных файлов");
    }

    /** @param token секрет
     * @param id идентификатор интервью
     * @throws SQLException ошибка базы */
    @PutMapping("/interviews/{id}/bind")
    public void bind(@RequestHeader("X-Internal-Token") String token, @PathVariable UUID id) throws SQLException {
        authenticate(token); store.bind(id);
    }

    /** Пара исходных TSV.
     * @param questions вопросы
     * @param followups уточнения */
    public record Import(String questions, String followups) {}

    /** @param token секрет
     * @param input данные каталога
     * @return номер опубликованной версии
     * @throws SQLException ошибка базы */
    @PostMapping("/catalog")
    public long publish(@RequestHeader("X-Internal-Token") String token, @RequestBody Import input) throws SQLException {
        authenticate(token); return store.publish(input.questions(), input.followups(), false);
    }
}
