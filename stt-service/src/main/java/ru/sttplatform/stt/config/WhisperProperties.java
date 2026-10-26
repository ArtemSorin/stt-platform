package ru.sttplatform.stt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки Whisper (префикс {@code whisper}).
 *
 * @param url адрес Python-сервера; пусто — Whisper выключен
 * @param autostart запускать ли {@code whisper_server.py} вместе с сервисом
 * @param python исполняемый файл Python
 * @param script путь к {@code whisper_server.py}
 * @param model имя модели faster-whisper или путь к CTranslate2-модели
 * @param device {@code cpu} или {@code cuda}
 * @param computeType тип вычислений, например {@code int8}
 * @param cpuThreads число потоков CPU
 * @param startupTimeoutSeconds сколько ждать готовности сервера при автозапуске
 * @version 1.0.0
 */
@ConfigurationProperties(prefix = "whisper")
public record WhisperProperties(String url, Boolean autostart, String python, String script, String model,
                                String device, String computeType, Integer cpuThreads, Integer startupTimeoutSeconds) {
    public WhisperProperties {
        if (url == null) url = "";
        if (autostart == null) autostart = false;
        if (python == null || python.isBlank()) python = "python";
        if (script == null || script.isBlank()) script = "whisper/whisper_server.py";
        if (model == null || model.isBlank()) model = "small";
        if (device == null || device.isBlank()) device = "cpu";
        if (computeType == null || computeType.isBlank()) computeType = "int8";
        if (cpuThreads == null) cpuThreads = 2;
        if (startupTimeoutSeconds == null) startupTimeoutSeconds = 600;
    }

    /**
     * Настроен ли второй проход.
     *
     * @return {@code true}, если задан адрес сервера
     */
    public boolean configured() {
        return !url.isBlank();
    }
}
