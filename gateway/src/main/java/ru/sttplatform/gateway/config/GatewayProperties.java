package ru.sttplatform.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Адреса микросервисов и каталог результатов (префикс {@code gateway}).
 *
 * @param diarizationUrl адрес diarization-service
 * @param sttUrl адрес stt-service
 * @param analyticsUrl адрес analytics-service
 * @param outputDir каталог журналов и таймлайна интервью
 * @version 1.0.0
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(String diarizationUrl, String sttUrl, String analyticsUrl, String outputDir) {
    public GatewayProperties {
        if (diarizationUrl == null || diarizationUrl.isBlank()) diarizationUrl = "http://127.0.0.1:8082";
        if (sttUrl == null || sttUrl.isBlank()) sttUrl = "http://127.0.0.1:8081";
        if (analyticsUrl == null || analyticsUrl.isBlank()) analyticsUrl = "http://127.0.0.1:8083";
        if (outputDir == null || outputDir.isBlank()) outputDir = "./runs/gateway";
    }
}
