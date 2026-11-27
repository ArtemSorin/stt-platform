package ru.sttplatform.diarization.session;

import ru.sttplatform.diarization.audio.AudioChunk;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Ограниченная очередь live-диаризации с явным учётом кадров, которые она не успела принять.
 * После первого переполнения новые кадры пропускаются только для диаризации; основной аудиопоток
 * продолжает поступать в запись и STT.
 *
 * @version 1.0.0
 */
final class DiarizationAudioQueue {
    enum Admission { ACCEPTED, OVERFLOW, DISCARDED }

    record Snapshot(long acceptedSamples, long droppedSamples, long droppedChunks,
                    long overflowCount, long maxDepth) {}

    private final ArrayBlockingQueue<AudioChunk> queue;
    private final int capacity;
    private long acceptedSamples, droppedSamples, droppedChunks, overflowCount, maxDepth;
    private boolean disabled;

    DiarizationAudioQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Ёмкость очереди должна быть положительной.");
        this.capacity = capacity;
        queue = new ArrayBlockingQueue<>(capacity);
    }

    synchronized Admission offer(AudioChunk chunk) {
        if (disabled) {
            recordDrop(chunk);
            return Admission.DISCARDED;
        }
        if (!queue.offer(chunk)) {
            disabled = true;
            overflowCount++;
            recordDrop(chunk);
            return Admission.OVERFLOW;
        }
        acceptedSamples += chunk.samples().length;
        maxDepth = Math.max(maxDepth, queue.size());
        return Admission.ACCEPTED;
    }

    AudioChunk poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    int size() {
        return queue.size();
    }

    boolean isEmpty() {
        return queue.isEmpty();
    }

    int capacity() {
        return capacity;
    }

    synchronized long acceptedSamples() {
        return acceptedSamples;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(acceptedSamples, droppedSamples, droppedChunks, overflowCount, maxDepth);
    }

    private void recordDrop(AudioChunk chunk) {
        droppedChunks++;
        droppedSamples += chunk.samples().length;
    }
}
