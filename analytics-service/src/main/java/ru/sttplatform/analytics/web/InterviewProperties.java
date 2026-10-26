package ru.sttplatform.analytics.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки analytics-service (префикс {@code interview}).
 * <ul>
 *   <li>{@code questions-path} / {@code glossary-path} / {@code spoken-path} — внешние файлы вместо встроенных;</li>
 *   <li>{@code stt-url} — адрес микросервиса stt-service (Vosk и Whisper);</li>
 *   <li>{@code whisper-enabled} — использовать ли второй проход Whisper через stt-service;</li>
 *   <li>{@code reports-dir}, {@code report-download} — куда сохранять Excel и давать ли скачать;</li>
 *   <li>{@code end-silence-ms}, {@code next-extra-ms}, {@code other-next-extra-ms}, {@code follow-up-extra-ms},
 *       {@code grace-ms} — тайминги решения (см. {@code LiveInterview}).</li>
 * </ul>
 *
 * @version 2.0.0
 */
@ConfigurationProperties(prefix = "interview")
public record InterviewProperties(String questionsPath, String glossaryPath, String spokenPath, Integer maxFollowUps,
                                  Integer endSilenceMs, Integer noSpeechTimeoutMs, Double weakCoverage,
                                  String sttUrl, Integer sttFlushTimeoutMs,
                                  Boolean whisperEnabled, Integer whisperWaitMs,
                                  String reportsDir, Boolean reportDownload,
                                  Integer nextExtraMs, Integer otherNextExtraMs, Integer followUpExtraMs, Integer graceMs) {
    public InterviewProperties {
        if (maxFollowUps == null) maxFollowUps = 2;
        if (endSilenceMs == null) endSilenceMs = 450;
        if (noSpeechTimeoutMs == null) noSpeechTimeoutMs = 15000;
        if (weakCoverage == null) weakCoverage = 0.08;
        if (sttUrl == null || sttUrl.isBlank()) sttUrl = "http://127.0.0.1:8081";
        if (sttFlushTimeoutMs == null) sttFlushTimeoutMs = 1500;
        if (whisperEnabled == null) whisperEnabled = true;
        if (whisperWaitMs == null) whisperWaitMs = 25000;
        if (reportsDir == null || reportsDir.isBlank()) reportsDir = "./reports";
        if (reportDownload == null) reportDownload = true;
        if (nextExtraMs == null) nextExtraMs = 150;
        if (otherNextExtraMs == null) otherNextExtraMs = 600;
        if (followUpExtraMs == null) followUpExtraMs = 1500;
        if (graceMs == null) graceMs = 1000;
    }
}
