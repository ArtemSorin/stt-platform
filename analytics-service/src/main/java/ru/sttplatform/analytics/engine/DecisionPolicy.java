package ru.sttplatform.analytics.engine;

/**
 * Решение «уточнить или идти дальше». Работает только с уже посчитанными числами — O(число уточняющих вопросов).
 * @version 1.0.0
 */
public final class DecisionPolicy {

    /**
     * Хранит действие по ответу и причину выбора.
     *
     * @param followUp нужно ли задать уточняющий вопрос
     * @param reason причина принятого решения
     * @param followUpIndex индекс уточнения или {@code -1}, если оно не выбрано
     * @version 1.0.0
     */
    public record Decision(boolean followUp, String reason, int followUpIndex) {}

    /**
     * Пороги решений по заключению модели. Стартовые значения нужно калибровать на своих ответах
     * ({@code GET /api/judge/selfcheck}), поэтому все они настраиваются.
     *
     * @param ignoranceP        с какой вероятности «не знаю» считается отказом
     * @param ignoranceMaxCov   «не знаю» учитывается, только если покрытие ниже этого значения
     * @param offTopicP         с какой вероятности ответ считается не по теме
     * @param offTopicMaxCov    «не по теме» учитывается, только если покрытие ниже этого значения
     * @param stagnationDelta   на сколько должно вырасти покрытие после уточнения, чтобы спрашивать дальше
     * @param sufficientShift   сдвиг порога «достаточно» относительно {@link Level#sufficientCoverage}
     * @version 1.0.0
     */
    public record Thresholds(double ignoranceP, double ignoranceMaxCov, double offTopicP, double offTopicMaxCov,
                             double stagnationDelta, double sufficientShift) {
        /**
         * Значения по умолчанию.
         *
         * @return пороги
         */
        public static Thresholds defaults() {
            return new Thresholds(0.6, 0.25, 0.6, 0.20, 0.05, 0.10);
        }
    }

    private final int maxFollowUps;
    private final double weakCoverage;
    private final Thresholds thresholds;

    /**
     * Создаёт объект с указанными зависимостями и настройками.
     *
     * @param maxFollowUps параметр операции
     * @param weakCoverage параметр операции
     */
    public DecisionPolicy(int maxFollowUps, double weakCoverage) {
        this(maxFollowUps, weakCoverage, Thresholds.defaults());
    }

    /**
     * Создаёт политику с настраиваемыми порогами для решений по заключению модели.
     *
     * @param maxFollowUps максимум уточняющих вопросов на вопрос
     * @param weakCoverage порог «слабого» покрытия для правил на ключевых словах (запасной путь)
     * @param thresholds пороги для заключения модели
     */
    public DecisionPolicy(int maxFollowUps, double weakCoverage, Thresholds thresholds) {
        this.maxFollowUps = maxFollowUps;
        this.weakCoverage = weakCoverage;
        this.thresholds = thresholds;
    }

    /**
     * Решение по смысловому заключению модели. Покрытие в {@code a} уже взято у модели ({@link AnswerAnalysis#withVerdict}).
     * Без заключения (или без оценки фактов) работают прежние правила на ключевых словах.
     *
     * @param q скомпилированный вопрос
     * @param level уровень вопроса
     * @param a разбор ответа с наложенным заключением
     * @param prevCoverage покрытие предыдущей попытки
     * @param followUpsUsed сколько уточнений уже задано
     * @param asked какие уточнения уже заданы
     * @param hits лексические попадания (для запасного пути)
     * @param verdict заключение модели или {@code null}
     * @return выбранное действие и причина
     */
    public Decision decide(CompiledQuestion q, Level level, AnswerAnalysis a, double prevCoverage,
                           int followUpsUsed, boolean[] asked, boolean[] hits, Verdict verdict) {
        if (verdict == null || !verdict.hasFacts()) {
            return decide(q, level, a, prevCoverage, followUpsUsed, asked, hits);
        }
        if (a.words() == 0) return next("no_answer");
        double cov = a.coverage();
        double enough = Math.min(0.95, level.sufficientCoverage + thresholds.sufficientShift());
        if (verdict.ignorance() != null && verdict.ignorance() >= thresholds.ignoranceP()
                && cov < thresholds.ignoranceMaxCov()) {
            return next("ignorance");               // «не знаю» — не давим
        }
        if (verdict.offTopic() != null && verdict.offTopic() >= thresholds.offTopicP()
                && cov < thresholds.offTopicMaxCov()) {
            return next("off_topic");               // говорит про другое — уточнять нечего
        }
        if (cov >= enough) return next("sufficient");
        if (followUpsUsed >= maxFollowUps) return next("follow_up_budget");
        if (followUpsUsed >= 1 && cov - prevCoverage < thresholds.stagnationDelta()) return next("stagnation");
        int best = pickFollowUp(q, asked, hits, verdict);
        if (best < 0) return next("no_follow_ups");
        return new Decision(true, "needs_detail", best);
    }

    /** Уточнение выбирает модель; если она не выбирала, — прежнее правило «бить в ещё не названное». */
    private int pickFollowUp(CompiledQuestion q, boolean[] asked, boolean[] hits, Verdict verdict) {
        if (verdict.followUpIndex() != null && verdict.followUpShare() != null) {
            int best = -1;
            double bestShare = -1;
            for (int i = 0; i < verdict.followUpIndex().length; i++) {
                int idx = verdict.followUpIndex()[i];
                if (idx < 0 || idx >= asked.length || asked[idx]) continue;
                if (verdict.followUpShare()[i] > bestShare) {
                    bestShare = verdict.followUpShare()[i];
                    best = idx;
                }
            }
            return best;
        }
        int best = -1;
        double bestScore = -1;
        CompiledQuestion.FollowUp[] fus = q.followUps();
        for (int j = 0; j < fus.length; j++) {
            if (asked[j]) continue;
            double s = 0;
            for (int p : fus[j].poolIdx()) if (!hits[p]) s += q.poolW()[p];
            if (s > bestScore) {
                bestScore = s;
                best = j;
            }
        }
        return best;
    }

    /**
     * Выбирает продолжение интервью по покрытию эталона и лимиту уточнений.
     *
     * @param q скомпилированный вопрос
     * @param level уровень вопроса
     * @param a анализ ответа
     * @param prevCoverage покрытие предыдущей попытки
     * @param followUpsUsed параметр операции
     * @param asked параметр операции
     * @param hits параметр операции
     * @return выбранное действие и его причина
     */
    public Decision decide(CompiledQuestion q, Level level, AnswerAnalysis a, double prevCoverage,
                           int followUpsUsed, boolean[] asked, boolean[] hits) {
        if (a.words() == 0) {
            return next("no_answer");
        }
        if (a.ignorance() && a.coverage() < weakCoverage) {
            return next("ignorance");               // «не знаю» — не давим
        }
        if (a.coverage() >= level.sufficientCoverage) {
            return next("sufficient");              // ключевые понятия названы
        }
        if (followUpsUsed >= maxFollowUps) {
            return next("follow_up_budget");
        }
        double focus = a.techWeight() > 0 ? a.onTopicWeight() / a.techWeight() : 1.0;
        if (a.coverage() < weakCoverage && focus < 0.25) {
            return next("off_topic");               // уверенно говорит, но про другое — уточнять нечего
        }
        if (a.coverage() < weakCoverage && a.words() < 6) {
            return next("no_signal");               // почти ничего не сказано
        }
        if (followUpsUsed >= 1 && a.coverage() - prevCoverage < 0.03) {
            return next("stagnation");              // уточнение ничего не добавило
        }
        int best = -1;
        double bestScore = -1;
        CompiledQuestion.FollowUp[] fus = q.followUps();
        for (int j = 0; j < fus.length; j++) {
            if (asked[j]) continue;
            double s = 0;
            for (int p : fus[j].poolIdx()) {
                if (!hits[p]) s += q.poolW()[p];    // уточнение должно бить в то, что ещё не названо
            }
            if (s > bestScore) {
                bestScore = s;
                best = j;
            }
        }
        if (best < 0) {
            return next("no_follow_ups");
        }
        return new Decision(true, "needs_detail", best);
    }

    private static Decision next(String reason) {
        return new Decision(false, reason, -1);
    }
}
