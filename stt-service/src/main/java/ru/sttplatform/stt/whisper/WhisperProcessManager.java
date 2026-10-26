package ru.sttplatform.stt.whisper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.sttplatform.stt.config.WhisperProperties;

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

/**
 * Необязательный автозапуск {@code whisper_server.py} ({@code whisper.autostart=true}) —
 * замена той части {@code DemoLauncher}, которая поднимала Whisper.
 *
 * @version 1.0.0
 */
@Component
@ConditionalOnProperty(name = "whisper.autostart", havingValue = "true")
public class WhisperProcessManager {
    private static final Logger log = LoggerFactory.getLogger(WhisperProcessManager.class);
    private final WhisperProperties props;
    private Process process;

    /**
     * Создаёт менеджер процесса.
     *
     * @param props настройки Whisper
     */
    public WhisperProcessManager(WhisperProperties props) {
        this.props = props;
    }

    @PostConstruct
    void start() throws Exception {
        Path script = Path.of(props.script()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(script)) throw new IllegalStateException("Не найден whisper_server.py: " + script);
        URI uri = URI.create(props.url());
        ProcessBuilder builder = new ProcessBuilder(props.python(), script.toString(),
                "--port", String.valueOf(uri.getPort() > 0 ? uri.getPort() : 9000),
                "--model", props.model(), "--device", props.device(),
                "--compute-type", props.computeType(), "--cpu-threads", String.valueOf(props.cpuThreads()));
        builder.redirectErrorStream(true);
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        process = builder.start();
        Thread output = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) log.info("[whisper] {}", line);
            } catch (Exception e) {
                log.warn("Лог Whisper недоступен: {}", e.toString());
            }
        }, "whisper-output");
        output.setDaemon(true);
        output.start();

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest ping = HttpRequest.newBuilder(URI.create(props.url().replaceAll("/+$", "") + "/"))
                .timeout(Duration.ofSeconds(2)).GET().build();
        long deadline = System.nanoTime() + Duration.ofSeconds(props.startupTimeoutSeconds()).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) throw new IllegalStateException("Whisper завершился до готовности, код " + process.exitValue());
            try {
                if (http.send(ping, HttpResponse.BodyHandlers.discarding()).statusCode() < 500) {
                    log.info("Whisper готов: {}", props.url());
                    return;
                }
            } catch (Exception ignored) {
                // модель ещё загружается
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("Whisper не запустился за " + props.startupTimeoutSeconds() + " с");
    }

    @PreDestroy
    void stop() {
        if (process != null && process.isAlive()) process.destroy();
    }
}
