package ru.sttplatform.analytics.engine;

import java.util.concurrent.CompletableFuture;

/**
 * Смысловая оценка ответа моделью. Реализация ходит в отдельный сервис, поэтому все вызовы асинхронны,
 * а любой сбой — это исключительное завершение будущего: вызывающий код откатывается на правила.
 *
 * @version 1.0.0
 */
public interface AnswerJudge {

    /**
     * Быстрая оценка для решения «уточнить или дальше» (высокий приоритет).
     *
     * @param input вопрос и ответы
     * @return заключение с фактами, «не знаю», «не по теме» и выбором уточнения
     */
    CompletableFuture<Verdict> decide(JudgeInput input);

    /**
     * Предварительная оценка для подсказки на экране; отдаёт {@code null}, если модель занята или отключена.
     *
     * @param input вопрос и ответы
     * @return будущее заключение или {@code null}, если оценку пропустили
     */
    CompletableFuture<Verdict> preview(JudgeInput input);

    /**
     * Фоновая оценка по критериям для отчёта (низкий приоритет).
     *
     * @param input вопрос и ответы
     * @return заключение с полнотой, точностью, терминологией и фактами
     */
    CompletableFuture<Verdict> score(JudgeInput input);

    /**
     * Можно ли сейчас рассчитывать на модель (включена и не «выбита» серией сбоев).
     *
     * @return {@code true}, если вызов имеет смысл
     */
    boolean ready();
}
