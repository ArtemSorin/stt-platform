package ru.sttplatform.analytics.archive;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Долговечная очередь финализации. Аренда ограничивает право изменять результат одной попыткой. */
@Component
@ConditionalOnProperty(name = "archive.database.url")
public final class FinalizationJobStore {
    private final String url;
    private final String user;
    private final String password;

    /** @param url JDBC-адрес PostgreSQL
     * @param user пользователь базы
     * @param password пароль базы */
    public FinalizationJobStore(@Value("${archive.database.url}") String url,
                                @Value("${archive.database.user}") String user,
                                @Value("${archive.database.password}") String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /** Задание с входными данными и действующим токеном аренды.
     * @param interviewId ID интервью
     * @param snapshot снимок аналитики в JSON
     * @param inputManifest манифест исходников в JSON
     * @param resultManifest сохранённый манифест результата либо null
     * @param leaseToken токен текущей попытки
     * @param leaseUntil срок аренды
     * @param attemptCount номер попытки */
    public record Lease(UUID interviewId, String snapshot, String inputManifest, String resultManifest,
                        UUID leaseToken, Instant leaseUntil, int attemptCount) {}
    /** Состояние задания при восстановлении после потери HTTP-подтверждения.
     * @param state текущее состояние
     * @param resultManifest закреплённый манифест результата */
    public record Status(String state, String resultManifest) {}

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /** Сохраняет неизменяемый снимок до подтверждения завершения живого этапа.
     * @param id ID интервью
     * @param snapshot снимок в JSON
     * @throws SQLException при ошибке базы или конфликте данных */
    public void saveSnapshot(UUID id, String snapshot) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (var binding = connection.prepareStatement("""
                        SELECT 1 FROM archive_interview WHERE id = ?
                          AND CAST(? AS jsonb)->>'schemaVersion' = '1'
                          AND CAST(? AS jsonb)->>'interviewId' = id::text
                          AND CAST(? AS jsonb)->>'catalogVersion' = catalog_version_id::text
                        """)) {
                    binding.setObject(1, id);
                    binding.setString(2, snapshot); binding.setString(3, snapshot); binding.setString(4, snapshot);
                    try (var rows = binding.executeQuery()) {
                        if (!rows.next()) throw new SQLException("Снимок не соответствует интервью или версии банка");
                    }
                }
                try (var insert = connection.prepareStatement("""
                        INSERT INTO finalization_snapshot(interview_id, schema_version, payload)
                        VALUES (?, 1, CAST(? AS jsonb)) ON CONFLICT (interview_id) DO NOTHING
                        """)) {
                    insert.setObject(1, id);
                    insert.setString(2, snapshot);
                    insert.executeUpdate();
                }
                try (var check = connection.prepareStatement("""
                        SELECT schema_version = 1 AND payload = CAST(? AS jsonb)
                        FROM finalization_snapshot WHERE interview_id = ?
                        """)) {
                    check.setString(1, snapshot);
                    check.setObject(2, id);
                    try (var rows = check.executeQuery()) {
                        if (!rows.next() || !rows.getBoolean(1))
                            throw new SQLException("Конфликт снимка интервью");
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    /** Создаёт задание или подтверждает повторную постановку с теми же входными данными.
     * @param id ID интервью
     * @param snapshot снимок в JSON
     * @param manifest манифест в JSON
     * @throws SQLException при ошибке базы или конфликте входов */
    public void enqueue(UUID id, String snapshot, String manifest) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (var snapshotCheck = connection.prepareStatement("""
                        SELECT 1 FROM finalization_snapshot
                        WHERE interview_id = ? AND schema_version = 1 AND payload = CAST(? AS jsonb)
                        """)) {
                    snapshotCheck.setObject(1, id);
                    snapshotCheck.setString(2, snapshot);
                    try (var rows = snapshotCheck.executeQuery()) {
                        if (!rows.next()) throw new SQLException("Снимок интервью не зарегистрирован или не совпадает");
                    }
                }
                try (var insert = connection.prepareStatement("""
                        INSERT INTO finalization_job(interview_id, snapshot, input_manifest)
                        VALUES (?, CAST(? AS jsonb), CAST(? AS jsonb))
                        ON CONFLICT (interview_id) DO NOTHING
                        """)) {
                    insert.setObject(1, id);
                    insert.setString(2, snapshot);
                    insert.setString(3, manifest);
                    insert.executeUpdate();
                }
                try (var check = connection.prepareStatement("""
                        SELECT snapshot = CAST(? AS jsonb) AND input_manifest = CAST(? AS jsonb)
                        FROM finalization_job WHERE interview_id = ?
                        """)) {
                    check.setString(1, snapshot);
                    check.setString(2, manifest);
                    check.setObject(3, id);
                    try (ResultSet rows = check.executeQuery()) {
                        if (!rows.next() || !rows.getBoolean(1))
                            throw new SQLException("Конфликт входных данных финализации");
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    /** Создаёт задание только после регистрации полного комплекта архивных файлов.
     * @param id ID завершённого интервью
     * @return false, если доставка необходимых входов ещё продолжается
     * @throws SQLException при ошибке базы или конфликте входов */
    public boolean enqueueRegistered(UUID id) throws SQLException {
        try (Connection connection = connect(); var statement = connection.prepareStatement("""
                SELECT s.payload::text, i.call_status,
                       COALESCE(jsonb_object_agg(a.kind, jsonb_build_object(
                           'kind', a.kind, 'objectKey', a.object_key, 'sizeBytes', a.size_bytes,
                           'sha256', a.sha256)) FILTER (WHERE a.kind IS NOT NULL), '{}'::jsonb)::text,
                       bool_or(a.kind IN ('audio_wav', 'audio_mono_wav')),
                       bool_or(a.kind = 'replay_inputs_json'),
                       bool_or(a.kind = 'interview_events_jsonl')
                FROM archive_interview i
                JOIN finalization_snapshot s ON s.interview_id = i.id
                LEFT JOIN archive_artifact a ON a.interview_id = i.id AND NOT a.deleting
                    AND a.kind IN ('audio_wav', 'audio_mono_wav', 'replay_inputs_json', 'interview_events_jsonl')
                WHERE i.id = ?
                GROUP BY s.payload, i.call_status
                """)) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !"finished".equals(rows.getString(2))
                        || !rows.getBoolean(4) || !rows.getBoolean(5) || !rows.getBoolean(6)) return false;
                enqueue(id, rows.getString(1), rows.getString(3));
                return true;
            }
        }
    }

    /** Выдаёт одно ожидающее или просроченное задание.
     * @param duration длительность аренды
     * @return выданное задание либо null
     * @throws SQLException при ошибке базы */
    public Lease claim(Duration duration) throws SQLException {
        long seconds = positiveSeconds(duration);
        UUID token = UUID.randomUUID();
        try (Connection connection = connect(); var statement = connection.prepareStatement("""
                WITH candidate AS (
                    SELECT interview_id FROM finalization_job
                    WHERE state = 'pending'
                       OR (state = 'retry_wait' AND next_attempt_at <= CURRENT_TIMESTAMP)
                       OR (state = 'running' AND lease_until <= CURRENT_TIMESTAMP)
                    ORDER BY created_at, interview_id
                    FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE finalization_job AS job
                SET state = 'running', attempt_count = job.attempt_count + 1,
                    lease_token = ?, lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    next_attempt_at = NULL, updated_at = CURRENT_TIMESTAMP
                FROM candidate WHERE job.interview_id = candidate.interview_id
                RETURNING job.interview_id, job.snapshot::text, job.input_manifest::text,
                          job.result_manifest::text, job.lease_until, job.attempt_count
                """)) {
            statement.setObject(1, token);
            statement.setLong(2, seconds);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new Lease(rows.getObject(1, UUID.class), rows.getString(2), rows.getString(3),
                        rows.getString(4), token, rows.getTimestamp(5).toInstant(), rows.getInt(6));
            }
        }
    }

    /** Возвращает данные только действующей попытке.
     * @param id интервью
     * @param token токен аренды
     * @return задание или null после истечения аренды
     * @throws SQLException ошибка базы */
    public Lease current(UUID id, UUID token) throws SQLException {
        try (Connection connection = connect(); var statement = connection.prepareStatement("""
                SELECT snapshot::text, input_manifest::text, result_manifest::text, lease_until, attempt_count
                FROM finalization_job WHERE interview_id = ? AND state = 'running'
                    AND lease_token = ? AND lease_until > CURRENT_TIMESTAMP
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, token);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? new Lease(id, rows.getString(1), rows.getString(2), rows.getString(3),
                        token, rows.getTimestamp(4).toInstant(), rows.getInt(5)) : null;
            }
        }
    }

    /** Продлевает действующую аренду.
     * @param id ID интервью
     * @param token токен попытки
     * @param duration новая длительность
     * @return false, если аренда уже истекла или выдана другой попытке
     * @throws SQLException при ошибке базы */
    public boolean renew(UUID id, UUID token, Duration duration) throws SQLException {
        try (Connection connection = connect(); var statement = connection.prepareStatement("""
                UPDATE finalization_job SET lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    updated_at = CURRENT_TIMESTAMP
                WHERE interview_id = ? AND state = 'running' AND lease_token = ?
                    AND lease_until > CURRENT_TIMESTAMP
                """)) {
            statement.setLong(1, positiveSeconds(duration));
            statement.setObject(2, id);
            statement.setObject(3, token);
            return statement.executeUpdate() == 1;
        }
    }

    /** Закрепляет единственный манифест результата для последующих попыток публикации.
     * @param id ID интервью
     * @param token токен попытки
     * @param manifest проверенный манифест в JSON
     * @return false при просроченной аренде или конфликте манифеста
     * @throws SQLException при ошибке базы */
    public boolean saveResult(UUID id, UUID token, String manifest) throws SQLException {
        try (Connection connection = connect(); var statement = connection.prepareStatement("""
                UPDATE finalization_job SET result_manifest = CAST(? AS jsonb), updated_at = CURRENT_TIMESTAMP
                WHERE interview_id = ? AND state = 'running' AND lease_token = ?
                    AND lease_until > CURRENT_TIMESTAMP
                    AND (result_manifest IS NULL OR result_manifest = CAST(? AS jsonb))
                """)) {
            statement.setString(1, manifest);
            statement.setObject(2, id);
            statement.setObject(3, token);
            statement.setString(4, manifest);
            return statement.executeUpdate() == 1;
        }
    }

    /** Планирует повтор или фиксирует окончательную ошибку.
     * @param id ID интервью
     * @param token токен попытки
     * @param delay задержка повтора или null для окончательной ошибки
     * @param errorCode безопасный код ошибки
     * @return false при просроченной аренде
     * @throws SQLException при ошибке базы */
    public boolean fail(UUID id, UUID token, Duration delay, String errorCode) throws SQLException {
        if (errorCode == null || !errorCode.matches("[a-z][a-z0-9_]{0,63}"))
            throw new IllegalArgumentException("Некорректный код ошибки");
        if (delay != null && delay.isNegative()) throw new IllegalArgumentException("Некорректная задержка");
        try (Connection connection = connect(); var statement = connection.prepareStatement("""
                UPDATE finalization_job
                SET state = ?, next_attempt_at = CASE WHEN ? THEN CURRENT_TIMESTAMP + (? * INTERVAL '1 second') ELSE NULL END,
                    error_code = ?, lease_token = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE interview_id = ? AND state = 'running' AND lease_token = ?
                    AND lease_until > CURRENT_TIMESTAMP
                """)) {
            statement.setString(1, delay == null ? "failed" : "retry_wait");
            statement.setBoolean(2, delay != null);
            statement.setLong(3, delay == null ? 0 : delay.toSeconds());
            statement.setString(4, errorCode);
            statement.setObject(5, id);
            statement.setObject(6, token);
            return statement.executeUpdate() == 1;
        }
    }

    /** Отмечает подтверждённый результат готовым.
     * @param id ID интервью
     * @param token токен попытки
     * @return false, если аренда истекла, манифест не сохранён или отчёт не зарегистрирован
     * @throws SQLException при ошибке базы */
    public boolean complete(UUID id, UUID token) throws SQLException {
        try (Connection connection = connect(); var statement = connection.prepareStatement("""
                UPDATE finalization_job AS job SET state = 'ready', lease_token = NULL, lease_until = NULL,
                    error_code = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE job.interview_id = ? AND job.state = 'running' AND job.lease_token = ?
                    AND job.lease_until > CURRENT_TIMESTAMP AND job.result_manifest IS NOT NULL
                    AND EXISTS (SELECT 1 FROM archive_artifact artifact
                                WHERE artifact.interview_id = job.interview_id
                                  AND artifact.kind = 'report_xlsx' AND NOT artifact.deleting
                                  AND EXISTS (SELECT 1 FROM jsonb_array_elements(job.result_manifest->'artifacts') result
                                              WHERE result->>'kind' = artifact.kind
                                                AND result->>'sha256' = artifact.sha256
                                                AND result->>'sizeBytes' = artifact.size_bytes::text))
                    AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(job.result_manifest->'artifacts') result
                                    WHERE NOT EXISTS (SELECT 1 FROM archive_artifact artifact
                                        WHERE artifact.interview_id = job.interview_id AND NOT artifact.deleting
                                          AND artifact.kind = result->>'kind'
                                          AND artifact.sha256 = result->>'sha256'
                                          AND artifact.size_bytes::text = result->>'sizeBytes'))
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, token);
            return statement.executeUpdate() == 1;
        }
    }

    /** Читает состояние задания, чтобы обработчик отличил потерю ответа от потери фиксации.
     * @param id ID интервью
     * @return состояние либо null, если задание ещё не создано
     * @throws SQLException при ошибке базы */
    public Status status(UUID id) throws SQLException {
        try (Connection connection = connect(); var statement = connection.prepareStatement(
                "SELECT state, result_manifest::text FROM finalization_job WHERE interview_id = ?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? new Status(rows.getString(1), rows.getString(2)) : null;
            }
        }
    }

    private static long positiveSeconds(Duration duration) {
        if (duration == null || duration.toSeconds() < 1) throw new IllegalArgumentException("Аренда должна быть не меньше секунды");
        return duration.toSeconds();
    }
}
