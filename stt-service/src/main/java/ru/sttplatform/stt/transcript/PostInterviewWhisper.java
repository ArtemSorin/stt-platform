package ru.sttplatform.stt.transcript;

import com.fasterxml.jackson.databind.JsonNode;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.stt.whisper.WhisperClient;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Повторно распознаёт каждый подтверждённый участок речи после остановки захвата. */
public final class PostInterviewWhisper implements AutoCloseable {
    private static final int RATE = 16_000;
    private static final long CHUNK = 12L * RATE;
    private static final long CONTEXT = RATE / 2L;
    private final Path path;
    private final RandomAccessFile audio;
    private boolean sealed;

    /** @param directory каталог сессии для временного исходного PCM
     * @throws IOException если запись звука открыть не удалось
     */
    public PostInterviewWhisper(Path directory) throws IOException {
        path = directory.resolve(".whisper-source.pcm.tmp");
        audio = new RandomAccessFile(path.toFile(), "rw");
        audio.setLength(0);
    }

    /** Сохраняет исходный системный канал, включая звук с ещё неопределённой ролью.
     * @param firstSample позиция блока на шкале интервью
     * @param pcm моно PCM16, 16 кГц
     * @throws IOException при ошибке записи или разрыве шкалы
     */
    public void append(long firstSample, byte[] pcm) throws IOException {
        if (sealed || audio.length() != firstSample * 2L) throw new IOException("Разрыв записи для Whisper");
        audio.write(pcm);
    }

    /** Обрабатывает всю речь кандидата короткими фрагментами после завершения интервью.
     * @param ranges финальные интервалы речи кандидата в сэмплах
     * @param client локальный Whisper helper
     * @return слова с абсолютными временами и диагностика каждого отправленного фрагмента
     * @throws IOException если исходный звук повреждён или helper ответил ошибкой
     * @throws InterruptedException если обработка прервана
     */
    public Result transcribe(List<Range> ranges, WhisperClient client) throws IOException, InterruptedException {
        sealed = true;
        long total = audio.length() / 2L;
        List<Word> result = new ArrayList<>();
        List<Chunk> chunks = new ArrayList<>();
        int chunkId = 0;
        for (Range range : merge(ranges, total)) {
            for (long coreStart = range.start(); coreStart < range.end(); coreStart += CHUNK) {
                long coreEnd = Math.min(coreStart + CHUNK, range.end());
                long readStart = Math.max(range.start(), coreStart - CONTEXT);
                long readEnd = Math.min(range.end(), coreEnd + CONTEXT);
                byte[] pcm = new byte[Math.toIntExact((readEnd - readStart) * 2L)];
                audio.seek(readStart * 2L);
                audio.readFully(pcm);
                WhisperClient.Reply reply;
                try { reply = client.post("/transcribe-json", pcm); }
                catch (IOException error) { throw new IOException("Whisper, фрагмент " + chunkId
                        + " [" + coreStart / (double) RATE + "–" + coreEnd / (double) RATE + " с]: "
                        + error.getMessage(), error); }
                if (reply.status() != 200) throw new IOException("Whisper ответил HTTP " + reply.status()
                        + " на фрагмент " + chunkId);
                JsonNode words = Json.tree(reply.body()).path("words");
                if (!words.isArray()) throw new IOException("Whisper не вернул массив слов");
                int before = result.size();
                for (JsonNode word : words) {
                    String text = word.path("word").asText("").trim();
                    double start = readStart + word.path("start").asDouble(-1) * RATE;
                    double end = readStart + word.path("end").asDouble(-1) * RATE;
                    double middle = (start + end) / 2;
                    if (text.isBlank() || start < readStart - 1 || end > readEnd + 1 || end < start
                            || middle < coreStart || middle >= coreEnd) continue;
                    result.add(new Word(text, Math.round(start), Math.round(end), chunkId,
                            word.hasNonNull("confidence") ? word.path("confidence").asDouble() : null));
                }
                chunks.add(new Chunk(chunkId, coreStart, coreEnd, result.size() - before));
                chunkId++;
            }
        }
        return new Result(List.copyOf(result), List.copyOf(chunks));
    }

    /** @param start первый сэмпл включительно
     * @param end последний сэмпл исключительно
     */
    public record Range(long start, long end) {}

    /** @param text распознанное слово
     * @param start абсолютный первый сэмпл
     * @param end абсолютный последний сэмпл
     * @param chunk номер обработанного фрагмента
     * @param confidence оценка Whisper
     */
    public record Word(String text, long start, long end, int chunk, Double confidence) {}

    /** @param id номер фрагмента
     * @param start начало покрываемого интервала
     * @param end конец покрываемого интервала
     * @param words количество слов в результате
     */
    public record Chunk(int id, long start, long end, int words) {}

    /** @param words распознанные слова
     * @param chunks все отправленные фрагменты, в том числе без распознанных слов
     */
    public record Result(List<Word> words, List<Chunk> chunks) {}

    private static List<Range> merge(List<Range> ranges, long total) {
        List<Range> sorted = ranges.stream()
                .map(r -> new Range(Math.max(0, r.start()), Math.min(total, r.end())))
                .filter(r -> r.end() > r.start())
                .sorted(Comparator.comparingLong(Range::start)).toList();
        List<Range> merged = new ArrayList<>();
        for (Range range : sorted) {
            int last = merged.size() - 1;
            if (last >= 0 && range.start() <= merged.get(last).end()) {
                Range previous = merged.get(last);
                merged.set(last, new Range(previous.start(), Math.max(previous.end(), range.end())));
            } else merged.add(range);
        }
        return merged;
    }

    @Override public void close() throws IOException {
        try { audio.close(); } finally { Files.deleteIfExists(path); }
    }
}
