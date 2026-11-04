package ru.sttplatform.stt.transcript;

import com.fasterxml.jackson.databind.JsonNode;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.stt.whisper.WhisperClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Асинхронно уточняет длинный ответ частями до его окончательного решения аналитикой. */
public final class LiveWhisperChunks {
    private static final int CHUNK_BYTES = 30 * 16_000 * 2;
    private final WhisperClient client;
    private final Executor executor;
    private final long base;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream(CHUNK_BYTES);
    private final List<CompletableFuture<Chunk>> chunks = new ArrayList<>();
    private long nextSample, chunkStart;

    /** @param client клиент Whisper
     * @param executor ограниченный исполнитель запросов
     * @param firstSample начало ответа */
    public LiveWhisperChunks(WhisperClient client, Executor executor, long firstSample) {
        this.client = client; this.executor = executor; base = firstSample;
        nextSample = firstSample; chunkStart = firstSample;
    }

    /** Добавляет звук ответа и сразу отправляет каждые накопленные 30 секунд.
     * @param firstSample позиция кадра
     * @param pcm PCM16 */
    public synchronized void append(long firstSample, byte[] pcm) {
        if (firstSample != nextSample || pcm.length % 2 != 0) throw new IllegalStateException("Разрыв аудио ответа Whisper");
        int offset = 0;
        while (offset < pcm.length) {
            int size = Math.min(pcm.length - offset, CHUNK_BYTES - pending.size());
            pending.write(pcm, offset, size);
            offset += size; nextSample += size / 2L;
            if (pending.size() == CHUNK_BYTES) dispatch();
        }
    }

    /** Фиксирует оставшуюся часть и возвращает все запросы текущего варианта ответа.
     * @return независимый список будущих результатов */
    public synchronized List<CompletableFuture<Chunk>> snapshot() {
        if (pending.size() > 0) dispatch();
        return List.copyOf(chunks);
    }

    private void dispatch() {
        byte[] pcm = pending.toByteArray();
        pending.reset();
        long offset = chunkStart - base;
        chunkStart = nextSample;
        CompletableFuture<Chunk> future = new CompletableFuture<>();
        chunks.add(future);
        try {
            executor.execute(() -> {
                try {
                    WhisperClient.Reply reply = client.post("/transcribe-json", pcm);
                    if (reply.status() != 200) throw new IOException("Whisper ответил HTTP " + reply.status());
                    JsonNode result = Json.tree(reply.body());
                    if (result == null || !result.path("words").isArray()) throw new IOException("Whisper не вернул слова");
                    future.complete(new Chunk(offset, result.path("words").deepCopy()));
                } catch (Throwable error) { future.completeExceptionally(error); }
            });
        } catch (RuntimeException rejected) { future.completeExceptionally(rejected); }
    }

    /** @param offset смещение части относительно начала ответа в сэмплах
     * @param words слова Whisper с временем относительно части */
    public record Chunk(long offset, JsonNode words) {}
}
