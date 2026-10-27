package ru.sttplatform.stt.whisper;

import org.springframework.stereotype.Component;
import ru.sttplatform.stt.config.WhisperProperties;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * HTTP-клиент Python-сервера Whisper ({@code /transcribe}, {@code /transcribe-json}).
 *
 * @version 1.0.0
 */
@Component
public class WhisperClient {
    /**
     * Ответ Whisper-сервера.
     *
     * @param status HTTP-код
     * @param body тело ответа (UTF-8)
     */
    public record Reply(int status, String body) {}

    private final WhisperProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /**
     * Создаёт клиент.
     *
     * @param props настройки Whisper
     */
    public WhisperClient(WhisperProperties props) {
        this.props = props;
    }

    /**
     * Настроен ли Whisper.
     *
     * @return {@code true}, если задан адрес
     */
    public boolean configured() {
        return props.configured();
    }

    /**
     * Базовый адрес Python-сервера без завершающего слэша.
     *
     * @return адрес
     */
    public String baseUrl() {
        return props.url().replaceAll("/+$", "");
    }

    /**
     * Отвечает ли Python-сервер на {@code GET /}. Готовность модели этим не проверяется.
     *
     * @return {@code true}, если сервер принимает соединения
     */
    public boolean reachable() {
        if (!configured()) return false;
        try {
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/"))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding());
            return r.statusCode() < 500;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Отправляет сырой PCM 16 кГц / 16 бит / моно на указанный путь Whisper-сервера.
     *
     * @param path {@code /transcribe} или {@code /transcribe-json}
     * @param pcm байты PCM без WAV-заголовка
     * @return статус и тело ответа
     * @throws IOException при сетевой ошибке
     * @throws InterruptedException если поток прерван
     */
    public Reply post(String path, byte[] pcm) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(pcm)).build();
        HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Reply(r.statusCode(), r.body());
    }
}
