package ru.sttplatform.gateway.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.sttplatform.common.archive.ArchiveQueue;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ArchiveQueueConfigTest {
    @TempDir Path directory;

    @Test
    void externalBindingRequiresExplicitContainerConfiguration() {
        ArchiveQueueConfig config = new ArchiveQueueConfig();
        GatewayProperties gateway = new GatewayProperties(null, null, null);
        assertThrows(IllegalStateException.class, () -> config.archiveQueue(directory.toString(),
                "http://localhost:8333", "interviews", "key", "secret",
                "01234567890123456789012345678901", "0.0.0.0", false, gateway));
    }

    @Test
    void containerBindingAllowsPersistentQueue() throws Exception {
        ArchiveQueueConfig config = new ArchiveQueueConfig();
        GatewayProperties gateway = new GatewayProperties(null, null, null);
        try (ArchiveQueue queue = config.archiveQueue(directory.toString(),
                "http://localhost:8333", "interviews", "key", "secret",
                "01234567890123456789012345678901", "0.0.0.0", true, gateway)) {
            org.junit.jupiter.api.Assertions.assertNotNull(queue);
        }
    }
}