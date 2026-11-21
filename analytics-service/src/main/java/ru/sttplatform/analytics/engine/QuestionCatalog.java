package ru.sttplatform.analytics.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Версионируемый банк вопросов и уточнений с идентификаторами из источника. */
public final class QuestionCatalog {
    private QuestionCatalog() {}

    /** @param id постоянный ID уточнения
     * @param questionId ID основного вопроса
     * @param text формулировка уточнения
     * @param reference эталон ответа
     * @param notes замечания для оценки */
    public record FollowUp(String id, String questionId, String text, String reference, String notes) {}
    /** @param id постоянный ID вопроса
     * @param order порядок в интервью
     * @param level уровень сложности
     * @param topic тема
     * @param text формулировка вопроса
     * @param reference эталон ответа
     * @param notes замечания для оценки
     * @param followUps варианты уточнений */
    public record Entry(String id, int order, String level, String topic, String text, String reference,
                        String notes, List<FollowUp> followUps) {}

    /** Загружает две таблицы; строки с # являются комментариями.
     * @param questions TSV основных вопросов
     * @param followUps TSV уточнений
     * @return вопросы в заданном порядке
     * @throws IllegalArgumentException при ошибках формата, связей или ID */
    public static List<Entry> parse(String questions, String followUps) {
        return parse(questions, followUps, true);
    }

    /** Загружает временный каталог до подготовки эталонов уточнений.
     * @param questions TSV основных вопросов
     * @param followUps TSV уточнений
     * @return вопросы в заданном порядке
     * @throws IllegalArgumentException при ошибках формата, связей или ID */
    public static List<Entry> parseWithoutFollowUpReferences(String questions, String followUps) {
        return parse(questions, followUps, false);
    }

    private static List<Entry> parse(String questions, String followUps, boolean requireReference) {
        Map<String, Entry> entries = new HashMap<>();
        Set<Integer> orders = new HashSet<>();
        for (String[] c : rows(questions, 7)) {
            String id = required(c[0], "questionId");
            int order;
            try { order = Integer.parseInt(c[1].strip()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Некорректный порядок вопроса " + id, e); }
            if (order < 1 || !orders.add(order)) throw new IllegalArgumentException("Повторный или некорректный порядок: " + order);
            Entry entry = new Entry(id, order, required(c[2], "уровень"), required(c[3], "тема"),
                    required(c[4], "вопрос"), required(c[5], "эталон"), c[6].strip(), new ArrayList<>());
            if (entries.putIfAbsent(id, entry) != null) throw new IllegalArgumentException("Повторный ID вопроса: " + id);
        }
        if (entries.isEmpty()) throw new IllegalArgumentException("Банк вопросов пуст");
        Set<String> ids = new HashSet<>();
        for (String[] c : rows(followUps, 5)) {
            String id = required(c[0], "followUpId"), parent = required(c[1], "questionId");
            if (entries.containsKey(id) || !ids.add(id))
                throw new IllegalArgumentException("Повторный ID вопроса или уточнения: " + id);
            Entry entry = entries.get(parent);
            if (entry == null) throw new IllegalArgumentException("Неизвестный родитель уточнения: " + parent);
            entry.followUps().add(new FollowUp(id, parent, required(c[2], "уточнение"),
                    requireReference ? required(c[3], "эталон уточнения") : c[3].strip(), c[4].strip()));
        }
        return entries.values().stream().sorted(Comparator.comparingInt(Entry::order))
                .map(e -> new Entry(e.id(), e.order(), e.level(), e.topic(), e.text(), e.reference(), e.notes(),
                        List.copyOf(e.followUps()))).toList();
    }

    /** Подготавливает каталог для движка, сохраняя внешние ID и данные уточнений.
     * @param entries загруженные записи
     * @return вопросы для построения знаний
     * @throws IllegalArgumentException при неизвестном уровне */
    public static List<Question> toQuestions(List<Entry> entries) {
        List<Question> result = new ArrayList<>();
        int internalId = 0;
        for (Entry entry : entries) {
            result.add(new Question(++internalId, Level.parse(entry.level()), entry.topic(), entry.text(),
                    entry.reference(), entry.notes(), entry.followUps().stream().map(FollowUp::text).toList(),
                    entry.id(), entry.followUps(), true));
        }
        return List.copyOf(result);
    }

    private static String required(String value, String field) {
        String result = value.strip();
        if (result.isEmpty()) throw new IllegalArgumentException("Не заполнено поле: " + field);
        return result;
    }

    private static List<String[]> rows(String input, int count) {
        if (input == null) throw new IllegalArgumentException("Таблица отсутствует");
        List<String[]> rows = new ArrayList<>();
        if (input.startsWith("\uFEFF")) input = input.substring(1);
        for (String line : input.split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] columns = line.split("\t", -1);
            if (columns.length != count) throw new IllegalArgumentException("Ожидалось колонок: " + count + ", получено: " + columns.length);
            rows.add(columns);
        }
        return rows;
    }
}
