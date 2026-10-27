package ru.sttplatform.analytics.engine;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Полные данные собеседования для отчёта (Excel): вопросы, ответы, что прозвучало и не прозвучало, оценки.
 * Содержит эталонные ответы — поэтому в браузер кандидата НЕ отправляется (туда уходит только InterviewReport).
 * @param candidate имя кандидата или подпись сессии
 * @param levels уровни вопросов, заданных в сессии
 * @param startedAt время начала интервью
 * @param finishedAt время окончания интервью
 * @param finishedEarly завершено ли интервью до конца банка вопросов
 * @param plannedQuestions число запланированных вопросов
 * @param answeredQuestions число вопросов с ответами
 * @param report итоговые оценки и решения интервью
 * @param questions подробные результаты по каждому вопросу
 * @param termsUsed использованные термины из словаря
 * @param slangUsed использованные выражения из словаря
 * @param settings настройки, зафиксированные для отчёта
 * @version 1.0.0
 */
public record InterviewDetails(String candidate, String levels, Instant startedAt, Instant finishedAt,
                               boolean finishedEarly, int plannedQuestions, int answeredQuestions,
                               InterviewReport report, List<QuestionDetail> questions,
                               List<String> termsUsed, List<String> slangUsed, Map<String, String> settings) {

    /**
     * Сохраняет данные одного вопроса для итогового отчёта.
     *
     * @param number порядковый номер вопроса
     * @param level уровень сложности
     * @param topic тема вопроса
     * @param question текст вопроса
     * @param reference эталонный ответ
     * @param criteria критерии оценки
     * @param followUps уточняющие вопросы и факт их показа
     * @param attempts ответы на основной и уточняющие вопросы
     * @param concepts понятия эталона и факт их появления в ответе
     * @param coverage покрытие понятий эталона
     * @param completeness оценка полноты
     * @param technical оценка технической точности
     * @param terminology оценка терминологии
     * @param speed оценка темпа речи
     * @param termsUsed использованные термины
     * @param slangUsed использованный сленг
     * @param hedgeCount число маркеров неуверенности
     * @param words число слов в ответах
     * @param comment текстовый комментарий к результату
     * @version 1.0.0
     */
    public record QuestionDetail(int number, String level, String topic, String question, String reference,
                                 String criteria, List<FollowUpInfo> followUps, List<AttemptDetail> attempts,
                                 List<ConceptDetail> concepts, double coverage,
                                 double completeness, double technical, double terminology, double speed,
                                 List<String> termsUsed, List<String> slangUsed, int hedgeCount, int words,
                                 String comment) {}

    /**
     * Показывает, был ли задан уточняющий вопрос.
     *
     * @param text текст уточнения
     * @param asked признак фактического показа уточнения
     * @version 1.0.0
     */
    public record FollowUpInfo(String text, boolean asked) {}

    /**
     * Сохраняет сведения о попытке ответа и времени речи.
     *
     * @param followUp является ли попытка ответом на уточнение
     * @param followUpNumber номер уточнения
     * @param prompt текст вопроса, на который отвечали
     * @param answer распознанный текст ответа
     * @param answered был ли получен ответ
     * @param source источник текста, например Vosk или Whisper
     * @param words число слов в ответе
     * @param latencyMs задержка ответа в миллисекундах
     * @param speechMs длительность речи в миллисекундах
     * @param wpm темп речи, слов/мин (null — речь слишком короткая, чтобы оценить)
     * @param speedScore нормированная оценка темпа
     * @param coverageAfter покрытие понятий после этой попытки
     * @param action действие интервью после попытки
     * @param reason причина принятого действия
     * @param answeredAt время фиксации ответа
     * @version 1.0.0
     */
    public record AttemptDetail(boolean followUp, int followUpNumber, String prompt, String answer, boolean answered,
                                String source, int words, long latencyMs, long speechMs, Double wpm,
                                double speedScore, double coverageAfter, String action, String reason,
                                Instant answeredAt) {}

    /**
     * Сохраняет покрытие отдельного понятия ответа.
     *
     * @param label название понятия
     * @param weight вес понятия в оценке
     * @param core относится ли понятие к ядру эталона
     * @param said произнёс ли кандидат это понятие
     * @version 1.0.0
     */
    public record ConceptDetail(String label, double weight, boolean core, boolean said) {}
}
