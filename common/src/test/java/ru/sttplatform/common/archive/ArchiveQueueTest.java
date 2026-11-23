package ru.sttplatform.common.archive;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveQueueTest {
    @TempDir Path directory;

    @Test
    @EnabledIfEnvironmentVariable(named = "ARCHIVE_TEST_S3_ENDPOINT", matches = ".+")
    void retriesRegistrationWithoutOverwritingStoredObject() throws Exception {
        var attempts = new AtomicInteger();
        var registered = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/archive/interviews/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            int attempt = attempts.incrementAndGet();
            if (attempt > 1) registered.set(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(attempt == 1 ? 503 : 204, -1);
            exchange.close();
        });
        server.start();
        URI registry = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        UUID id = UUID.randomUUID();
        Path input = directory.resolve("original");
        Files.writeString(input, "two channels");
        try {
            try (ArchiveQueue queue = new ArchiveQueue(directory.resolve("jobs"), URI.create(System.getenv("ARCHIVE_TEST_S3_ENDPOINT")),
                    "interviews", "local-test-key", "local-test-secret", registry, "local-test-token-01234567890123456789")) {
                queue.submit(id, "audio_wav", input);
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
                while (registered.get() == null && System.nanoTime() < deadline) Thread.sleep(100);
                assertNotNull(registered.get(), "задание должно повториться после ошибки реестра");
                assertTrue(registered.get().contains("audio_wav"));
                assertTrue(Files.exists(input));
            }
        } finally { server.stop(0); }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ARCHIVE_TEST_S3_ENDPOINT", matches = ".+")
    void resumesQueuedDeliveryAfterRestart() throws Exception {
        var attempts = new AtomicInteger();
        var allow = new java.util.concurrent.atomic.AtomicBoolean(false);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/archive/interviews/", exchange -> {
            exchange.getRequestBody().readAllBytes(); attempts.incrementAndGet();
            exchange.sendResponseHeaders(allow.get() ? 204 : 503, -1); exchange.close();
        });
        server.start();
        URI registry = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        Path jobs = directory.resolve("restart-jobs"), input = directory.resolve("recording");
        UUID id = UUID.randomUUID(); Files.writeString(input, "restart-safe-audio");
        try {
            try (ArchiveQueue queue = new ArchiveQueue(jobs, URI.create(System.getenv("ARCHIVE_TEST_S3_ENDPOINT")),
                    "interviews", "local-test-key", "local-test-secret", registry, "local-test-token-01234567890123456789")) {
                queue.submit(id, "audio_wav", input);
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                while (attempts.get() == 0 && System.nanoTime() < deadline) Thread.sleep(100);
                assertTrue(attempts.get() > 0);
                assertTrue(Files.exists(jobs.resolve(id + "_audio_wav/payload")));
            }
            allow.set(true);
            try (ArchiveQueue queue = new ArchiveQueue(jobs, URI.create(System.getenv("ARCHIVE_TEST_S3_ENDPOINT")),
                    "interviews", "local-test-key", "local-test-secret", registry, "local-test-token-01234567890123456789")) {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                while (Files.exists(jobs.resolve(id + "_audio_wav")) && System.nanoTime() < deadline) Thread.sleep(100);
                assertFalse(Files.exists(jobs.resolve(id + "_audio_wav")));
                assertTrue(Files.exists(input));
            }
        } finally { server.stop(0); }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ARCHIVE_TEST_S3_ENDPOINT", matches = ".+")
    void removesReleasedSourceOnlyAfterMatchingRegistration() throws Exception {
        var stored = new AtomicReference<String>();
        var permit = new java.util.concurrent.atomic.AtomicBoolean(false);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/archive/interviews/", exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                byte[] response = ("{\"artifacts\":[" + (stored.get() == null ? "" : stored.get()) + "]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } else {
                byte[] body = exchange.getRequestBody().readAllBytes();
                if (permit.get()) stored.set(new String(body, java.nio.charset.StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(permit.get() ? 204 : 503, -1);
            }
            exchange.close();
        });
        server.start();
        URI registry = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        Path jobs = directory.resolve("release-jobs"), source = directory.resolve("report.xlsx");
        UUID id = UUID.randomUUID(); Files.writeString(source, "report");
        try (ArchiveQueue queue = new ArchiveQueue(jobs, URI.create(System.getenv("ARCHIVE_TEST_S3_ENDPOINT")),
                "interviews", "local-test-key", "local-test-secret", registry, "local-test-token-01234567890123456789")) {
            queue.submit(id, "report_xlsx", source);
            queue.release(id, "report_xlsx", java.util.List.of(source));
            Thread.sleep(1500);
            assertTrue(Files.exists(source));
            permit.set(true);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(35);
            while (Files.exists(source) && System.nanoTime() < deadline) Thread.sleep(100);
            assertFalse(Files.exists(source), "после подтверждения исходник должен удалиться");
        } finally { server.stop(0); }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ARCHIVE_TEST_S3_ENDPOINT", matches = ".+")
    void retentionDeletesOnlyAllowedObject() throws Exception {
        var stored = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/archive/interviews/", exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                byte[] response = ("{\"artifacts\":[" + (stored.get() == null ? "" : stored.get()) + "]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } else {
                stored.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(204, -1);
            }
            exchange.close();
        });
        server.start();
        Path input = directory.resolve("retention.wav"); Files.writeString(input, "retention test");
        UUID id = UUID.randomUUID();
        try (ArchiveQueue queue = new ArchiveQueue(directory.resolve("retention-jobs"), URI.create(System.getenv("ARCHIVE_TEST_S3_ENDPOINT")),
                "interviews", "local-test-key", "local-test-secret", URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "local-test-token-01234567890123456789")) {
            queue.submit(id, "audio_wav", input);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while (stored.get() == null && System.nanoTime() < deadline) Thread.sleep(100);
            assertNotNull(stored.get());
            assertTrue(queue.available(id, "audio_wav"));
            try (var stream = queue.open(id, "audio_wav")) { assertNotNull(stream); assertEquals("retention test", new String(stream.readAllBytes())); }
            assertThrows(IllegalArgumentException.class, () -> queue.delete(id, "replay_inputs_json"));
            queue.delete(id, "audio_wav");
            assertFalse(queue.available(id, "audio_wav"));
            assertThrows(software.amazon.awssdk.services.s3.model.S3Exception.class, () -> queue.open(id, "audio_wav"));
        } finally { server.stop(0); }
    }
}
