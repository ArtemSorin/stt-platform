package ru.sttplatform.analytics.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class FinalizationJobStoreTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "CATALOG_TEST_JDBC_URL", matches = ".+")
    void waitsForCompleteInputsAndProtectsAudioDuringFinalization() throws Exception {
        String url = System.getenv("CATALOG_TEST_JDBC_URL");
        String user = System.getenv("CATALOG_TEST_JDBC_USER");
        String password = System.getenv("CATALOG_TEST_JDBC_PASSWORD");
        CatalogStore catalog = new CatalogStore(url, user, password);
        FinalizationJobStore jobs = new FinalizationJobStore(url, user, password);
        UUID id = UUID.randomUUID();
        catalog.bind(id);
        assertFalse(jobs.enqueueRegistered(id));
        String snapshot = snapshot(id, catalog.find(id).catalogVersion());
        jobs.saveSnapshot(id, snapshot);
        for (String kind : List.of("audio_wav", "replay_inputs_json", "interview_events_jsonl")) {
            catalog.register(id, new CatalogStore.Artifact(kind, "interviews/" + id + "/" + kind, 8, "a".repeat(64)));
        }
        assertFalse(jobs.enqueueRegistered(id));
        catalog.finish(id);
        try (var connection = DriverManager.getConnection(url, user, password);
             var age = connection.prepareStatement("UPDATE archive_artifact SET created_at = CURRENT_TIMESTAMP - INTERVAL '5 days' WHERE interview_id = ?")) {
            age.setObject(1, id);
            age.executeUpdate();
        }
        assertTrue(catalog.expired(1, -1).stream().noneMatch(item -> item.interviewId().equals(id)));
        assertTrue(jobs.enqueueRegistered(id));
        assertTrue(jobs.enqueueRegistered(id));
        assertTrue(catalog.expired(1, -1).stream().noneMatch(item -> item.interviewId().equals(id)));
        try (var connection = DriverManager.getConnection(url, user, password);
             var fail = connection.prepareStatement("UPDATE finalization_job SET state='failed' WHERE interview_id = ?")) {
            fail.setObject(1, id);
            fail.executeUpdate();
        }
        assertTrue(catalog.expired(1, -1).stream().anyMatch(item -> item.interviewId().equals(id)));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CATALOG_TEST_JDBC_URL", matches = ".+")
    void leasesAreExclusiveAndResultSurvivesRetries() throws Exception {
        String url = System.getenv("CATALOG_TEST_JDBC_URL");
        String user = System.getenv("CATALOG_TEST_JDBC_USER");
        String password = System.getenv("CATALOG_TEST_JDBC_PASSWORD");
        CatalogStore catalog = new CatalogStore(url, user, password);
        FinalizationJobStore jobs = new FinalizationJobStore(url, user, password);
        UUID id = UUID.randomUUID();
        catalog.bind(id);
        long version = catalog.find(id).catalogVersion();
        String snapshot = snapshot(id, version);
        assertThrows(SQLException.class, () -> jobs.enqueue(id, snapshot, "{\"audio\":\"first\"}"));
        assertThrows(SQLException.class, () -> jobs.saveSnapshot(id, snapshot(id, version + 1)));
        assertThrows(SQLException.class, () -> jobs.saveSnapshot(id, snapshot(UUID.randomUUID(), version)));
        jobs.saveSnapshot(id, snapshot);
        jobs.saveSnapshot(id, snapshot);
        assertThrows(SQLException.class, () -> jobs.saveSnapshot(id, snapshot.replace("\"marker\":1", "\"marker\":2")));
        jobs.enqueue(id, snapshot, "{\"audio\":\"first\"}");
        jobs.enqueue(id, snapshot, "{\"audio\":\"first\"}");
        assertThrows(SQLException.class, () -> jobs.enqueue(id, "{\"version\":2}", "{\"audio\":\"first\"}"));

        CountDownLatch gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        FinalizationJobStore.Lease first;
        try {
            var one = pool.submit(() -> { gate.await(); return jobs.claim(Duration.ofMinutes(1)); });
            var two = pool.submit(() -> { gate.await(); return jobs.claim(Duration.ofMinutes(1)); });
            gate.countDown();
            var a = one.get();
            var b = two.get();
            assertTrue((a == null) != (b == null));
            first = a == null ? b : a;
        } finally {
            pool.shutdownNow();
        }
        assertEquals(id, first.interviewId());
        assertFalse(jobs.complete(id, first.leaseToken()));
        String manifest = "{\"artifacts\":[{\"kind\":\"report_xlsx\",\"sizeBytes\":8,\"sha256\":\"" + "a".repeat(64) + "\"},"
                + "{\"kind\":\"transcript_md\",\"sizeBytes\":4,\"sha256\":\"" + "b".repeat(64) + "\"}]}";
        assertTrue(jobs.saveResult(id, first.leaseToken(), manifest));
        assertFalse(jobs.saveResult(id, first.leaseToken(), "{\"sha256\":\"other-bytes\"}"));
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.prepareStatement(
                     "UPDATE finalization_job SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE interview_id = ?")) {
            statement.setObject(1, id);
            statement.executeUpdate();
        }
        assertFalse(jobs.renew(id, first.leaseToken(), Duration.ofMinutes(1)));
        var second = jobs.claim(Duration.ofMinutes(1));
        assertNotNull(second);
        assertEquals(id, second.interviewId());
        assertEquals(2, second.attemptCount());
        assertTrue(second.resultManifest().contains("a".repeat(64)));
        assertFalse(jobs.complete(id, first.leaseToken()));
        assertTrue(jobs.fail(id, second.leaseToken(), Duration.ZERO, "s3_unavailable"));
        var third = jobs.claim(Duration.ofMinutes(1));
        assertNotNull(third);
        catalog.register(id, new CatalogStore.Artifact("report_xlsx",
                "interviews/" + id + "/report_xlsx", 8, "a".repeat(64)));
        assertFalse(jobs.complete(id, third.leaseToken()));
        catalog.register(id, new CatalogStore.Artifact("transcript_md",
                "interviews/" + id + "/transcript_md", 4, "b".repeat(64)));
        assertTrue(jobs.complete(id, third.leaseToken()));
        var committed = jobs.status(id);
        assertNotNull(committed);
        assertEquals("ready", committed.state()); // Обработчик может прочитать это состояние, если HTTP-подтверждение потерялось.
        var controller = new FinalizationController(jobs, "test-token-0123456789abcdef0123456789");
        var statusResponse = controller.status("test-token-0123456789abcdef0123456789", id);
        assertEquals("ready", statusResponse.getBody().state());
        assertEquals(committed.resultManifest(), statusResponse.getBody().resultManifest());
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN,
                assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> controller.status("wrong", id)).getStatusCode());
        assertFalse(jobs.complete(id, third.leaseToken()));
        assertEquals(1, catalog.find(id).artifacts().stream().filter(file -> file.kind().equals("report_xlsx")).count());
        assertNull(jobs.claim(Duration.ofMinutes(1)));
    }

    private static String snapshot(UUID id, long version) {
        return "{\"schemaVersion\":1,\"interviewId\":\"" + id + "\",\"catalogVersion\":" + version + ",\"marker\":1}";
    }
}
