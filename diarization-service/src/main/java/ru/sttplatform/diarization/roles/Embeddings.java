package ru.sttplatform.diarization.roles;

import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import ru.sttplatform.common.audio.Pcm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Голосовые эмбеддинги CAM++ (sherpa-onnx): извлечение, нормализация, сравнение.
 *
 * @version 2.0.0
 */
public final class Embeddings {
    private Embeddings() {}

    /**
     * Создаёт извлекатель эмбеддингов.
     *
     * @param model путь к ONNX-модели CAM++
     * @param threads число потоков
     * @return извлекатель
     */
    public static SpeakerEmbeddingExtractor extractor(Path model, int threads) {
        if (!Files.isRegularFile(model)) throw new IllegalArgumentException("Не найдена ONNX-модель эмбеддингов: " + model);
        SpeakerEmbeddingExtractorConfig cfg = SpeakerEmbeddingExtractorConfig.builder()
                .setModel(model.toString()).setNumThreads(threads).setDebug(false).build();
        return new SpeakerEmbeddingExtractor(cfg);
    }

    /**
     * Эмбеддинг участка аудио.
     *
     * @param extractor извлекатель
     * @param audio сэмплы
     * @param start начало, секунды
     * @param end конец, секунды
     * @param trim сколько срезать с краёв, секунды
     * @return нормированный эмбеддинг или {@code null}, если речи мало или она слишком тихая
     */
    public static float[] embedding(SpeakerEmbeddingExtractor extractor, float[] audio,
                                    double start, double end, double trim) {
        if (end - start > 2 * trim + 0.2) { start += trim; end -= trim; }
        int first = Math.max(0, (int) Math.rint(start * Pcm.RATE));
        int last = Math.min(audio.length, (int) Math.rint(end * Pcm.RATE));
        if (last - first < (int) (.65 * Pcm.RATE)) return null;
        float[] signal = Arrays.copyOfRange(audio, first, last);
        double power = 0;
        for (float sample : signal) power += (double) sample * sample;
        if (Math.sqrt(power / signal.length) < .003) return null;
        OnlineStream stream = extractor.createStream();
        try {
            stream.acceptWaveform(signal, Pcm.RATE);
            stream.inputFinished();
            float[] result = extractor.compute(stream);
            if (result == null || result.length == 0) return null;
            for (float value : result) if (!Float.isFinite(value)) return null;
            return normalize(result);
        } finally {
            stream.release();
        }
    }

    /**
     * Нормирует вектор до единичной длины.
     *
     * @param values вектор
     * @return нормированная копия
     */
    public static float[] normalize(float[] values) {
        double norm = 0;
        for (float value : values) norm += (double) value * value;
        norm = Math.max(Math.sqrt(norm), 1e-12);
        float[] result = values.clone();
        for (int i = 0; i < result.length; i++) result[i] = (float) (result[i] / norm);
        return result;
    }

    /**
     * Скалярное произведение (косинусное сходство для нормированных векторов).
     *
     * @param left первый вектор
     * @param right второй вектор
     * @return сходство
     */
    public static double dot(float[] left, float[] right) {
        double result = 0;
        for (int i = 0; i < left.length; i++) result += (double) left[i] * right[i];
        return result;
    }

    /**
     * Нормированный средний вектор набора эмбеддингов.
     *
     * @param embeddings непустой набор
     * @return прототип голоса
     */
    public static float[] meanPrototype(List<float[]> embeddings) {
        float[] mean = new float[embeddings.get(0).length];
        for (float[] embedding : embeddings) for (int i = 0; i < mean.length; i++) mean[i] += embedding[i];
        for (int i = 0; i < mean.length; i++) mean[i] /= embeddings.size();
        return normalize(mean);
    }
}
