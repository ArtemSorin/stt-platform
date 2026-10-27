package ru.sttplatform.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Адрес аналитики и каталог результатов браузерного интерфейса.
 *
 * @param analyticsUrl адрес analytics-service
 * @param outputDir каталог журналов и таймлайна интервью
 * @version 1.0.0
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(String sttUrl, String analyticsUrl, String outputDir) {
    public GatewayProperties {
        if (sttUrl == null || sttUrl.isBlank()) sttUrl = "http://127.0.0.1:8080";
        if (analyticsUrl == null || analyticsUrl.isBlank()) analyticsUrl = "http://127.0.0.1:8083";
        if (outputDir == null || outputDir.isBlank()) outputDir = "./runs/gateway";
    }
}
