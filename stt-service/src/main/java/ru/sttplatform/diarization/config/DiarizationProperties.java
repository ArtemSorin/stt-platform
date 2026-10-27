package ru.sttplatform.diarization.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Настройки встроенного компонента диаризации (префикс {@code diarization}).
 *
 * @param nemoLib путь к {@code nemo_speech_asr_c.dll}
 * @param nemoModel путь к GGUF-модели Nemotron
 * @param speakerModel путь к ONNX-модели CAM++
 * @param outputDir каталог для events.jsonl, segments.json, summary.json
 * @param preset пресет Nemotron
 * @param numThreads потоков CAM++
 * @param queueChunks ёмкость очереди аудио (блоков по 160 мс)
 * @param threshold порог сходства голосов
 * @param settleSeconds сколько секунд после обработки ждать до фиксации сегмента
 * @version 1.0.0
 */
@ConfigurationProperties(prefix = "diarization")
public record DiarizationProperties(String nemoLib, String nemoModel, String speakerModel, String outputDir,
                                    String preset, Integer numThreads, Integer queueChunks,
                                    Double threshold, Double settleSeconds) {
    public DiarizationProperties {
        if (preset == null || preset.isBlank()) preset = "v3-streaming";
        if (numThreads == null) numThreads = 4;
        if (queueChunks == null) queueChunks = 32;
        if (threshold == null) threshold = .60;
        if (settleSeconds == null) settleSeconds = .64;
        if (numThreads < 1 || queueChunks < 1 || settleSeconds < 0)
            throw new IllegalArgumentException("Проверьте diarization.num-threads, queue-chunks и settle-seconds.");
    }

    /**
     * Абсолютный нормализованный путь из строки настройки.
     *
     * @param value значение настройки
     * @param name имя настройки для сообщения об ошибке
     * @return путь
     */
    public static Path path(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException("Не задана настройка diarization." + name);
        return Path.of(value).toAbsolutePath().normalize();
    }
}
