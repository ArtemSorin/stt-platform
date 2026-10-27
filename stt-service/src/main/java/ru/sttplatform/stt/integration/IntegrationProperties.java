package ru.sttplatform.stt.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Настройки определения ответа в STT service. */
@ConfigurationProperties(prefix = "integration")
public record IntegrationProperties(Integer endSilenceMs, Integer noSpeechTimeoutMs) {
    public IntegrationProperties {
        if (endSilenceMs == null) endSilenceMs = 450;
        if (noSpeechTimeoutMs == null) noSpeechTimeoutMs = 15_000;
    }

    /** Создаёт детектор с общими для платформы порогами. */
    public EndpointDetector newDetector() {
        EndpointDetector.Config config = new EndpointDetector.Config();
        config.baseSilenceMs = endSilenceMs;
        config.noSpeechTimeoutMs = noSpeechTimeoutMs;
        return new EndpointDetector(config);
    }
}
