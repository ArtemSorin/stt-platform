package ru.sttplatform.analytics.live;

import java.util.concurrent.CompletableFuture;

/**
 * Второй, более точный проход распознавания (Whisper). Работает асинхронно, вне критического пути решения.
 *
 * @version 2.0.0
 */
@FunctionalInterface
public interface Transcriber {

    /**
     * Распознаёт готовый фрагмент ответа.
     *
     * @param pcm16kMono PCM 16 кГц / 16 бит / little-endian / моно
     * @return текст ответа
     */
    CompletableFuture<String> transcribe(byte[] pcm16kMono);
}
