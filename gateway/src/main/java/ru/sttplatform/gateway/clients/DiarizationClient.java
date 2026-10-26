package ru.sttplatform.gateway.clients;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.common.ws.WsPeer;
import ru.sttplatform.gateway.config.GatewayProperties;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Клиент diarization-service: REST для управления сессией и WebSocket для живого звука.
 *
 * @version 1.0.0
 */
@Component
public class DiarizationClient {
    private final String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /**
     * Создаёт клиент.
     *
     * @param props адреса сервисов
     */
    public DiarizationClient(GatewayProperties props) {
        this.base = props.diarizationUrl().replaceAll("/+$", "");
    }

    /**
     * Начинает сессию (в сервисе загружаются модели — это может занять время).
     *
     * @return состояние сессии
     */
    public Map<String, Object> start() {
        return Json.toMap(call("POST", "/api/sessions", null, null, Duration.ofMinutes(3)));
    }

    /**
     * Состояние текущей сессии; заголовок {@code X-Session-Id} продлевает её «жизнь».
     *
     * @param token идентификатор сессии или {@code null}
     * @return состояние или {@code {phase: idle}}
     */
    public Map<String, Object> current(String token) {
        return Json.toMap(call("GET", "/api/sessions/current", null, token, Duration.ofSeconds(5)));
    }

    /**
     * Состояние сессии по идентификатору.
     *
     * @param id идентификатор
     * @return состояние
     */
    public Map<String, Object> status(String id) {
        return Json.toMap(call("GET", "/api/sessions/" + id, null, null, Duration.ofSeconds(5)));
    }

    /**
     * Передаёт кусок аудио эталона.
     *
     * @param id идентификатор сессии
     * @param sequence порядковый номер блока
     * @param pcm PCM16 16 кГц моно
     */
    public void enrollAudio(String id, long sequence, byte[] pcm) {
        call("POST", "/api/sessions/" + id + "/enrollment/audio", pcm, null, Duration.ofSeconds(10), "X-Sequence", Long.toString(sequence));
    }

    /**
     * Завершает запись эталона.
     *
     * @param id идентификатор сессии
     * @return состояние: live или retry
     */
    public Map<String, Object> finishEnrollment(String id) {
        return Json.toMap(call("POST", "/api/sessions/" + id + "/enrollment/finish", null, null, Duration.ofSeconds(60)));
    }

    /**
     * Запрашивает остановку интервью.
     *
     * @param id идентификатор сессии
     * @param reason причина
     * @return состояние
     */
    public Map<String, Object> finish(String id, String reason) {
        return Json.toMap(call("POST", "/api/sessions/" + id + "/finish", null, null, Duration.ofSeconds(10), "X-Stop-Reason", reason));
    }

    /**
     * Все подтверждённые сегменты ролей.
     *
     * @param id идентификатор сессии
     * @return сегменты
     */
    public List<Map<String, Object>> segments(String id) {
        JsonNode node = call("GET", "/api/sessions/" + id + "/segments", null, null, Duration.ofSeconds(10));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode row : node) rows.add(Json.toMap(row));
        return rows;
    }

    /**
     * Открывает канал живого звука и событий диаризации.
     *
     * @param id идентификатор сессии
     * @param handler обработчик событий {@code diarization}/{@code finished}/{@code error}
     * @return подключённый клиент
     */
    public WsPeer connectLive(String id, Consumer<JsonNode> handler) {
        return WsPeer.connect(base.replaceFirst("^http", "ws") + "/ws/diarization/" + id, handler, null);
    }

    private JsonNode call(String method, String path, byte[] body, String sessionHeader, Duration timeout, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout);
        if (sessionHeader != null) request.header("X-Session-Id", sessionHeader);
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        if ("POST".equals(method)) {
            request.header("Content-Type", "application/octet-stream")
                    .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        } else request.GET();
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode json = response.body() == null || response.body().isBlank() ? Json.MAPPER.createObjectNode() : Json.tree(response.body());
            if (response.statusCode() >= 400)
                throw new ApiException(response.statusCode(), json.path("error").asText("diarization-service: HTTP " + response.statusCode()));
            return json;
        } catch (IOException e) {
            throw new ApiException(502, "diarization-service недоступен (" + base + "): " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "Запрос к diarization-service прерван.");
        }
    }
}
