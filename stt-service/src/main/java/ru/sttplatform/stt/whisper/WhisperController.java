package ru.sttplatform.stt.whisper;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.sttplatform.common.json.Json;

import java.io.IOException;
import java.util.Map;

/**
 * Публичный контракт Whisper для других микросервисов: тело запроса — сырой PCM 16 кГц / 16 бит / моно.
 *
 * @version 2.0.0
 */
@RestController
@RequestMapping("/api/whisper")
public class WhisperController {
    private final WhisperClient whisper;

    /**
     * Создаёт контроллер.
     *
     * @param whisper клиент Python-сервера
     */
    public WhisperController(WhisperClient whisper) {
        this.whisper = whisper;
    }

    /**
     * Состояние второго прохода.
     *
     * @return {@code configured} (задан адрес) и {@code reachable} (сервер отвечает)
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Json.map("configured", whisper.configured(), "reachable", whisper.reachable(), "url", whisper.baseUrl());
    }

    /**
     * Текст ответа.
     *
     * @param pcm PCM без заголовка
     * @return текст UTF-8
     */
    @PostMapping(value = "/transcribe", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<String> transcribe(@RequestBody byte[] pcm) {
        return forward("/transcribe", pcm, MediaType.parseMediaType("text/plain;charset=UTF-8"));
    }

    /**
     * Текст и слова с временными метками.
     *
     * @param pcm PCM без заголовка
     * @return JSON {@code {text, words[]}}
     */
    @PostMapping(value = "/transcribe-json", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<String> transcribeJson(@RequestBody byte[] pcm) {
        return forward("/transcribe-json", pcm, MediaType.APPLICATION_JSON);
    }

    private ResponseEntity<String> forward(String path, byte[] pcm, MediaType type) {
        if (!whisper.configured())
            return ResponseEntity.status(503).contentType(MediaType.TEXT_PLAIN).body("Whisper не настроен (whisper.url пуст).");
        try {
            WhisperClient.Reply reply = whisper.post(path, pcm);
            return ResponseEntity.status(reply.status()).contentType(type).body(reply.body());
        } catch (IOException e) {
            return ResponseEntity.status(502).contentType(MediaType.TEXT_PLAIN).body("Whisper недоступен: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(503).contentType(MediaType.TEXT_PLAIN).body("Запрос прерван.");
        }
    }
}
