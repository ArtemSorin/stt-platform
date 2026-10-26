package ru.sttplatform.analytics.judge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки смысловой оценки ответов моделью FRIDA-Decisions (префикс {@code interview.judge}).
 *
 * @param enabled            включена ли оценка моделью; выключена — работают правила на ключевых словах
 * @param url                адрес сервиса модели ({@code judge/frida_server.py} или vLLM)
 * @param protocol           {@code sidecar} (POST /judge) или {@code vllm} (POST /pooling)
 * @param model              имя модели для протокола vllm
 * @param requestTimeoutMs   таймаут HTTP-запроса быстрой оценки
 * @param scoreTimeoutMs     таймаут HTTP-запроса фоновой оценки
 * @param decisionDeadlineMs сколько ждать модель после конца ответа, прежде чем решить по правилам
 * @param maxStateChars      бюджет текста запроса в символах (окно модели — 512 токенов)
 * @param previewEnabled     считать ли модель для подсказки на экране
 * @param breakerFailures    сколько сбоев подряд «выбивают» модель
 * @param breakerPauseMs     на сколько «выбивать»
 * @param cacheSize          сколько последних заключений помнить
 * @param ignoranceP         порог вероятности «не знаю»
 * @param ignoranceMaxCoverage порог покрытия для «не знаю»
 * @param offTopicP          порог вероятности «не по теме»
 * @param offTopicMaxCoverage порог покрытия для «не по теме»
 * @param stagnationDelta    минимальный прирост покрытия после уточнения
 * @param sufficientShift    сдвиг порога «ответ достаточен» относительно уровня вопроса
 * @version 1.0.0
 */
@ConfigurationProperties(prefix = "interview.judge")
public record JudgeProperties(Boolean enabled, String url, String protocol, String model,
                              Integer requestTimeoutMs, Integer scoreTimeoutMs, Integer decisionDeadlineMs,
                              Integer maxStateChars, Boolean previewEnabled,
                              Integer breakerFailures, Integer breakerPauseMs, Integer cacheSize,
                              Double ignoranceP, Double ignoranceMaxCoverage, Double offTopicP,
                              Double offTopicMaxCoverage, Double stagnationDelta, Double sufficientShift) {
    public JudgeProperties {
        if (enabled == null) enabled = true;
        if (url == null || url.isBlank()) url = "http://127.0.0.1:9100";
        if (protocol == null || protocol.isBlank()) protocol = "sidecar";
        if (!protocol.equals("sidecar") && !protocol.equals("vllm"))
            throw new IllegalArgumentException("interview.judge.protocol: sidecar или vllm, а не " + protocol);
        if (model == null || model.isBlank()) model = "ai-forever/FRIDA-Decisions";
        if (requestTimeoutMs == null) requestTimeoutMs = 4000;
        if (scoreTimeoutMs == null) scoreTimeoutMs = 30000;
        if (decisionDeadlineMs == null) decisionDeadlineMs = 1200;
        if (maxStateChars == null) maxStateChars = 1800;
        if (previewEnabled == null) previewEnabled = true;
        if (breakerFailures == null) breakerFailures = 3;
        if (breakerPauseMs == null) breakerPauseMs = 15000;
        if (cacheSize == null) cacheSize = 128;
        if (ignoranceP == null) ignoranceP = 0.6;
        if (ignoranceMaxCoverage == null) ignoranceMaxCoverage = 0.25;
        if (offTopicP == null) offTopicP = 0.6;
        if (offTopicMaxCoverage == null) offTopicMaxCoverage = 0.20;
        if (stagnationDelta == null) stagnationDelta = 0.05;
        if (sufficientShift == null) sufficientShift = 0.10;
    }
}
