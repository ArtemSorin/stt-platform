package ru.sttplatform.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.sttplatform.common.archive.ArchiveQueue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/** Создаёт локальную постоянную очередь архивации интервью. */
@Configuration
@ConditionalOnProperty(name = "archive.enabled", havingValue = "true")
public class ArchiveQueueConfig {
    /** @param queuePath путь к очереди
     * @param endpoint S3-адрес
     * @param bucket имя бакета
     * @param access ключ
     * @param secret секрет
     * @param token внутренний токен
     * @param address адрес API браузера
     * @param containerBinding разрешает привязку внутри контейнерной сети
     * @param gateway настройки аналитики
     * @return очередь архивации
     * @throws IOException ошибка каталога */
    @Bean(destroyMethod = "close")
    public ArchiveQueue archiveQueue(@Value("${archive.queue-dir}") String queuePath,
            @Value("${archive.s3.endpoint}") String endpoint, @Value("${archive.s3.bucket}") String bucket,
            @Value("${archive.s3.access-key}") String access, @Value("${archive.s3.secret-key}") String secret,
            @Value("${INTERVIEW_EVENTS_TOKEN}") String token, @Value("${server.address}") String address,
            @Value("${archive.container-binding:false}") boolean containerBinding,
            GatewayProperties gateway) throws IOException {
        if (!containerBinding && !"127.0.0.1".equals(address) && !"::1".equals(address) && !"localhost".equalsIgnoreCase(address))
            throw new IllegalStateException("Пилотный архив разрешает только локальный API браузера");
        return new ArchiveQueue(Path.of(queuePath), URI.create(endpoint), bucket, access, secret,
                URI.create(gateway.analyticsUrl()), token);
    }
}
