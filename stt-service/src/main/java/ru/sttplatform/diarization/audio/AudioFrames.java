package ru.sttplatform.diarization.audio;


import java.util.List;

/**
 * Объединяет четыре последовательных входных кадра в блок для Nemotron.
 *
 * @version 1.0.0
 */
public final class AudioFrames {
    private AudioFrames() {}

    /**
     * Сохраняет время первого кадра и отклоняет пропуски, чтобы не сместить границы речи.
     *
     * @param frames последовательные аудиокадры
     * @param index номер блока Nemotron
     * @return объединённый блок
     * @throws IllegalArgumentException если список пуст
     * @throws IllegalStateException если нарушен порядок сэмплов
     */
    public static AudioChunk mergeForNemotron(List<AudioChunk> frames, long index) {
        if (frames == null || frames.isEmpty()) throw new IllegalArgumentException("Нет кадров для Nemotron.");
        long expected = frames.get(0).firstSample();
        int size = 0;
        for (AudioChunk frame : frames) {
            if (frame.firstSample() != expected)
                throw new IllegalStateException("Нарушен порядок 160-мс кадров перед Nemotron.");
            expected += frame.samples().length;
            size += frame.samples().length;
        }
        float[] samples = new float[size];
        int offset = 0;
        for (AudioChunk frame : frames) {
            System.arraycopy(frame.samples(), 0, samples, offset, frame.samples().length);
            offset += frame.samples().length;
        }
        AudioChunk first = frames.get(0);
        return new AudioChunk(index, first.firstSample(), samples, first.capturedAtNanos());
    }
}
