package ru.sttplatform.gateway.session;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Сопоставляет слова итоговой транскрипции с непересекающимися интервалами ответов. */
public final class FinalAnswerAssembler {
    /** @param questionId ID основного вопроса
     * @param followUpId ID уточнения или null
     * @param attemptId ID попытки
     * @param answerId ID принятого ответа
     * @param start начало в сэмплах включительно
     * @param end конец в сэмплах исключительно */
    public record Interval(String questionId, String followUpId, long attemptId, int answerId, long start, long end) {}
    /** @param text распознанный текст
     * @param start начало в сэмплах
     * @param end конец в сэмплах
     * @param role определённая роль
     * @param source исходный канал
     * @param model источник распознавания */
    public record Word(String text, long start, long end, String role, String source, String model) {}
    /** @param interval интервал ответа
     * @param text слова кандидата
     * @param warnings предупреждения о неоднозначных участках */
    public record Answer(Interval interval, String text, List<String> warnings) {}

    /** Собирает ответы по середине слова; интервалы полуоткрытые и не должны пересекаться.
     * @param intervals интервалы вопросов и ответов
     * @param words итоговые слова с ролями и каналами
     * @param testMode единственный канал содержит кандидата
     * @return ответы по порядку и предупреждения
     * @throws IllegalArgumentException при некорректных интервалах или времени слов */
    public List<Answer> assemble(List<Interval> intervals, List<Word> words, boolean testMode) {
        List<Interval> ordered = intervals.stream().sorted(Comparator.comparingLong(Interval::start)).toList();
        long previousEnd = 0;
        var ids = new java.util.HashSet<Integer>();
        for (Interval interval : ordered) {
            if (interval.questionId() == null || interval.questionId().isBlank() || interval.start() < previousEnd
                    || interval.end() < interval.start() || interval.attemptId() < 0 || interval.answerId() <= 0
                    || !ids.add(interval.answerId())) throw new IllegalArgumentException("Некорректные интервалы ответов");
            previousEnd = interval.end();
        }
        List<Word> sortedWords = words.stream().sorted(Comparator.comparingLong(Word::start)
                .thenComparingLong(Word::end)).toList();
        List<Answer> result = new ArrayList<>();
        for (Interval interval : ordered) {
            List<String> text = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            for (Word word : sortedWords) {
                if (word.start() < 0 || word.end() < word.start()) throw new IllegalArgumentException("Некорректные таймкоды слов");
                boolean eligible = testMode ? "test".equals(word.source()) : "system".equals(word.source());
                if (!eligible) continue;
                boolean overlaps = word.start() < interval.end() && word.end() > interval.start();
                if (overlaps && ("Не определено".equals(word.role()) || "unavailable".equals(word.model()))) {
                    if (!warnings.contains("Неопределённая роль или нераспознанный участок")) warnings.add("Неопределённая роль или нераспознанный участок");
                    continue;
                }
                long midpoint = word.start() + (word.end() - word.start()) / 2;
                if (midpoint < interval.start() || midpoint >= interval.end()) continue;
                if (!"Кандидат".equals(word.role()) && !"Кандидат (резерв Vosk)".equals(word.role())) continue;
                if (word.start() < interval.start() || word.end() > interval.end())
                    if (!warnings.contains("Слово пересекает границу ответа")) warnings.add("Слово пересекает границу ответа");
                if (word.text() != null && !word.text().isBlank()) text.add(word.text().strip());
            }
            result.add(new Answer(interval, String.join(" ", text), List.copyOf(warnings)));
        }
        return List.copyOf(result);
    }
}
