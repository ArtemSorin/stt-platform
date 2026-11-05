package ru.sttplatform.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.nio.file.Path;

/**
 * Адрес аналитики и каталог результатов браузерного интерфейса.
 *
 * @param sttUrl адрес браузерного STT-сервиса
 * @param analyticsUrl адрес analytics-service
 * @param outputDir каталог журналов и таймлайна интервью
 * @param audioSpoolDir каталог временных записей для итогового Whisper
 * @version 1.0.0
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(String sttUrl, String analyticsUrl, String outputDir, String audioSpoolDir) {
    @ConstructorBinding
    public GatewayProperties {
        if (sttUrl == null || sttUrl.isBlank()) sttUrl = "http://127.0.0.1:8080";
        if (analyticsUrl == null || analyticsUrl.isBlank()) analyticsUrl = "http://127.0.0.1:8083";
        if (outputDir == null || outputDir.isBlank()) outputDir = "./runs/gateway";
        if (audioSpoolDir == null || audioSpoolDir.isBlank())
            audioSpoolDir = Path.of(System.getProperty("java.io.tmpdir"), "stt-platform", "audio").toString();
    }

    /** Совместимый конструктор с системным каталогом временных файлов.
     * @param sttUrl адрес STT
     * @param analyticsUrl адрес аналитики
     * @param outputDir каталог результатов
     */
    public GatewayProperties(String sttUrl, String analyticsUrl, String outputDir) {
        this(sttUrl, analyticsUrl, outputDir, null);
    }
}
