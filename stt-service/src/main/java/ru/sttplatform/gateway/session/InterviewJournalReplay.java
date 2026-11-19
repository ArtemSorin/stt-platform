package ru.sttplatform.gateway.session;

import com.fasterxml.jackson.databind.JsonNode;
import ru.sttplatform.common.json.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Восстанавливает интервалы подтверждённых ответов из журнала одной сессии. */
public final class InterviewJournalReplay {
    private record Ready(String question, String followUp, long start) {}

    /** Восстанавливает только неотменённые решения из завершённого журнала.
     * @param path путь JSONL-файла
     * @param sessionId ожидаемый ID сессии
     * @param maxSamples длина записанного аудио в сэмплах
     * @return подтверждённые интервалы ответов
     * @throws IOException при повреждении журнала или ошибке чтения */
    public List<FinalAnswerAssembler.Interval> read(Path path, String sessionId, long maxSamples) throws IOException {
        Map<Long, Ready> ready = new HashMap<>();
        Map<Integer, FinalAnswerAssembler.Interval> answers = new LinkedHashMap<>();
        java.util.Set<Integer> seenAnswerIds = new java.util.HashSet<>();
        Ready skipped = null;
        long sequence = 0;
        boolean complete = false;
        try (var lines = Files.lines(path)) {
            for (var iterator = lines.iterator(); iterator.hasNext();) {
                JsonNode event = Json.tree(iterator.next());
                if (event == null || !event.isObject() || !event.path("eventSequence").isIntegralNumber()
                        || !sessionId.equals(event.path("sessionId").asText()) || event.path("eventSequence").asLong(-1) != sequence++)
                    throw new IOException("Нарушена последовательность событий журнала");
                long sample = event.path("audioSample").asLong(-1);
                long attempt = event.path("attemptId").asLong(-1);
                if (!event.path("audioSample").isIntegralNumber() || !event.path("attemptId").isIntegralNumber()
                        || sample < 0 || sample > maxSamples || attempt < 0) throw new IOException("Неверная позиция или номер попытки");
                if (complete) throw new IOException("События после маркера завершения журнала");
                String type = event.path("type").asText();
                if ("journal_complete".equals(type)) {
                    if (sample != maxSamples) throw new IOException("Маркёр завершения не совпадает с длиной записи");
                    complete = true;
                    continue;
                }
                String question = event.path("questionId").asText("");
                String followUp = event.hasNonNull("followUpId") ? event.path("followUpId").asText() : null;
                if ("ready".equals(type)) {
                    if (question.isBlank() || attempt < 1 || ready.putIfAbsent(attempt, new Ready(question, followUp, sample)) != null)
                        throw new IOException("Некорректное начало ответа");
                } else if ("skip".equals(type)) {
                    if (question.isBlank()) throw new IOException("Пропуск без вопроса");
                    skipped = new Ready(question, followUp, sample);
                } else if ("decision".equals(type)) {
                    int id = event.path("answerId").asInt(-1);
                    Ready start = ready.get(attempt);
                    if (skipped != null && skipped.question().equals(question)
                            && java.util.Objects.equals(skipped.followUp(), followUp)) { start = skipped; skipped = null; }
                    if (id < 1 || start == null || !start.question().equals(question)
                            || !java.util.Objects.equals(start.followUp(), followUp) || sample < start.start())
                        throw new IOException("Решение не соответствует началу ответа");
                    var interval = new FinalAnswerAssembler.Interval(question, followUp, attempt, id, start.start(), sample);
                    if (!seenAnswerIds.add(id)) throw new IOException("Повторный answerId");
                    answers.put(id, interval);
                } else if ("retract".equals(type)) {
                    int id = event.path("answerId").asInt(-1);
                    var prior = answers.get(id);
                    if (prior == null || prior.attemptId() != attempt)
                        throw new IOException("Отмена неизвестного ответа или чужой попытки");
                    answers.remove(id);
                }
            }
        } catch (java.io.UncheckedIOException error) { throw error.getCause(); }
        if (!complete) throw new IOException("Журнал интервью не завершён");
        return List.copyOf(new ArrayList<>(answers.values()));
    }
}
