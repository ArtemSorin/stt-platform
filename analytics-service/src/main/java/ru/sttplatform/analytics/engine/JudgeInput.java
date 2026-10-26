package ru.sttplatform.analytics.engine;

import java.util.List;

/**
 * Всё, что нужно модели, чтобы оценить ответ: вопрос, эталон, факты, ответы кандидата в этом вопросе
 * (основной и на уточнения) и ещё не заданные уточняющие вопросы.
 *
 * @param level     уровень вопроса
 * @param topic     тема
 * @param question  основной вопрос
 * @param reference эталонный ответ
 * @param notes     пометка проверяющего (на что обратить внимание)
 * @param facts     утверждения эталона ({@link Facts#split})
 * @param turns     реплики вопроса по порядку; последняя — оцениваемый ответ
 * @param followUps ещё не заданные уточняющие вопросы
 * @version 1.0.0
 */
public record JudgeInput(String level, String topic, String question, String reference, String notes,
                         List<String> facts, List<Turn> turns, List<FollowUpOption> followUps) {

    /**
     * Одна реплика: вопрос интервьюера и ответ кандидата.
     *
     * @param prompt вопрос (основной или уточняющий)
     * @param answer ответ кандидата
     * @version 1.0.0
     */
    public record Turn(String prompt, String answer) {}

    /**
     * Ещё не заданное уточнение.
     *
     * @param index индекс в списке уточнений вопроса
     * @param text текст уточнения
     * @version 1.0.0
     */
    public record FollowUpOption(int index, String text) {}

    /**
     * Вход для проверки модели без интервью: один ответ на основной вопрос, все уточнения ещё не заданы.
     *
     * @param cq скомпилированный вопрос
     * @param answer текст ответа
     * @return данные для модели
     */
    public static JudgeInput single(CompiledQuestion cq, String answer) {
        Question q = cq.question();
        java.util.List<FollowUpOption> options = new java.util.ArrayList<>();
        for (int j = 0; j < cq.followUps().length; j++) options.add(new FollowUpOption(j, cq.followUps()[j].text()));
        return new JudgeInput(cq.level().title, q.topic(), q.text(), q.reference(), q.notes(),
                Facts.split(q.reference()), List.of(new Turn(q.text(), answer)), options);
    }
}
