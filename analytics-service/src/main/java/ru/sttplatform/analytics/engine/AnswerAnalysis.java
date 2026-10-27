package ru.sttplatform.analytics.engine;

/**
 * Результат разбора одного ответа (покрытие — накопительное по всем попыткам внутри вопроса).
 *
 * @param words        число слов
 * @param techWeight   суммарный IDF технических токенов
 * @param onTopicWeight из них относящихся к теме вопроса
 * @param coverage     доля (по весу) ключевых понятий эталона, уже названных, 0..1
 * @param coreCoverage то же для «ядра» понятий
 * @param termTokens   сколько раз использованы термины/сленг
 * @param glossaryIds  различные термины/сленг из словаря, использованные в ответе
 * @param newHits      ключевые понятия, добавленные этой попыткой ответа
 * @param hedgeCount   число маркеров неуверенности в ответе
 * @param ignorance    вероятность отказа от ответа по заключению модели
 * @version 1.0.0
 */
public record AnswerAnalysis(int words, double techWeight, double onTopicWeight, double coverage, double coreCoverage,
                             int newHits, int hedgeCount, boolean ignorance, int termTokens, int[] glossaryIds) {

    /** Вес оценки фактов в покрытии, когда модель дала и полноту (фоновая оценка). */
    private static final double FACTS_WEIGHT = 0.6;

    /**
     * Накладывает смысловую оценку модели на лексический разбор: покрытие и «не знаю» берутся у модели,
     * а счётчики слов, терминов и слов-паразитов остаются лексическими (их модель посчитать не может).
     *
     * @param verdict заключение модели или {@code null}
     * @return этот же разбор, если заключения нет или в нём нет оценки фактов; иначе его копия
     */
    public AnswerAnalysis withVerdict(Verdict verdict) {
        if (verdict == null || !verdict.hasFacts()) return this;
        double cov = verdict.factsCoverage();
        if (verdict.completeness() != null) cov = FACTS_WEIGHT * cov + (1 - FACTS_WEIGHT) * verdict.completeness();
        boolean ignorant = verdict.ignorance() != null ? verdict.ignorance() >= 0.5 : ignorance;
        return new AnswerAnalysis(words, techWeight, onTopicWeight, cov, cov, newHits, hedgeCount, ignorant,
                termTokens, glossaryIds);
    }
}
