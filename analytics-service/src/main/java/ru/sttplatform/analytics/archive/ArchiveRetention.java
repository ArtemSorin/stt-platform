package ru.sttplatform.analytics.archive;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.sttplatform.common.archive.ArchiveQueue;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Удаляет просроченные файлы с повтором незавершённой операции. */
@Component
@ConditionalOnProperty(name = "archive.enabled", havingValue = "true")
public class ArchiveRetention {
    private static final Logger LOG = LoggerFactory.getLogger(ArchiveRetention.class);
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "archive-retention"); thread.setDaemon(true); return thread;
    });

    /** @param store реестр
     * @param queue доступ к объектам
     * @param audioDays срок для аудио или -1
     * @param reportDays срок для XLSX или -1 */
    public ArchiveRetention(CatalogStore store, ArchiveQueue queue,
                            @Value("${archive.retention.audio-days:-1}") int audioDays,
                            @Value("${archive.retention.report-days:-1}") int reportDays) {
        if ((audioDays != -1 && audioDays < 1) || (reportDays != -1 && reportDays < 1))
            throw new IllegalArgumentException("Срок хранения должен быть -1 или положительным числом дней");
        executor.scheduleWithFixedDelay(() -> {
            try {
                for (var artifact : store.expired(audioDays, reportDays)) {
                    queue.delete(artifact.interviewId(), artifact.kind());
                    store.forget(artifact);
                }
            } catch (Exception error) { LOG.warn("Удаление просроченных файлов отложено: {}", error.toString()); }
        }, 60, 3600, TimeUnit.SECONDS);
    }

    /** Останавливает фоновую очистку. */
    @PreDestroy public void close() { executor.shutdownNow(); }
}
