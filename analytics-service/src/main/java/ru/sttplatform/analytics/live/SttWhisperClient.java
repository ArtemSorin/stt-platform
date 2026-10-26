package ru.sttplatform.analytics.live;

import com.fasterxml.jackson.databind.JsonNode;
import ru.sttplatform.common.json.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Клиент Whisper через микросервис {@code stt-service}: {@code POST /api/whisper/transcribe} (текст)
 * и {@code POST /api/whisper/transcribe-json} (слова с метками). Тело запроса — сырой PCM 16 кГц / 16 бит / моно.
 *
 * @version 2.0.0
 */
public final class SttWhisperClient implements DetailedTranscriber {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final URI textUri;
    private final URI jsonUri;
    private final URI statusUri;
    private final Duration timeout;

    /**
     * Создаёт клиент.
     *
     * @param sttHttpUrl базовый HTTP-адрес stt-service, например {@code http://127.0.0.1:8081}
     * @param timeout таймаут одного запроса
     */
    public SttWhisperClient(String sttHttpUrl, Duration timeout) {
        String base = sttHttpUrl.replaceAll("/+$", "");
        this.textUri = URI.create(base + "/api/whisper/transcribe");
        this.jsonUri = URI.create(base + "/api/whisper/transcribe-json");
        this.statusUri = URI.create(base + "/api/whisper/status");
        this.timeout = timeout;
    }

    /**
     * Спрашивает у stt-service, настроен ли и доступен ли Whisper.
     *
     * @return карта {@code configured} / {@code reachable}; при недоступности stt-service — оба {@code false}
     */
    public java.util.Map<String, Object> status() {
        try {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(statusUri)
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) return Json.map("configured", false, "reachable", false);
            JsonNode node = Json.tree(response.body());
            return Json.map("configured", node.path("configured").asBoolean(false),
                    "reachable", node.path("reachable").asBoolean(false));
        } catch (Exception error) {
            return Json.map("configured", false, "reachable", false);
        }
    }

    @Override
    public CompletableFuture<String> transcribe(byte[] pcm) {
        return client.sendAsync(post(textUri, pcm), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(r -> {
                    if (r.statusCode() != 200) throw new IllegalStateException("stt-service вернул HTTP " + r.statusCode());
                    return r.body().trim();
                });
    }

    @Override
    public CompletableFuture<Transcript> transcribeDetailed(byte[] pcm) {
        return client.sendAsync(post(jsonUri, pcm), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(response -> {
                    if (response.statusCode() == 404)
                        return transcribe(pcm).thenApply(text -> new Transcript(text, List.of()));
                    if (response.statusCode() != 200)
                        throw new IllegalStateException("stt-service вернул HTTP " + response.statusCode());
                    try {
                        JsonNode root = Json.tree(response.body());
                        List<Word> words = new ArrayList<>();
                        for (JsonNode row : root.path("words")) {
                            words.add(new Word(row.path("word").asText(""), row.path("start").asDouble(),
                                    row.path("end").asDouble(),
                                    row.hasNonNull("confidence") ? row.path("confidence").asDouble() : null));
                        }
                        return CompletableFuture.completedFuture(new Transcript(root.path("text").asText(""), words));
                    } catch (Exception error) {
                        throw new IllegalStateException("Некорректный JSON от Whisper", error);
                    }
                });
    }

    private HttpRequest post(URI uri, byte[] pcm) {
        return HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(pcm)).build();
    }
}
