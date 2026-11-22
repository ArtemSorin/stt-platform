package ru.sttplatform.analytics.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CatalogStoreTest {
    private static final String QUESTIONS = "Q1\t1\tJunior\tJava\tQuestion\tReference\tnote\n";
    private static final String FOLLOWUPS = "F1\tQ1\tFollowup\tReference\tnote\n";

    @Test
    @EnabledIfEnvironmentVariable(named = "CATALOG_TEST_JDBC_URL", matches = ".+")
    void versionBindingSurvivesPublicationAndInvalidImport() throws Exception {
        String url = System.getenv("CATALOG_TEST_JDBC_URL");
        String user = System.getenv("CATALOG_TEST_JDBC_USER");
        String password = System.getenv("CATALOG_TEST_JDBC_PASSWORD");
            CatalogStore store = new CatalogStore(url, user, password);
            long first = store.publish(QUESTIONS, FOLLOWUPS, false);
            assertEquals(first, store.publish(QUESTIONS, FOLLOWUPS, false));
            UUID oldInterview = UUID.randomUUID();
            assertEquals("Reference", store.bind(oldInterview).get(0).reference());
            var controller = new ArchiveController(store, "local-test-token-01234567890123456789");
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.find("incorrect", oldInterview));
            assertEquals(200, controller.find("local-test-token-01234567890123456789", oldInterview).getStatusCode().value());
            long second = store.publish(QUESTIONS.replace("Reference", "New reference"), FOLLOWUPS, false);
            assertNotEquals(first, second);
            assertThrows(IllegalArgumentException.class, () -> store.publish(QUESTIONS, FOLLOWUPS.replace("Q1", "missing"), false));
            assertEquals("Reference", store.bind(oldInterview).get(0).reference());
            assertEquals("New reference", store.bind(UUID.randomUUID()).get(0).reference());
            var artifact = new CatalogStore.Artifact("audio_wav", "interviews/" + oldInterview + "/audio_wav", 8, "a".repeat(64));
            store.register(oldInterview, artifact);
            store.register(oldInterview, artifact);
            assertThrows(SQLException.class, () -> store.register(oldInterview,
                    new CatalogStore.Artifact("audio_wav", artifact.objectKey(), 9, "a".repeat(64))));
            assertEquals(1, store.find(oldInterview).artifacts().size());
            try (var connection = DriverManager.getConnection(url, user, password);
                 var age = connection.prepareStatement("UPDATE archive_artifact SET created_at = CURRENT_TIMESTAMP - INTERVAL '5 days' WHERE interview_id=?")) {
                age.setObject(1, oldInterview); age.executeUpdate();
            }
            assertTrue(store.expired(-1, -1).isEmpty());
            var expired = store.expired(1, -1);
            assertTrue(expired.stream().anyMatch(item -> item.interviewId().equals(oldInterview)));
            assertTrue(store.find(oldInterview).artifacts().isEmpty());
            store.forget(expired.stream().filter(item -> item.interviewId().equals(oldInterview)).findFirst().orElseThrow());
            try (var connection = DriverManager.getConnection(url, user, password);
                 var invalid = connection.prepareStatement("INSERT INTO catalog_followup VALUES (?,?,?,?,?,?,?)")) {
                invalid.setLong(1, first); invalid.setString(2, "invalid"); invalid.setString(3, "missing");
                invalid.setInt(4, 1); invalid.setString(5, "x"); invalid.setString(6, "x"); invalid.setString(7, "x");
                assertThrows(SQLException.class, invalid::executeUpdate);
            }
    }
}
