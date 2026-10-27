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
 * @version 1.0.0
 */
public record Question(int id, Level level, String topic, String text, String reference, String notes,
                       List<String> followUps) {
}
