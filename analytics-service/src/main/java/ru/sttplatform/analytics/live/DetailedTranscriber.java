package ru.sttplatform.analytics.live;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Второй проход, который возвращает слова с временными метками.
 *
 * @version 1.0.0
 */
public interface DetailedTranscriber extends Transcriber {

    /**
     * Слово с временем от начала переданного фрагмента.
     *
     * @param word текст слова
     * @param start начало, секунды
     * @param end конец, секунды
     * @param confidence оценка модели или {@code null}
     * @version 1.0.0
     */
    record Word(String word, double start, double end, Double confidence) {}

    /**
     * Уточнённый текст и слова.
     *
     * @param text полный текст
     * @param words слова (может быть пустым, если сервис отдал только текст)
     * @version 1.0.0
     */
    record Transcript(String text, List<Word> words) {}

    /**
     * Распознаёт фрагмент с временными метками слов.
     *
     * @param pcm PCM16 без WAV-заголовка
     * @return текст и слова
     */
    CompletableFuture<Transcript> transcribeDetailed(byte[] pcm);
}
