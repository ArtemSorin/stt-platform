package ru.sttplatform.analytics.engine;

import java.util.List;

/**
 * Хранит вопрос, эталон и варианты уточнений.
 *
 * @param id идентификатор вопроса в банке
 * @param level уровень сложности
 * @param topic тема вопроса
 * @param text формулировка вопроса
 * @param reference эталонный ответ
 * @param notes заметки для оценки
 * @param followUps варианты уточняющих вопросов
 * @param stableId постоянный ID вопроса
 * @param followUpDetails ID и эталоны уточнений нового каталога
 * @param explicitOrder задан ли порядок в новом каталоге
 * @version 1.0.0
 */
public record Question(int id, Level level, String topic, String text, String reference, String notes,
                       List<String> followUps, String stableId, List<QuestionCatalog.FollowUp> followUpDetails, boolean explicitOrder) {
    public Question(int id, Level level, String topic, String text, String reference, String notes,
                    List<String> followUps) {
        this(id, level, topic, text, reference, notes, List.copyOf(followUps), "legacy-" + id, List.of(), false);
    }

    public Question {
        followUps = List.copyOf(followUps);
        followUpDetails = List.copyOf(followUpDetails);
        if (stableId == null || stableId.isBlank()) throw new IllegalArgumentException("Пустой ID вопроса");
        if (!followUpDetails.isEmpty() && followUpDetails.size() != followUps.size())
            throw new IllegalArgumentException("Неверное число уточнений");
        for (int i = 0; i < followUpDetails.size(); i++) {
            QuestionCatalog.FollowUp detail = followUpDetails.get(i);
            if (!stableId.equals(detail.questionId()) || !followUps.get(i).equals(detail.text()))
                throw new IllegalArgumentException("Уточнение не соответствует вопросу или его тексту");
        }
    }
}
