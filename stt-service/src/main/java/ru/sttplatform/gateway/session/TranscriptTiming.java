package ru.sttplatform.gateway.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.sttplatform.common.json.Json;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Замеряет итоговое распознавание каналов и время до публикации транскрипции. */
final class TranscriptTiming {
    private static final Logger log = LoggerFactory.getLogger(TranscriptTiming.class);

    private final Path file;
    private final String sessionId;
    private final boolean separateChannels, testMode;
    private final LongSupplier clock;
    private final long started;
    private long channelStarted;
    private Long systemMs, microphoneMs, mixedMs;

    TranscriptTiming(Path directory, String sessionId, boolean separateChannels, boolean testMode) {
        this(directory, sessionId, separateChannels, testMode, System::nanoTime);
    }

    TranscriptTiming(Path directory, String sessionId, boolean separateChannels, boolean testMode, LongSupplier clock) {
        this.file = directory.resolve("transcription-timing.json");
        this.sessionId = sessionId;
        this.separateChannels = separateChannels;
        this.testMode = testMode;
        this.clock = clock;
        this.started = clock.getAsLong();
    }

    void progress(int done) {
        if (done == 0) {
            channelStarted = clock.getAsLong();
            return;
        }
        long elapsed = elapsedMillis(channelStarted);
        String channel;
        if (testMode || (separateChannels && done == 2)) {
            microphoneMs = elapsed;
            channel = "микрофон";
        } else if (separateChannels) {
            systemMs = elapsed;
            channel = "системный звук";
        } else {
            mixedMs = elapsed;
            channel = "смешанный звук";
        }
        log.info("Итоговая транскрипция: сессия={}, канал={}, время={} мс", sessionId, channel, elapsed);
        write("processing", null);
        channelStarted = clock.getAsLong();
    }

    void finish(boolean ready) {
        long total = elapsedMillis(started);
        String status = ready ? "ready" : "error";
        log.info("Итоговая транскрипция: сессия={}, всего={} мс, статус={}", sessionId, total, status);
        write(status, total);
    }

    private long elapsedMillis(long from) {
        return TimeUnit.NANOSECONDS.toMillis(clock.getAsLong() - from);
    }

    private void write(String status, Long total) {
        Map<String, Object> values = Json.map("system_ms", systemMs, "microphone_ms", microphoneMs);
        if (!separateChannels && !testMode) values.put("mixed_ms", mixedMs);
        values.put("total_ms", total);
        values.put("status", status);
        try {
            Out.writePretty(file, values);
        } catch (Exception error) {
            log.warn("Не удалось записать замеры транскрипции в {}: {}", file, error.toString());
        }
    }
}
