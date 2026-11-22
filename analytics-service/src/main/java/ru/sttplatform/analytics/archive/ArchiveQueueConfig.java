package ru.sttplatform.analytics.archive;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.sttplatform.common.archive.ArchiveQueue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/** Создаёт постоянную очередь отправки отчётов. */
@Configuration
@ConditionalOnProperty(name = "archive.enabled", havingValue = "true")
public class ArchiveQueueConfig {
    /** @param queuePath путь к очереди
     * @param endpoint S3-адрес
     * @param bucket имя бакета
     * @param access ключ
     * @param secret секрет
     * @param token внутренний токен
     * @return очередь архивации
     * @throws IOException ошибка каталога */
    @Bean(destroyMethod = "close")
    public ArchiveQueue archiveQueue(@Value("${archive.queue-dir}") String queuePath,
            @Value("${archive.s3.endpoint}") String endpoint, @Value("${archive.s3.bucket}") String bucket,
            @Value("${archive.s3.access-key}") String access, @Value("${archive.s3.secret-key}") String secret,
            @Value("${interview.events.token}") String token) throws IOException {
        return new ArchiveQueue(Path.of(queuePath), URI.create(endpoint), bucket, access, secret,
                URI.create("http://127.0.0.1:8083"), token);
    }
}
