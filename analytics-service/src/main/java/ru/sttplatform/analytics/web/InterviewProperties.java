package ru.sttplatform.analytics.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки analytics-service (префикс {@code interview}).
 * <ul>
 *   <li>{@code questions-path} / {@code glossary-path} / {@code spoken-path} — внешние файлы вместо встроенных;</li>
 *   <li>{@code whisper-enabled} — разрешён ли второй проход Whisper, выполняемый STT-сервисом;</li>
 *   <li>{@code reports-dir}, {@code report-download} — куда сохранять Excel и давать ли скачать;</li>
 *   <li>{@code end-silence-ms}, {@code next-extra-ms}, {@code other-next-extra-ms}, {@code follow-up-extra-ms},
 *       {@code grace-ms} — тайминги решения (см. {@code LiveInterview}).</li>
 * </ul>
 *
 * @param questionsPath путь к внешнему файлу банка вопросов
 * @param glossaryPath путь к внешнему словарю терминов
 * @param spokenPath путь к файлу настроек произношения
 * @param maxFollowUps максимум уточнений на один вопрос
 * @param endSilenceMs длительность тишины для фиксации конца ответа
 * @param noSpeechTimeoutMs время ожидания речи после вопроса
 * @param weakCoverage порог слабого покрытия ответа
 * @param whisperEnabled разрешён ли второй проход Whisper в STT
 * @param whisperWaitMs время ожидания уточнённого текста
 * @param reportsDir каталог файлов отчётов
 * @param reportDownload разрешено ли скачивание отчётов
 * @param nextExtraMs дополнительная задержка перед следующим вопросом
 * @param otherNextExtraMs дополнительная задержка для отдельного сценария перехода
 * @param followUpExtraMs задержка перед уточняющим вопросом
 * @param graceMs окно, в котором продолжение речи отменяет предварительное решение
 * @version 1.0.0
 */
@ConfigurationProperties(prefix = "interview")
public record InterviewProperties(String questionsPath, String glossaryPath, String spokenPath, Integer maxFollowUps,
                                  Integer endSilenceMs, Integer noSpeechTimeoutMs, Double weakCoverage,
                                  Boolean whisperEnabled, Integer whisperWaitMs,
                                  String reportsDir, Boolean reportDownload,
                                  Integer nextExtraMs, Integer otherNextExtraMs, Integer followUpExtraMs, Integer graceMs) {
    public InterviewProperties {
        if (maxFollowUps == null) maxFollowUps = 2;
        if (endSilenceMs == null) endSilenceMs = 450;
        if (noSpeechTimeoutMs == null) noSpeechTimeoutMs = 15000;
        if (weakCoverage == null) weakCoverage = 0.08;
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
