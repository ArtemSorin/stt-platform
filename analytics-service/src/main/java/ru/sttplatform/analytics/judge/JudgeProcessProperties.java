package ru.sttplatform.analytics.judge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Автозапуск {@code judge/frida_server.py} вместе с сервисом (префикс {@code interview.judge.process}).
 *
 * @param autostart запускать ли сервер модели; по умолчанию нет — его можно запустить отдельно
 * @param python исполняемый файл Python (с установленным frida-decisions)
 * @param script путь к {@code frida_server.py}
 * @param backend {@code torch}, {@code onnx} или {@code heuristic} (заглушка без модели)
 * @param model репозиторий Hugging Face или локальная папка модели
 * @param device {@code cuda} или {@code cpu} для torch; пусто — выбрать автоматически
 * @param startupTimeoutSeconds сколько ждать готовности (первая загрузка весов может быть долгой)
 * @version 1.0.0
 */
@ConfigurationProperties(prefix = "interview.judge.process")
public record JudgeProcessProperties(Boolean autostart, String python, String script, String backend, String model,
                                     String device, Integer startupTimeoutSeconds) {
    public JudgeProcessProperties {
        if (autostart == null) autostart = false;
        if (python == null || python.isBlank()) python = "python";
        if (script == null || script.isBlank()) script = "judge/frida_server.py";
        if (backend == null || backend.isBlank()) backend = "torch";
        if (model == null || model.isBlank()) model = "ai-forever/FRIDA-Decisions";
        if (device == null) device = "";
        if (startupTimeoutSeconds == null) startupTimeoutSeconds = 900;
    }
}
