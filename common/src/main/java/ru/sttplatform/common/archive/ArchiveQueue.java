package ru.sttplatform.common.archive;

import ru.sttplatform.common.json.Json;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Долговечная очередь проверяемой доставки файлов в S3 и регистрации в аналитике. */
public final class ArchiveQueue implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(ArchiveQueue.class.getName());
    private final Path root;
    private final String bucket;
    private final URI registry;
    private final String token;
    private final S3Client s3;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "archive-queue"); thread.setDaemon(true); return thread;
    });

    /** @param root постоянный каталог очереди
     * @param endpoint адрес S3
     * @param bucket приватный бакет
     * @param accessKey ключ доступа
     * @param secretKey секретный ключ
     * @param registry адрес analytics-service
     * @param token общий секрет сервисов
     * @throws IOException если каталог недоступен */
    public ArchiveQueue(Path root, URI endpoint, String bucket, String accessKey, String secretKey, URI registry, String token) throws IOException {
        if (token.length() < 32 || accessKey.isBlank() || secretKey.isBlank()) throw new IllegalArgumentException("Не настроены секреты архива");
        this.root = root.toAbsolutePath(); this.bucket = bucket; this.registry = registry; this.token = token;
        Files.createDirectories(this.root);
        s3 = S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))).build();
        executor.scheduleWithFixedDelay(this::drain, 0, 10, TimeUnit.SECONDS);
    }

    /** Ставит неизменяемый файл в очередь; исходник сохраняется.
     * @param id UUID интервью
     * @param kind тип артефакта
     * @param source готовый файл
     * @throws IOException при ошибке локального сохранения */
    public synchronized void submit(UUID id, String kind, Path source) throws IOException {
        if (!kind.matches("[a-z][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("Некорректный тип артефакта");
        Path destination = root.resolve(id + "_" + kind);
        if (Files.exists(destination)) {
            if (!digest(source).equals(digest(destination.resolve("payload")))) throw new IOException("Конфликт локального артефакта");
            return;
        }
        Path temporary = Files.createTempDirectory(root, ".pending-");
        try {
            Files.copy(source, temporary.resolve("payload"));
            Files.writeString(temporary.resolve("metadata.json"), Json.write(Map.of("interviewId", id.toString(), "kind", kind)));
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.exists(temporary)) {
                Files.deleteIfExists(temporary.resolve("metadata.json")); Files.deleteIfExists(temporary.resolve("payload")); Files.deleteIfExists(temporary);
            }
        }
        executor.execute(this::drain);
    }

    private void drain() {
        try (var entries = Files.list(root)) {
            var paths = entries.toList();
            paths.stream().filter(path -> Files.isDirectory(path) && !path.getFileName().toString().startsWith(".")).forEach(path -> {
                if (Files.exists(path.resolve("conflict"))) return;
                try { deliver(path); }
                catch (ConflictException error) {
                    try { Files.writeString(path.resolve("conflict"), error.getMessage()); }
                    catch (IOException writeError) { LOG.log(Level.WARNING, "Не удалось сохранить статус конфликта", writeError); }
                    LOG.warning("Конфликт архива: " + path.getFileName());
                }
                catch (Exception error) { LOG.log(Level.WARNING, "Архивация отложена: " + path.getFileName() + ": " + error.getMessage()); }
            });
            paths.stream().filter(path -> path.getFileName().toString().startsWith(".cleanup-")).forEach(path -> {
                try { cleanReleased(path); }
                catch (Exception error) { LOG.log(Level.WARNING, "Очистка исходника отложена: " + error.getMessage()); }
            });
        } catch (IOException error) { LOG.log(Level.WARNING, "Очередь архива недоступна", error); }
    }

    /** Разрешает удалить исходники, когда запись подтверждена реестром.
     * @param id UUID интервью
     * @param kind тип записи
     * @param originals исходные файлы, которые больше не используются
     * @throws IOException ошибка сохранения задания */
    public void release(UUID id, String kind, java.util.List<Path> originals) throws IOException {
        if (!kind.equals("audio_wav") && !kind.equals("audio_mono_wav") && !kind.equals("report_xlsx")) throw new IllegalArgumentException("Неверный тип файла");
        int expectedPaths = kind.equals("report_xlsx") ? 1 : 2;
        if (originals.size() != expectedPaths || !Files.isRegularFile(originals.get(expectedPaths - 1))) throw new IOException("Готовый файл не найден");
        Path verified = originals.get(expectedPaths - 1);
        Path temp = Files.createTempFile(root, ".release-", ".tmp");
        try {
            Files.writeString(temp, Json.write(Map.of("id", id.toString(), "kind", kind,
                    "sha256", digest(verified), "sizeBytes", Files.size(verified),
                    "paths", originals.stream().map(path -> path.toAbsolutePath().normalize().toString()).toList())));
            Files.move(temp, root.resolve(".cleanup-" + id), StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp); }
        executor.execute(this::drain);
    }

    private void cleanReleased(Path marker) throws IOException, InterruptedException {
        var metadata = Json.MAPPER.readTree(marker.toFile());
        UUID id = UUID.fromString(metadata.path("id").asText());
        String kind = metadata.path("kind").asText();
        var interview = find(id);
        if (interview == null) return;
        boolean saved = false;
        for (var artifact : interview.path("artifacts")) saved |= kind.equals(artifact.path("kind").asText())
                && metadata.path("sha256").asText().equals(artifact.path("sha256").asText())
                && metadata.path("sizeBytes").asLong(-1) == artifact.path("sizeBytes").asLong(-2);
        if (!saved) return;
        for (var path : metadata.path("paths")) Files.deleteIfExists(Path.of(path.asText()));
        Files.deleteIfExists(marker);
    }

    private void deliver(Path job) throws Exception {
        var metadata = Json.MAPPER.readTree(job.resolve("metadata.json").toFile());
        UUID id = UUID.fromString(metadata.path("interviewId").asText());
        String kind = metadata.path("kind").asText();
        if (!kind.matches("[a-z][a-z0-9_-]{0,63}")) throw new IOException("Некорректный тип в задании");
        if (kind.equals("call_finished")) {
            var request = HttpRequest.newBuilder(registry.resolve("/internal/archive/interviews/" + id + "/finish"))
                    .timeout(Duration.ofSeconds(15)).header("X-Internal-Token", token)
                    .PUT(HttpRequest.BodyPublishers.noBody()).build();
            var response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) throw new IOException("Реестр вернул HTTP " + response.statusCode());
            complete(job);
            return;
        }
        String key = "interviews/" + id + "/" + kind;
        Path payload = job.resolve("payload"); long size = Files.size(payload); String checksum = digest(payload);
        try { s3.headBucket(request -> request.bucket(bucket)); }
        catch (S3Exception error) { if (error.statusCode() != 404) throw error; s3.createBucket(request -> request.bucket(bucket)); }
        try { s3.headObject(request -> request.bucket(bucket).key(key)); }
        catch (S3Exception error) {
            if (error.statusCode() != 404) throw error;
            try {
                s3.putObject(request -> request.bucket(bucket).key(key)
                        .overrideConfiguration(config -> config.putHeader("If-None-Match", "*")), RequestBody.fromFile(payload));
            } catch (S3Exception conflict) {
                if (conflict.statusCode() != 412) throw conflict;
            }
        }
        try (var stream = s3.getObject(request -> request.bucket(bucket).key(key))) {
            if (stream.response().contentLength() != size || !checksum.equals(digest(stream))) throw new ConflictException("Контрольная сумма объекта не совпадает");
        }
        String body = Json.write(Map.of("kind", kind, "objectKey", key, "sizeBytes", size, "sha256", checksum));
        var request = HttpRequest.newBuilder(registry.resolve("/internal/archive/interviews/" + id + "/artifacts"))
                .timeout(Duration.ofSeconds(15)).header("X-Internal-Token", token).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() / 100 != 2) throw new IOException("Реестр вернул HTTP " + response.statusCode());
        complete(job);
    }

    private void complete(Path job) throws IOException {
        Path finished = root.resolve(".complete-" + UUID.randomUUID());
        Files.move(job, finished, StandardCopyOption.ATOMIC_MOVE);
        Files.deleteIfExists(finished.resolve("metadata.json"));
        Files.deleteIfExists(finished.resolve("payload"));
        Files.deleteIfExists(finished);
    }

    private static final class ConflictException extends IOException {
        private ConflictException(String message) { super(message); }
    }

    /** Проверяет наличие конфликта локального задания.
     * @param id UUID интервью
     * @param kind тип артефакта
     * @return true, если содержимое объекта конфликтует с исходником */
    public boolean hasConflict(UUID id, String kind) {
        return Files.isRegularFile(root.resolve(id + "_" + kind).resolve("conflict")) ||
                Files.isRegularFile(root.resolve(".error-" + id + "_" + kind));
    }

    /** Сохраняет локальный признак повреждённой или неполной записи.
     * @param id UUID интервью
     * @param kind тип файла
     * @throws IOException ошибка диска */
    public void markInvalid(UUID id, String kind) throws IOException {
        if (!kind.matches("[a-z][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("Некорректный тип артефакта");
        Files.writeString(root.resolve(".error-" + id + "_" + kind), "invalid");
    }

    /** Сохраняет в очереди отметку завершённого захвата.
     * @param id идентификатор интервью
     * @throws IOException ошибка очереди */
    public void finish(UUID id) throws IOException {
        Path marker = Files.createTempFile(root, ".finish-", ".tmp");
        try { submit(id, "call_finished", marker); }
        finally { Files.deleteIfExists(marker); }
    }

    /** Регистрирует интервью до начала записи.
     * @param id UUID интервью
     * @throws IOException если реестр недоступен
     * @throws InterruptedException если ожидание прервано */
    public void bind(UUID id) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(registry.resolve("/internal/archive/interviews/" + id + "/bind"))
                .timeout(Duration.ofSeconds(15)).header("X-Internal-Token", token)
                .PUT(HttpRequest.BodyPublishers.noBody()).build();
        var response = http.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() / 100 != 2) throw new IOException("Реестр вернул HTTP " + response.statusCode());
    }

    private static String digest(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) { return digest(input); }
    }

    private static String digest(InputStream input) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Получает карточку из реестра.
     * @param id UUID интервью
     * @return карточка или null при отсутствии
     * @throws IOException если реестр недоступен
     * @throws InterruptedException если ожидание прервано */
    public com.fasterxml.jackson.databind.JsonNode find(UUID id) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(registry.resolve("/internal/archive/interviews/" + id))
                .timeout(Duration.ofSeconds(15)).header("X-Internal-Token", token).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return null;
        if (response.statusCode() != 200) throw new IOException("Реестр вернул HTTP " + response.statusCode());
        return Json.MAPPER.readTree(response.body());
    }

    /** Открывает зарегистрированный объект для чтения.
     * @param id UUID интервью
     * @param kind тип артефакта
     * @return поток объекта или null
     * @throws IOException если реестр недоступен
     * @throws InterruptedException если ожидание прервано */
    public InputStream open(UUID id, String kind) throws IOException, InterruptedException {
        var interview = find(id);
        if (interview == null) return null;
        for (var artifact : interview.path("artifacts")) {
            String expected = "interviews/" + id + "/" + kind;
            if (kind.equals(artifact.path("kind").asText()) && expected.equals(artifact.path("objectKey").asText()))
                return s3.getObject(request -> request.bucket(bucket).key(expected));
        }
        return null;
    }

    /** Проверяет доступность зарегистрированного объекта в хранилище.
     * @param id UUID интервью
     * @param kind тип файла
     * @return true, если объект доступен */
    public boolean available(UUID id, String kind) {
        if (!kind.matches("[a-z][a-z0-9_-]{0,63}")) return false;
        try {
            s3.headObject(request -> request.bucket(bucket).key("interviews/" + id + "/" + kind));
            return true;
        } catch (software.amazon.awssdk.core.exception.SdkException error) {
            return false;
        }
    }

    /** Удаляет объект с истёкшим сроком хранения.
     * @param id UUID интервью
     * @param kind тип файла */
    public void delete(UUID id, String kind) {
        if (!kind.equals("audio_wav") && !kind.equals("audio_mono_wav") && !kind.equals("report_xlsx"))
            throw new IllegalArgumentException("Тип не поддерживает удаление по сроку");
        s3.deleteObject(request -> request.bucket(bucket).key("interviews/" + id + "/" + kind));
    }

    @Override public void close() { executor.shutdownNow(); s3.close(); }
}
