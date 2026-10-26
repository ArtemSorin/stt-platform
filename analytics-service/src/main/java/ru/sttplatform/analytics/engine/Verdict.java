package ru.sttplatform.analytics.engine;

import java.util.List;

/**
 * Заключение модели (FRIDA-Decisions) по ответу кандидата. Приходит из двух запросов: быстрого — для решения
 * («уточнить или дальше»), и фонового — для оценок по критериям; {@link #mergedWith} объединяет их.
 * Все вероятности и оценки нормированы к диапазону 0..1; {@code null} — «этого вопроса модели не задавали».
 *
 * @param facts          факты эталона и вероятность того, что кандидат их сообщил
 * @param ignorance      вероятность «кандидат не знает или отказывается отвечать»
 * @param offTopic       вероятность «ответ не по теме вопроса»
 * @param followUpIndex  индексы уточняющих вопросов, среди которых модель выбирала (в порядке банка); {@code null} — не выбирала
 * @param followUpShare  доли выбора (сумма 1), согласованные с {@code followUpIndex}
 * @param completeness   полнота ответа относительно эталона (оценка 0..1)
 * @param correctness    техническая точность
 * @param terminology    владение профессиональной терминологией
 * @param source         откуда заключение: {@code frida}
 * @param latencyMs      время ответа модели
 * @param stateTruncated текст запроса не поместился в окно модели и был обрезан с конца
 * @version 1.0.0
 */
public record Verdict(List<Fact> facts, Double ignorance, Double offTopic, int[] followUpIndex, double[] followUpShare,
                      Double completeness, Double correctness, Double terminology, String source, long latencyMs,
                      boolean stateTruncated) {

    /**
     * Факт эталона и оценка того, что кандидат его назвал.
     *
     * @param text утверждение из эталона
     * @param p вероятность «сказано»
     * @version 1.0.0
     */
    public record Fact(String text, double p) {}

    /**
     * Есть ли оценка фактов.
     *
     * @return {@code true}, если модель проверяла факты эталона
     */
    public boolean hasFacts() {
        return facts != null && !facts.isEmpty();
    }

    /**
     * Средняя вероятность «сказано» по фактам эталона.
     *
     * @return доля 0..1 или 0, если фактов нет
     */
    public double factsCoverage() {
        if (!hasFacts()) return 0;
        double sum = 0;
        for (Fact f : facts) sum += f.p();
        return sum / facts.size();
    }

    /**
     * Объединяет быстрое (решение) и фоновое (оценки) заключения: непустые поля второго приоритетнее.
     *
     * @param later более позднее заключение или {@code null}
     * @return объединённое заключение
     */
    public Verdict mergedWith(Verdict later) {
        if (later == null) return this;
        return new Verdict(later.hasFacts() ? later.facts : facts,
                later.ignorance != null ? later.ignorance : ignorance,
                later.offTopic != null ? later.offTopic : offTopic,
                later.followUpIndex != null ? later.followUpIndex : followUpIndex,
                later.followUpShare != null ? later.followUpShare : followUpShare,
                later.completeness != null ? later.completeness : completeness,
                later.correctness != null ? later.correctness : correctness,
                later.terminology != null ? later.terminology : terminology,
                source, Math.max(latencyMs, later.latencyMs), stateTruncated || later.stateTruncated);
    }
}
