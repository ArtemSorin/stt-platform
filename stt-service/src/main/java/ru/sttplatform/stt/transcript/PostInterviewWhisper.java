package ru.sttplatform.stt.transcript;

import com.fasterxml.jackson.databind.JsonNode;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.stt.whisper.WhisperClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;

/** Распознаёт каждый канал завершённой записи небольшими перекрывающимися фрагментами. */
public final class PostInterviewWhisper {
    private static final int RATE = 16_000;
    private static final long CORE = 11L * RATE;
    private static final long CONTEXT = RATE / 2L;
    private final InterviewAudioSpool audio;

    /** @param audio закрытая запись интервью */
    public PostInterviewWhisper(InterviewAudioSpool audio) { this.audio = audio; }

    /** Распознаёт оба канала целиком, включая интервалы без определённой роли.
     * @param evidence признаки речи из Vosk и диаризации
     * @param client локальный Whisper
     * @param progress уведомление о числе обработанных и всех частей
     * @return слова и сведения об обработанных частях
     * @throws IOException если запись или ответ Whisper неполны
     * @throws InterruptedException если запрос прерван
     */
    public Result transcribe(List<SpeechEvidence> evidence, WhisperClient client,
                             BiConsumer<Integer, Integer> progress) throws IOException, InterruptedException {
        long total = audio.samples();
        long perChannel = (total + CORE - 1) / CORE;
        int count = Math.toIntExact(perChannel * audio.channels());
        int completed = 0;
        progress.accept(0, count);
        List<Word> words = new ArrayList<>();
        List<Chunk> chunks = new ArrayList<>();
        for (int channel = 0; channel < audio.channels(); channel++) {
            for (long coreStart = 0; coreStart < total; coreStart += CORE) {
                long coreEnd = Math.min(coreStart + CORE, total);
                long readStart = Math.max(0, coreStart - CONTEXT);
                long readEnd = Math.min(total, coreEnd + CONTEXT);
                byte[] pcm = audio.read(channel, readStart, readEnd);
                boolean expectedSpeech = false;
                for (SpeechEvidence span : evidence) {
                    if (span.channel() == channel && Math.min(coreEnd, span.end()) - Math.max(coreStart, span.start()) >= span.minimumOverlap()) {
                        expectedSpeech = true;
                        break;
                    }
                }
                List<Word> found = null;
                IOException last = null;
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        WhisperClient.Reply reply = client.post("/transcribe-json", pcm);
                        if (reply.status() != 200) throw new IOException("Whisper ответил HTTP " + reply.status());
                        found = parse(reply.body(), channel, completed, readStart, readEnd, coreStart, coreEnd);
                        if (found.isEmpty() && expectedSpeech) throw new IOException("Whisper не распознал участок с признаками речи");
                        last = null;
                        break;
                    } catch (IOException | IllegalArgumentException error) {
                        last = new IOException("Канал " + channel + ", фрагмент " + completed + " ["
                                + coreStart / (double) RATE + "–" + coreEnd / (double) RATE + " с]: " + error.getMessage(), error);
                    }
                }
                if (last != null) throw last;
                for (Word word : found) {
                    int previous = words.size() - 1;
                    if (previous >= 0 && duplicate(words.get(previous), word)) {
                        Word old = words.get(previous);
                        if (word.confidence() != null && (old.confidence() == null || word.confidence() > old.confidence()))
                            words.set(previous, word);
                    } else words.add(word);
                }
                chunks.add(new Chunk(channel, coreStart, coreEnd, found.size()));
                progress.accept(++completed, count);
            }
        }
        return new Result(List.copyOf(words), List.copyOf(chunks));
    }

    private static List<Word> parse(String body, int channel, int chunk, long readStart, long readEnd,
                                    long coreStart, long coreEnd) throws IOException {
        JsonNode root = Json.tree(body);
        if (root == null) throw new IOException("Пустой ответ Whisper");
        JsonNode array = root.path("words");
        if (!array.isArray()) throw new IOException("Whisper не вернул массив слов");
        List<Word> result = new ArrayList<>();
        for (JsonNode word : array) {
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
        if (a.channel() != b.channel() || a.chunk() == b.chunk()
                || !a.text().equalsIgnoreCase(b.text())) return false;
        long overlap = Math.min(a.end(), b.end()) - Math.max(a.start(), b.start());
        return overlap > 0 && overlap * 2 >= Math.min(a.end() - a.start(), b.end() - b.start());
    }

    /** @param channel номер канала
     * @param start начало признака речи в сэмплах
     * @param end конец признака речи в сэмплах
     * @param minimumOverlap минимальное пересечение с основной частью */
    public record SpeechEvidence(int channel, long start, long end, long minimumOverlap) {
        /** Использует порог для протяжённого интервала диаризации.
         * @param channel номер канала
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
     * @param start начало основной части
     * @param end конец основной части
     * @param words количество слов */
    public record Chunk(int channel, long start, long end, int words) {}
    /** @param words итоговые слова
     * @param chunks обработанные части */
    public record Result(List<Word> words, List<Chunk> chunks) {}
}
