package ru.sttplatform.finalization;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.gateway.config.GatewayProperties;
import ru.sttplatform.stt.config.WhisperProperties;
import ru.sttplatform.stt.transcript.TranscriptStore;
import ru.sttplatform.stt.whisper.WhisperClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Выдаёт задания с арендой, сохраняет неизменяемый результат и публикует его повторяемо. */
@Component
public final class FinalizationWorker implements ApplicationRunner, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(FinalizationWorker.class);
    private static final long LEASE_SECONDS = 120;
    private final URI analytics;
    private final String token, bucket;
    private final S3Client s3;
    private final WhisperClient whisper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private Thread worker;

    public FinalizationWorker(@Value("${worker.analytics-url}") String analytics,
                              @Value("${interview.events.token}") String token,
                              @Value("${archive.s3.endpoint}") String endpoint,
                              @Value("${archive.s3.bucket}") String bucket,
                              @Value("${archive.s3.access-key}") String access,
                              @Value("${archive.s3.secret-key}") String secret,
                              @Value("${whisper.url}") String whisperUrl) {
        if (token.length() < 32 || bucket.isBlank() || access.isBlank() || secret.isBlank() || whisperUrl.isBlank())
            throw new IllegalArgumentException("Не настроены параметры обработчика");
        this.analytics = URI.create(analytics); this.token = token; this.bucket = bucket;
        this.s3 = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
                .forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret))).build();
        this.whisper = new WhisperClient(new WhisperProperties(whisperUrl, false, null, null, null, null, null, null, null));
    }

    @Override public void run(ApplicationArguments args) {
        worker = new Thread(this::loop, "finalization-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private void loop() {
        while (!stopping.get()) {
            try {
                var response = request("POST", "/internal/finalization/claim", Json.write(Map.of("seconds", LEASE_SECONDS)), null);
                if (response.statusCode() == 204) { Thread.sleep(3000); continue; }
                requireSuccess(response);
                process(Json.tree(response.body()));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); return;
            } catch (Exception error) {
                log.warn("Обработчик временно недоступен: {}", error.toString());
                try { Thread.sleep(5000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private void process(JsonNode lease) throws InterruptedException {
        String id = UUID.fromString(lease.path("interviewId").asText()).toString();
        String leaseToken = UUID.fromString(lease.path("leaseToken").asText()).toString();
        int attempt = lease.path("attemptCount").asInt(1);
        AtomicBoolean leaseLost = new AtomicBoolean();
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
        heartbeat.scheduleWithFixedDelay(() -> {
            try {
                var response = request("POST", "/internal/finalization/" + id + "/renew",
                        Json.write(Map.of("seconds", LEASE_SECONDS)), leaseToken);
                if (response.statusCode() == 409) leaseLost.set(true);
                else requireSuccess(response);
            } catch (Exception error) { log.warn("Не удалось продлить аренду {}: {}", id, error.toString()); }
        }, 30, 30, TimeUnit.SECONDS);
        Path temporary = null;
        try {
            temporary = Files.createTempDirectory("finalization-");
            JsonNode manifest;
            if (lease.hasNonNull("resultManifest")) manifest = Json.tree(lease.path("resultManifest").asText());
            else {
                JsonNode inputs = Json.tree(lease.path("inputManifest").asText());
                manifest = prepare(id, leaseToken, inputs, temporary, leaseLost, attempt >= 10);
                checkLease(leaseLost);
                requireSuccess(request("PUT", "/internal/finalization/" + id + "/result",
                        Json.write(Map.of("leaseToken", leaseToken, "manifest", manifest)), null));
            }
            for (var artifact : manifest.path("artifacts")) {
                checkLease(leaseLost);
                publish(id, artifact, temporary);
            }
            checkLease(leaseLost);
            completeAndCleanup(id, leaseToken, manifest);
            log.info("Финализация интервью {} завершена", id);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw error;
        } catch (Exception error) {
            log.warn("Финализация интервью {} отложена: {}", id, error.toString());
            if (!leaseLost.get()) try {
                Long delay = attempt >= 10 ? null : Math.min(300L, 5L << Math.min(attempt, 5));
                requireSuccess(request("POST", "/internal/finalization/" + id + "/failure",
                        Json.write(Json.map("leaseToken", leaseToken, "delaySeconds", delay, "errorCode", "processing_failed")), null));
            } catch (Exception reportError) { log.warn("Не удалось зафиксировать ошибку: {}", reportError.toString()); }
        } finally {
            heartbeat.shutdownNow();
            if (temporary != null) try (var files = Files.walk(temporary)) {
                for (var path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (IOException error) { log.warn("Не удалось удалить временные файлы: {}", error.toString()); }
        }
    }

    private void completeAndCleanup(String id, String leaseToken, JsonNode manifest)
            throws IOException, InterruptedException {
        IOException completionError = null;
        try {
            requireSuccess(request("POST", "/internal/finalization/" + id + "/complete",
                    Json.write(Map.of("leaseToken", leaseToken)), null));
        } catch (IOException error) {
            completionError = error;
        }
        if (completionError != null && !readyWithSameManifest(id, manifest)) throw completionError;
        cleanupStaging(id);
    }

    private boolean readyWithSameManifest(String id, JsonNode expected)
            throws IOException, InterruptedException {
        var response = request("GET", "/internal/finalization/" + id + "/status", "", null);
        if (response.statusCode() / 100 != 2) return false;
        JsonNode status = Json.tree(response.body());
        if (!"ready".equals(status.path("state").asText())) return false;
        try { return expected.equals(Json.tree(status.path("resultManifest").asText())); }
        catch (RuntimeException invalid) { return false; }
    }

    private void cleanupStaging(String id) {
        String prefix = "staging/" + id + "/";
        String continuation = null;
        try {
            do {
                final String token = continuation;
                var builder = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix);
                if (token != null) builder.continuationToken(token);
                var page = s3.listObjectsV2(builder.build());
                for (S3Object object : page.contents())
                    s3.deleteObject(request -> request.bucket(bucket).key(object.key()));
                continuation = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
            } while (continuation != null);
        } catch (Exception error) {
            log.warn("Не удалось очистить временные результаты интервью {}: {}", id, error.toString());
        }
    }

    private JsonNode prepare(String id, String leaseToken, JsonNode inputs, Path dir,
                             AtomicBoolean leaseLost, boolean allowVoskFallback) throws Exception {
        JsonNode audio = inputs.has("audio_wav") ? inputs.path("audio_wav") : inputs.path("audio_mono_wav");
        Path wave = dir.resolve("audio.wav"), journal = dir.resolve("events.jsonl"), replay = dir.resolve("replay.json");
        download(id, audio, wave);
        download(id, inputs.path("interview_events_jsonl"), journal);
        download(id, inputs.path("replay_inputs_json"), replay);
        var decoded = new FinalizationTranscriber().transcribe(id, wave, journal,
                Json.MAPPER.readTree(replay.toFile()), dir.resolve("audio.pcm"), whisper, allowVoskFallback);
        checkLease(leaseLost);
        var score = requestBytes("POST", "/internal/finalization/" + id + "/score",
                Json.write(Map.of("transcriptSource", decoded.source(), "answers", decoded.answers())), leaseToken);
        if (score.statusCode() / 100 != 2) throw new IOException("Оценка вернула HTTP " + score.statusCode());
        Path xlsx = dir.resolve("report.xlsx"); Files.write(xlsx, score.body());
        Path sessionDir = dir.resolve("session"); Files.createDirectory(sessionDir);
        new TranscriptStore(new GatewayProperties(null, null, dir.toString())).publish(sessionDir, id, decoded.words());
        List<Map<String, Object>> artifacts = new ArrayList<>();
        stage(id, leaseToken, "report_xlsx", xlsx, artifacts);
        stage(id, leaseToken, "transcript_md", sessionDir.resolve("transcripts/transcript.md"), artifacts);
        stage(id, leaseToken, "transcript_timed_md", sessionDir.resolve("transcripts/transcript-timed.md"), artifacts);
        stage(id, leaseToken, "transcripts_zip", sessionDir.resolve("transcripts/transcripts.zip"), artifacts);
        return Json.MAPPER.valueToTree(Map.of("interviewId", id, "transcriptSource", decoded.source(), "artifacts", artifacts));
    }

    private void stage(String id, String leaseToken, String kind, Path file, List<Map<String, Object>> artifacts) throws Exception {
        String key = "staging/" + id + "/" + leaseToken + "/" + kind;
        long size = Files.size(file); String sha = digest(file);
        s3.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromFile(file));
        verify(key, size, sha);
        artifacts.add(Map.of("kind", kind, "stagingKey", key, "sizeBytes", size, "sha256", sha));
    }

    private void publish(String id, JsonNode artifact, Path dir) throws Exception {
        String kind = artifact.path("kind").asText(), staging = artifact.path("stagingKey").asText();
        long size = artifact.path("sizeBytes").asLong(-1); String sha = artifact.path("sha256").asText();
        if (!kind.matches("[a-z][a-z0-9_]{0,63}") || !staging.startsWith("staging/" + id + "/")
                || size < 0 || !sha.matches("[0-9a-f]{64}")) throw new IOException("Некорректный манифест");
        Path file = dir.resolve("publish-" + kind); downloadObject(staging, size, sha, file);
        String canonical = "interviews/" + id + "/" + kind;
        try {
            s3.putObject(request -> request.bucket(bucket).key(canonical)
                    .overrideConfiguration(config -> config.putHeader("If-None-Match", "*")), RequestBody.fromFile(file));
        } catch (S3Exception error) { if (error.statusCode() != 412) throw error; }
        verify(canonical, size, sha);
        requireSuccess(request("PUT", "/internal/archive/interviews/" + id + "/artifacts",
                Json.write(Map.of("kind", kind, "objectKey", canonical, "sizeBytes", size, "sha256", sha)), null));
    }

    private void download(String id, JsonNode artifact, Path path) throws Exception {
        String kind = artifact.path("kind").asText(), key = artifact.path("objectKey").asText();
        if (!kind.matches("[a-z][a-z0-9_]{0,63}") || !key.equals("interviews/" + id + "/" + kind))
            throw new IOException("Неверный адрес архивного файла");
        downloadObject(key, artifact.path("sizeBytes").asLong(-1), artifact.path("sha256").asText(), path);
    }

    private void downloadObject(String key, long size, String sha, Path path) throws Exception {
        if (size < 0 || !sha.matches("[0-9a-f]{64}")) throw new IOException("Неверные метаданные файла");
        try (var stream = s3.getObject(request -> request.bucket(bucket).key(key))) {
            if (stream.response().contentLength() != size) throw new IOException("Размер файла не совпадает");
            Files.copy(stream, path);
        }
        if (Files.size(path) != size || !digest(path).equals(sha)) throw new IOException("Контрольная сумма не совпадает");
    }

    private void verify(String key, long size, String sha) throws Exception {
        try (var stream = s3.getObject(request -> request.bucket(bucket).key(key))) {
            if (stream.response().contentLength() != size || !digest(stream).equals(sha))
                throw new IOException("Сохранённый объект не совпадает с исходником");
        }
    }

    private static String digest(Path path) throws Exception { try (var input = Files.newInputStream(path)) { return digest(input); } }
    private static String digest(InputStream input) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536]; int count;
        while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
        return HexFormat.of().formatHex(hash.digest());
    }
    private static void checkLease(AtomicBoolean lost) throws IOException { if (lost.get()) throw new IOException("Аренда задания истекла"); }
    private HttpResponse<String> request(String method, String path, String body, String lease) throws IOException, InterruptedException {
        return http.send(build(method, path, body, lease), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<byte[]> requestBytes(String method, String path, String body, String lease) throws IOException, InterruptedException {
        return http.send(build(method, path, body, lease), HttpResponse.BodyHandlers.ofByteArray());
    }
    private HttpRequest build(String method, String path, String body, String lease) {
        var builder = HttpRequest.newBuilder(analytics.resolve(path)).timeout(Duration.ofMinutes(5))
                .header("X-Internal-Token", token).header("Content-Type", "application/json");
        if (lease != null) builder.header("X-Lease-Token", lease);
        if (method.equals("GET")) return builder.GET().build();
        return builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build();
    }
    private static void requireSuccess(HttpResponse<?> response) throws IOException {
        if (response.statusCode() / 100 != 2) throw new IOException("Сервис вернул HTTP " + response.statusCode());
    }

    @PreDestroy @Override public void close() {
        stopping.set(true);
        if (worker != null) worker.interrupt();
        s3.close();
    }
}
