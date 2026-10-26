package ru.sttplatform.analytics.judge;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Необязательный автозапуск {@code judge/frida_server.py} ({@code interview.judge.process.autostart=true}).
 * Сервис не стартует, пока модель не загрузилась: иначе первые ответы пошли бы по запасным правилам.
 *
 * @version 1.0.0
 */
@Component
@ConditionalOnProperty(name = "interview.judge.process.autostart", havingValue = "true")
public class JudgeProcessManager {
    private static final Logger log = LoggerFactory.getLogger(JudgeProcessManager.class);
    private final JudgeProcessProperties process;
    private final JudgeProperties judge;
    private Process child;

    /**
     * Создаёт менеджер процесса.
     *
     * @param process настройки запуска
     * @param judge настройки клиента (адрес сервера)
     */
    public JudgeProcessManager(JudgeProcessProperties process, JudgeProperties judge) {
        this.process = process;
        this.judge = judge;
    }

    @PostConstruct
    void start() throws Exception {
        Path script = Path.of(process.script()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(script)) throw new IllegalStateException("Не найден frida_server.py: " + script);
        URI uri = URI.create(judge.url());
        List<String> command = new ArrayList<>(List.of(process.python(), script.toString(),
                "--port", String.valueOf(uri.getPort() > 0 ? uri.getPort() : 9100),
                "--backend", process.backend(), "--model", process.model()));
        if (!process.device().isBlank()) command.addAll(List.of("--device", process.device()));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        child = builder.start();
        Thread output = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) log.info("[frida] {}", line);
            } catch (Exception e) {
                log.warn("Лог сервера модели недоступен: {}", e.toString());
            }
        }, "frida-output");
        output.setDaemon(true);
        output.start();

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest ping = HttpRequest.newBuilder(URI.create(judge.url().replaceAll("/+$", "") + "/"))
                .timeout(Duration.ofSeconds(2)).GET().build();
        long deadline = System.nanoTime() + Duration.ofSeconds(process.startupTimeoutSeconds()).toNanos();
        while (System.nanoTime() < deadline) {
            if (!child.isAlive()) throw new IllegalStateException("Сервер модели завершился до готовности, код " + child.exitValue());
            try {
                if (http.send(ping, HttpResponse.BodyHandlers.discarding()).statusCode() < 500) {
                    log.info("Сервер модели готов: {}", judge.url());
                    return;
                }
            } catch (Exception ignored) {
                // модель ещё загружается
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Сервер модели не запустился за " + process.startupTimeoutSeconds() + " с");
    }

    @PreDestroy
    void stop() {
        if (child != null && child.isAlive()) child.destroy();
    }
}
