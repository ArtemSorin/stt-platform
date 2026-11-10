package ru.sttplatform.gateway.session;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import ru.sttplatform.common.json.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TranscriptTimingTest {
    @TempDir Path directory;

    @Test void recordsBothChannelsAndTotalInJsonAndConsole() throws Exception {
        AtomicLong nanos = new AtomicLong();
        Logger logger = (Logger) LoggerFactory.getLogger(TranscriptTiming.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            var timing = new TranscriptTiming(directory, "session", true, false, nanos::get);
            nanos.set(5_000_000); timing.progress(0);
            nanos.set(25_000_000); timing.progress(1);
            var intermediate = Json.MAPPER.readTree(directory.resolve("transcription-timing.json").toFile());
            assertEquals(20, intermediate.path("system_ms").asLong());
            assertTrue(intermediate.path("microphone_ms").isNull());
            assertTrue(intermediate.path("total_ms").isNull());
            assertEquals("processing", intermediate.path("status").asText());

            nanos.set(65_000_000); timing.progress(2);
            nanos.set(100_000_000); timing.finish(true);
            var result = Json.MAPPER.readTree(directory.resolve("transcription-timing.json").toFile());
            assertEquals(20, result.path("system_ms").asLong());
            assertEquals(40, result.path("microphone_ms").asLong());
            assertEquals(100, result.path("total_ms").asLong());
            assertEquals("ready", result.path("status").asText());
            assertEquals(3, logs.list.size());
            assertTrue(logs.list.get(0).getFormattedMessage().contains("канал=системный звук, время=20 мс"));
            assertTrue(logs.list.get(1).getFormattedMessage().contains("канал=микрофон, время=40 мс"));
            assertTrue(logs.list.get(2).getFormattedMessage().contains("всего=100 мс, статус=ready"));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test void testAndMixedModesDoNotMislabelTheirSingleChannel() throws Exception {
        AtomicLong nanos = new AtomicLong();
        var test = new TranscriptTiming(directory, "test", false, true, nanos::get);
        test.progress(0); nanos.set(20_000_000); test.progress(1); test.finish(true);
        var result = Json.MAPPER.readTree(directory.resolve("transcription-timing.json").toFile());
        assertTrue(result.path("system_ms").isNull());
        assertEquals(20, result.path("microphone_ms").asLong());
        assertFalse(result.has("mixed_ms"));

        var mixed = new TranscriptTiming(directory, "mixed", false, false, nanos::get);
        mixed.progress(0); nanos.set(50_000_000); mixed.progress(1); mixed.finish(true);
        result = Json.MAPPER.readTree(directory.resolve("transcription-timing.json").toFile());
        assertTrue(result.path("system_ms").isNull());
        assertTrue(result.path("microphone_ms").isNull());
        assertEquals(30, result.path("mixed_ms").asLong());
    }

    @Test void failedPassKeepsCompletedChannelAndDoesNotInventAnUnfinishedDuration() throws Exception {
        AtomicLong nanos = new AtomicLong();
        var timing = new TranscriptTiming(directory, "failed", true, false, nanos::get);
        timing.progress(0); nanos.set(20_000_000); timing.progress(1);
        nanos.set(50_000_000); timing.finish(false);
        var result = Json.MAPPER.readTree(directory.resolve("transcription-timing.json").toFile());
        assertEquals(20, result.path("system_ms").asLong());
        assertTrue(result.path("microphone_ms").isNull());
        assertEquals(50, result.path("total_ms").asLong());
        assertEquals("error", result.path("status").asText());
    }

    @Test void failureToWriteTimingDoesNotInterruptProcessing() throws Exception {
        Path unavailable = Files.createFile(directory.resolve("not-a-directory"));
        var timing = new TranscriptTiming(unavailable, "session", true, false);
        assertDoesNotThrow(() -> { timing.progress(0); timing.progress(1); timing.progress(2); timing.finish(true); });
    }
}
