package ru.sttplatform.analytics.archive;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import ru.sttplatform.analytics.engine.QuestionCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

/** Хранит неизменяемые версии банка и закрепляет их за интервью. */
@Component
@ConditionalOnProperty(name = "archive.database.url")
public final class CatalogStore {
    private final String url;
    private final String user;
    private final String password;

    /** Создаёт хранилище, применяет миграции и устанавливает начальный каталог.
     * @param url JDBC-адрес PostgreSQL
     * @param user пользователь базы
     * @param password пароль базы
     * @throws IOException если встроенный каталог недоступен
     * @throws SQLException при ошибке базы
     */
    public CatalogStore(@Value("${archive.database.url}") String url,
                        @Value("${archive.database.user}") String user,
                        @Value("${archive.database.password}") String password) throws IOException, SQLException {
        this.url = url;
        this.user = user;
        this.password = password;
        Flyway.configure().dataSource(url, user, password).load().migrate();
        try (Connection connection = connect(); var query = connection.createStatement();
             ResultSet rows = query.executeQuery("SELECT version_id FROM catalog_current")) {
            if (!rows.next()) publish(resource("interview/team-questions.tsv"), resource("interview/team-followups.tsv"), true);
        }
    }

    private static String resource(String name) throws IOException {
        try (var stream = new ClassPathResource(name).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Connection connect() throws SQLException { return DriverManager.getConnection(url, user, password); }

    /** Публикует проверенный TSV-каталог атомарно.
     * @param questions основные вопросы
     * @param followups уточнения
     * @param onlyIfEmpty не заменять ранее опубликованный банк
     * @return идентификатор версии
     * @throws SQLException если транзакция не выполнена
     */
    public long publish(String questions, String followups, boolean onlyIfEmpty) throws SQLException {
        List<QuestionCatalog.Entry> entries = QuestionCatalog.parse(questions, followups);
        QuestionCatalog.toQuestions(entries);
        String digest;
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(questions.getBytes(StandardCharsets.UTF_8));
            hash.update((byte) 0);
            digest = HexFormat.of().formatHex(hash.digest(followups.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (var lock = connection.createStatement()) { lock.execute("SELECT pg_advisory_xact_lock(674832615)"); }
                if (onlyIfEmpty) {
                    try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT version_id FROM catalog_current")) {
                        if (rows.next()) { long current = rows.getLong(1); connection.commit(); return current; }
                    }
                }
                long version;
                boolean inserted;
                try (var statement = connection.prepareStatement("INSERT INTO catalog_version(digest, questions_tsv, followups_tsv) VALUES (?,?,?) ON CONFLICT (digest) DO NOTHING RETURNING id")) {
                    statement.setString(1, digest); statement.setString(2, questions); statement.setString(3, followups);
                    try (var rows = statement.executeQuery()) { inserted = rows.next(); version = inserted ? rows.getLong(1) : 0; }
                }
                if (!inserted) {
                    try (var statement = connection.prepareStatement("SELECT id FROM catalog_version WHERE digest=?")) {
                        statement.setString(1, digest);
                        try (var rows = statement.executeQuery()) { if (!rows.next()) throw new SQLException("Каталог не найден"); version = rows.getLong(1); }
                    }
                } else {
                    insertEntries(connection, version, entries);
                }
                try (var statement = connection.prepareStatement("INSERT INTO catalog_current(singleton, version_id) VALUES (TRUE,?) ON CONFLICT(singleton) DO UPDATE SET version_id=EXCLUDED.version_id")) {
                    statement.setLong(1, version); statement.executeUpdate();
                }
                connection.commit();
                return version;
            } catch (SQLException | RuntimeException error) { connection.rollback(); throw error; }
        }
    }

    private static void insertEntries(Connection connection, long version, List<QuestionCatalog.Entry> entries) throws SQLException {
        try (var question = connection.prepareStatement("INSERT INTO catalog_question VALUES (?,?,?,?,?,?,?,?)");
             var followup = connection.prepareStatement("INSERT INTO catalog_followup VALUES (?,?,?,?,?,?,?)")) {
            for (var entry : entries) {
                question.setLong(1, version); question.setString(2, entry.id()); question.setInt(3, entry.order());
                question.setString(4, entry.level()); question.setString(5, entry.topic()); question.setString(6, entry.text());
                question.setString(7, entry.reference()); question.setString(8, entry.notes()); question.executeUpdate();
                int position = 0;
                for (var item : entry.followUps()) {
                    followup.setLong(1, version); followup.setString(2, item.id()); followup.setString(3, entry.id());
                    followup.setInt(4, ++position); followup.setString(5, item.text()); followup.setString(6, item.reference());
                    followup.setString(7, item.notes()); followup.executeUpdate();
                }
            }
        }
    }

    /** Закрепляет текущий каталог при первом старте; повторно возвращает прежний.
     * @param interviewId UUID интервью
     * @return записи закреплённой версии
     * @throws SQLException если реестр недоступен
     */
    public List<QuestionCatalog.Entry> bind(UUID interviewId) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO archive_interview(id,catalog_version_id) SELECT ?,version_id FROM catalog_current ON CONFLICT(id) DO NOTHING")) {
                    statement.setObject(1, interviewId); statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("SELECT c.questions_tsv,c.followups_tsv FROM archive_interview i JOIN catalog_version c ON c.id=i.catalog_version_id WHERE i.id=?")) {
                    statement.setObject(1, interviewId);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) throw new SQLException("Для интервью нет опубликованного каталога");
                        var result = QuestionCatalog.parse(rows.getString(1), rows.getString(2));
                        connection.commit(); return result;
                    }
                }
            } catch (SQLException | RuntimeException error) { connection.rollback(); throw error; }
        }
    }

    /** Метаданные проверенного объекта, связанного с интервью.
     * @param kind тип файла
     * @param objectKey ключ в хранилище
     * @param sizeBytes число байтов
     * @param sha256 контрольная сумма
     */
    public record Artifact(String kind, String objectKey, long sizeBytes, String sha256) {}

    /** Регистрирует проверенный объект; повтор с иными данными отклоняется.
     * @param interviewId идентификатор интервью
     * @param artifact сведения об объекте
     * @throws SQLException при ошибке или конфликте реестра
     */
    public void register(UUID interviewId, Artifact artifact) throws SQLException {
        if (!artifact.kind().matches("[a-z][a-z0-9_-]{0,63}") ||
                !artifact.objectKey().equals("interviews/" + interviewId + "/" + artifact.kind()) ||
                artifact.sizeBytes() < 0 || !artifact.sha256().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Некорректные метаданные файла");
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement("INSERT INTO archive_artifact(interview_id,kind,object_key,size_bytes,sha256) VALUES (?,?,?,?,?) ON CONFLICT (interview_id,kind) DO NOTHING")) {
                    statement.setObject(1, interviewId); statement.setString(2, artifact.kind());
                    statement.setString(3, artifact.objectKey()); statement.setLong(4, artifact.sizeBytes()); statement.setString(5, artifact.sha256());
                    statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement("SELECT object_key,size_bytes,sha256 FROM archive_artifact WHERE interview_id=? AND kind=?")) {
                    statement.setObject(1, interviewId); statement.setString(2, artifact.kind());
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next() || !artifact.objectKey().equals(rows.getString(1)) || artifact.sizeBytes() != rows.getLong(2) || !artifact.sha256().equals(rows.getString(3)))
                            throw new SQLException("Конфликт зарегистрированного артефакта");
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) { connection.rollback(); throw error; }
        }
    }

    /** Состояние сохранённого интервью.
     * @param id идентификатор
     * @param catalogVersion версия банка
     * @param callStatus состояние звонка
     * @param artifacts зарегистрированные файлы
     */
    public record Interview(UUID id, long catalogVersion, String callStatus, List<Artifact> artifacts) {}

    /** Загружает карточку интервью.
     * @param id идентификатор интервью
     * @return карточка либо null
     * @throws SQLException при ошибке реестра
     */
    public Interview find(UUID id) throws SQLException {
        try (Connection connection = connect(); var statement = connection.prepareStatement("SELECT catalog_version_id,call_status FROM archive_interview WHERE id=?")) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                long version = rows.getLong(1); String status = rows.getString(2);
                List<Artifact> files = new ArrayList<>();
                try (var query = connection.prepareStatement("SELECT kind,object_key,size_bytes,sha256 FROM archive_artifact WHERE interview_id=? AND NOT deleting ORDER BY kind")) {
                    query.setObject(1, id);
                    try (var fileRows = query.executeQuery()) {
                        while (fileRows.next()) files.add(new Artifact(fileRows.getString(1), fileRows.getString(2), fileRows.getLong(3), fileRows.getString(4)));
                    }
                }
                return new Interview(id, version, status, List.copyOf(files));
            }
        }
    }

    /** Отмечает окончание захвата безотносительно успеха обработки.
     * @param id идентификатор интервью
     * @throws SQLException если запись не найдена или БД недоступна
     */
    public void finish(UUID id) throws SQLException {
        try (Connection connection = connect(); var statement = connection.prepareStatement("UPDATE archive_interview SET call_status='finished' WHERE id=?")) {
            statement.setObject(1, id);
            if (statement.executeUpdate() != 1) throw new SQLException("Интервью не найдено");
        }
    }

    /** Кандидат на удаление по сроку хранения.
     * @param interviewId интервью
     * @param kind тип файла
     * @param key ключ объекта */
    public record ExpiredArtifact(UUID interviewId, String kind, String key) {}

    /** Сначала скрывает просроченные файлы, затем возвращает незавершённые удаления.
     * @param audioDays срок аудио, -1 для бессрочного хранения
     * @param reportDays срок отчёта, -1 для бессрочного хранения
     * @return файлы, ожидающие удаления
     * @throws SQLException ошибка базы */
    public List<ExpiredArtifact> expired(int audioDays, int reportDays) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (var update = connection.prepareStatement("UPDATE archive_artifact SET deleting=TRUE WHERE NOT deleting AND ((kind IN ('audio_wav','audio_mono_wav') AND ? > 0 AND created_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')) OR (kind='report_xlsx' AND ? > 0 AND created_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')))")) {
                    update.setInt(1, audioDays); update.setInt(2, audioDays); update.setInt(3, reportDays); update.setInt(4, reportDays); update.executeUpdate();
                }
                List<ExpiredArtifact> results = new ArrayList<>();
                try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT interview_id,kind,object_key FROM archive_artifact WHERE deleting")) {
                    while (rows.next()) results.add(new ExpiredArtifact(rows.getObject(1, UUID.class), rows.getString(2), rows.getString(3)));
                }
                connection.commit(); return results;
            } catch (SQLException | RuntimeException error) { connection.rollback(); throw error; }
        }
    }

    /** Удаляет реестровую запись после удаления объекта.
     * @param artifact ожидающий удаления файл
     * @throws SQLException ошибка базы */
    public void forget(ExpiredArtifact artifact) throws SQLException {
        try (Connection connection = connect(); var statement = connection.prepareStatement("DELETE FROM archive_artifact WHERE interview_id=? AND kind=? AND object_key=? AND deleting")) {
            statement.setObject(1, artifact.interviewId()); statement.setString(2, artifact.kind()); statement.setString(3, artifact.key()); statement.executeUpdate();
        }
    }
}
