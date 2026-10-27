package ru.sttplatform.analytics.engine;

import java.util.List;

/** Итог собеседования. Все оценки — по 10-балльной шкале, итог — среднее четырёх критериев (≤ 10, 2 знака).
 * @param completeness средняя оценка полноты ответов
 * @param technical средняя оценка технической точности
 * @param terminology средняя оценка терминологии
 * @param speed средняя оценка темпа речи
 * @param total общий балл по четырём критериям
 * @param totalText текстовая интерпретация общего балла
 * @param verdict итоговое текстовое заключение
 * @param plannedQuestions число запланированных вопросов
 * @param answeredQuestions число вопросов с ответами
 * @param finishedEarly завершено ли интервью досрочно
 * @param questions результаты по каждому заданному вопросу
 * @version 1.0.0
 */
public record InterviewReport(double completeness, double technical, double terminology, double speed,
                              double total, String totalText, String verdict,
                              int plannedQuestions, int answeredQuestions, boolean finishedEarly,
                              List<QuestionResult> questions) {

    /**
     * Содержит результат одного вопроса.
     *
     * @param number порядковый номер вопроса
     * @param level уровень сложности
     * @param topic тема вопроса
     * @param question текст вопроса
     * @param attempts число попыток ответа
     * @param coveragePercent покрытие понятий эталона в процентах
     * @param completeness оценка полноты ответа
     * @param technical оценка технической точности
     * @param terminology оценка терминологии
     * @param speed оценка темпа речи
     * @param missing понятия эталона, не прозвучавшие в ответе
     * @version 1.0.0
     */
    public record QuestionResult(int number, String level, String topic, String question, int attempts,
                                 int coveragePercent, double completeness, double technical, double terminology,
                                 double speed, List<String> missing) {}
}
