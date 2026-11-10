package ru.sttplatform.stt.transcript;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.stt.whisper.WhisperClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;

/** Распознаёт записанные каналы с VAD и повторяет непокрытые признаки речи без фильтра. */
public final class PostInterviewWhisper {
    private static final Logger log = LoggerFactory.getLogger(PostInterviewWhisper.class);
    private static final int RATE = 16_000;
    private static final long MIN_RETRY = 12L * RATE;
    private static final long CONTEXT = RATE / 2L;
    private static final String TRANSCRIBE_PATH = "/transcribe-json";
    private static final String TRANSCRIBE_VAD_PATH = "/transcribe-json-vad";
    private final InterviewAudioSpool audio;

    /** @param audio закрытая запись интервью */
    public PostInterviewWhisper(InterviewAudioSpool audio) { this.audio = audio; }

    /** Обрабатывает каналы с VAD и повторяет непокрытые признаки речи без фильтра.
     * @param evidence признаки речи из Vosk и диаризации
     * @param client локальный Whisper
     * @param progress число завершённых каналов и их общее число
     * @return слова, обработанные участки и нераспознанные интервалы
     * @throws IOException при ошибке чтения исходной записи
     * @throws InterruptedException если запрос прерван
     */
    public Result transcribe(List<SpeechEvidence> evidence, WhisperClient client,
                             BiConsumer<Integer, Integer> progress) throws IOException, InterruptedException {
        List<Word> words = new ArrayList<>();
        List<Chunk> chunks = new ArrayList<>();
        List<Gap> gaps = new ArrayList<>();
        progress.accept(0, audio.channels());
        for (int channel = 0; channel < audio.channels(); channel++) {
            if (audio.samples() > 0) {
                process(channel, 0, audio.samples(), evidence, client, words, chunks, gaps, true);
                for (Interval missing : uncoveredEvidence(channel, evidence, words)) {
                    log.warn("Whisper VAD не покрыл признаки речи в канале {} на участке {}–{} с; повтор без VAD",
                            channel, missing.start() / (double) RATE, missing.end() / (double) RATE);
                    process(channel, missing.start(), missing.end(), evidence, client, words, chunks, gaps, false);
                }
                addUnresolvedEvidenceGaps(channel, evidence, words, gaps);
            }
            progress.accept(channel + 1, audio.channels());
        }
        sortAndDeduplicate(words);
        return new Result(List.copyOf(words), List.copyOf(chunks), coalesceGaps(gaps));
    }

    private void process(int channel, long start, long end, List<SpeechEvidence> evidence, WhisperClient client,
                         List<Word> words, List<Chunk> chunks, List<Gap> gaps, boolean vadFilter)
            throws IOException, InterruptedException {
        long readStart = Math.max(0, start - CONTEXT), readEnd = Math.min(audio.samples(), end + CONTEXT);
        boolean speech = evidence.stream().anyMatch(s -> s.channel() == channel
                && Math.min(end, s.end()) - Math.max(start, s.start()) >= s.minimumOverlap());
        IOException failure = null;
        List<Word> found = null;
        boolean disconnected = false;
        int index = chunks.size();
        byte[] pcm = audio.read(channel, readStart, readEnd);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                WhisperClient.Reply reply = client.post(vadFilter ? TRANSCRIBE_VAD_PATH : TRANSCRIBE_PATH, pcm);
                if (reply.status() != 200) throw new IOException("Whisper ответил HTTP " + reply.status());
                found = parse(reply.body(), channel, index, readStart, readEnd, start, end);
                if (found.isEmpty() && speech && !vadFilter)
                    throw new IOException("Whisper не распознал участок с признаками речи");
                failure = null;
                break;
            } catch (IOException | IllegalArgumentException error) {
                failure = new IOException("Канал " + channel + " [" + start / (double) RATE + "–"
                        + end / (double) RATE + " с]: " + error.getMessage(), error);
                if (error instanceof java.net.ConnectException) { disconnected = true; break; }
            }
        }
        if (failure != null) {
            if (!disconnected && end - start > MIN_RETRY) {
                long middle = start + (end - start) / 2;
                log.warn("Whisper не обработал канал {} на участке {}–{} с; повтор по частям", channel, start / (double) RATE, end / (double) RATE);
                process(channel, start, middle, evidence, client, words, chunks, gaps, vadFilter);
                process(channel, middle, end, evidence, client, words, chunks, gaps, vadFilter);
            } else {
                log.error("Whisper не обработал канал {} на участке {}–{} с; используется резерв", channel, start / (double) RATE, end / (double) RATE, failure);
                gaps.add(new Gap(channel, start, end));
                chunks.add(new Chunk(channel, start, end, 0));
            }
            return;
        }
        for (Word word : found) {
            int previous = words.size() - 1;
            if (previous >= 0 && duplicate(words.get(previous), word)) {
                Word old = words.get(previous);
                if (word.confidence() != null && (old.confidence() == null || word.confidence() > old.confidence())) words.set(previous, word);
            } else words.add(word);
        }
        chunks.add(new Chunk(channel, start, end, found.size()));
    }

    private List<Interval> uncoveredEvidence(int channel, List<SpeechEvidence> evidence, List<Word> words) {
        List<Interval> intervals = evidence.stream()
                .filter(item -> item.channel() == channel)
                .filter(item -> !covered(item, words))
                .map(item -> new Interval(Math.max(0, item.start()), Math.min(audio.samples(), item.end())))
                .filter(item -> item.end() > item.start())
                .sorted(Comparator.comparingLong(Interval::start).thenComparingLong(Interval::end))
                .toList();
        if (intervals.isEmpty()) return List.of();

        List<Interval> merged = new ArrayList<>();
        long start = intervals.get(0).start(), end = intervals.get(0).end();
        for (int i = 1; i < intervals.size(); i++) {
            Interval next = intervals.get(i);
            if (next.start() <= end + 2 * CONTEXT) end = Math.max(end, next.end());
            else { merged.add(new Interval(start, end)); start = next.start(); end = next.end(); }
        }
        merged.add(new Interval(start, end));
        return merged;
    }

    private static boolean covered(SpeechEvidence evidence, List<Word> words) {
        long requiredOverlap = Math.max(1, evidence.minimumOverlap());
        return words.stream().anyMatch(word -> word.channel() == evidence.channel()
                && Math.min(word.end(), evidence.end()) - Math.max(word.start(), evidence.start()) >= requiredOverlap);
    }

    private void addUnresolvedEvidenceGaps(int channel, List<SpeechEvidence> evidence, List<Word> words, List<Gap> gaps) {
        for (SpeechEvidence item : evidence) {
            if (item.channel() != channel || covered(item, words)) continue;
            long start = Math.max(0, item.start()), end = Math.min(audio.samples(), item.end());
            if (end <= start || gaps.stream().anyMatch(gap -> gap.channel() == channel
                    && gap.start() <= start && gap.end() >= end)) continue;
            gaps.add(new Gap(channel, start, end));
        }
    }

    private static void sortAndDeduplicate(List<Word> words) {
        words.sort(Comparator.comparingInt(Word::channel).thenComparingLong(Word::start)
                .thenComparingLong(Word::end));
        List<Word> unique = new ArrayList<>();
        for (Word word : words) {
            int duplicateIndex = -1;
            for (int i = unique.size() - 1; i >= 0; i--) {
                Word candidate = unique.get(i);
                if (candidate.end() <= word.start()) break;
                if (duplicate(candidate, word)) { duplicateIndex = i; break; }
            }
            if (duplicateIndex < 0) unique.add(word);
            else {
                Word old = unique.get(duplicateIndex);
                if (word.confidence() != null && (old.confidence() == null || word.confidence() > old.confidence()))
                    unique.set(duplicateIndex, word);
            }
        }
        words.clear();
        words.addAll(unique);
    }

    private static List<Gap> coalesceGaps(List<Gap> gaps) {
        List<Gap> ordered = gaps.stream().sorted(Comparator.comparingInt(Gap::channel)
                .thenComparingLong(Gap::start).thenComparingLong(Gap::end)).toList();
        if (ordered.isEmpty()) return List.of();
        List<Gap> merged = new ArrayList<>();
        Gap current = ordered.get(0);
        for (int i = 1; i < ordered.size(); i++) {
            Gap next = ordered.get(i);
            if (next.channel() == current.channel() && next.start() <= current.end())
                current = new Gap(current.channel(), current.start(), Math.max(current.end(), next.end()));
            else { merged.add(current); current = next; }
        }
        merged.add(current);
        return List.copyOf(merged);
    }

    private record Interval(long start, long end) {}

    private static List<Word> parse(String body, int channel, int chunk, long readStart, long readEnd,
                                    long coreStart, long coreEnd) throws IOException {
        JsonNode root = Json.tree(body);
        if (root == null || !root.path("words").isArray()) throw new IOException("Whisper не вернул массив слов");
        List<Word> result = new ArrayList<>();
        for (JsonNode word : root.path("words")) {
            String text = word.path("word").asText("").trim();
            if (text.isBlank() || !word.path("start").isNumber() || !word.path("end").isNumber())
                throw new IOException("Некорректное слово Whisper");
            double start = readStart + word.path("start").asDouble() * RATE;
            double end = readStart + word.path("end").asDouble() * RATE;
            if (!Double.isFinite(start) || !Double.isFinite(end) || start < readStart - 1 || end > readEnd + 1 || end < start)
                throw new IOException("Некорректное время слова Whisper");
            double middle = (start + end) / 2;
            if (middle >= coreStart && middle < coreEnd)
                result.add(new Word(channel, text, Math.round(start), Math.round(end), chunk,
                        word.hasNonNull("confidence") ? word.path("confidence").asDouble() : null));
        }
        result.sort(Comparator.comparingLong(Word::start).thenComparingLong(Word::end));
        return result;
    }

    private static boolean duplicate(Word a, Word b) {
        if (a.channel() != b.channel() || a.chunk() == b.chunk() || !a.text().equalsIgnoreCase(b.text())) return false;
        long overlap = Math.min(a.end(), b.end()) - Math.max(a.start(), b.start());
        return overlap > 0 && overlap * 2 >= Math.min(a.end() - a.start(), b.end() - b.start());
    }

    /** @param channel номер канала
     * @param start начало признака речи в сэмплах
     * @param end конец признака речи в сэмплах
     * @param minimumOverlap минимальное пересечение */
    public record SpeechEvidence(int channel, long start, long end, long minimumOverlap) {
        /** @param channel номер канала
         * @param start начало интервала
         * @param end конец интервала */
        public SpeechEvidence(int channel, long start, long end) { this(channel, start, end, RATE / 5L); }
    }
    /** @param channel номер канала
     * @param text слово
     * @param start абсолютное начало в сэмплах
     * @param end абсолютный конец в сэмплах
     * @param chunk номер части
     * @param confidence оценка Whisper */
    public record Word(int channel, String text, long start, long end, int chunk, Double confidence) {}
    /** @param channel номер канала
     * @param start начало участка
     * @param end конец участка
     * @param words количество слов */
    public record Chunk(int channel, long start, long end, int words) {}
    /** @param channel номер канала
     * @param start начало участка
     * @param end конец участка */
    public record Gap(int channel, long start, long end) {}
    /** @param words итоговые слова
     * @param chunks обработанные участки
     * @param gaps нераспознанные участки */
    public record Result(List<Word> words, List<Chunk> chunks, List<Gap> gaps) {}
}
