package ru.sttplatform.gateway.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.gateway.clients.ApiException;
import ru.sttplatform.gateway.config.GatewayProperties;
import ru.sttplatform.gateway.session.SessionManager;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * HTTP API для браузерной страницы интервью. Контракт совпадает с прежним {@code BrowserMode}
 * на порту 8080. Заголовки: {@code X-Session-Id},
 * {@code X-Sequence}, {@code X-Event-Id}, {@code X-Stop-Reason}.
 *
 * @version 1.0.0
 */
@RestController
public class BrowserApiController {
    /** Максимум байт одного аудиозапроса: 0,64 с PCM16 16 кГц. */
    private static final int MAX_AUDIO_BYTES = 2 * 10240;
    private static final int MAX_CHANNEL_AUDIO_BYTES = 2 * MAX_AUDIO_BYTES;
    private static final Set<String> STOP_REASONS = Set.of("requested", "system_audio_lost", "microphone_lost", "capture_error");

    private final SessionManager sessions;
    private final String analyticsUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /**
     * Создаёт контроллер.
     *
     * @param sessions менеджер сессий
     * @param props адреса сервисов
     */
    public BrowserApiController(SessionManager sessions, GatewayProperties props) {
        this.sessions = sessions;
        this.analyticsUrl = props.analyticsUrl().replaceAll("/+$", "");
    }

    /** Направляет браузер на основной сценарий интервью с записью эталона и диаризацией.
     * @return перенаправление на страницу основного сценария
     */
    @GetMapping("/")
    public ResponseEntity<Void> home() {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/integrated.html")).build();
    }

    /**
     * Состояние интервью.
     *
     * @param request запрос (для проверки источника)
     * @param token идентификатор сессии
     * @return состояние
     */
    @GetMapping("/api/status")
    public Map<String, Object> status(HttpServletRequest request,
                                      @RequestHeader(value = "X-Session-Id", required = false) String token) {
        checkOrigin(request);
        return sessions.status(token);
    }

    /**
     * Начинает запись эталона.
     *
     * @param request запрос
     * @return состояние с {@code session_id}
     */
    @PostMapping("/api/enrollment/start")
    public Map<String, Object> startEnrollment(HttpServletRequest request) {
        checkOrigin(request);
        return sessions.start();
    }

    /** Запускает тест аналитики без записи эталона и диаризации.
     * @param request HTTP-запрос с необязательными настройками теста
     * @return состояние созданной тестовой сессии
     * @throws IOException если не удалось прочитать тело запроса
     */
    @PostMapping("/api/test/start")
    public Map<String, Object> startTest(HttpServletRequest request) throws IOException {
        checkOrigin(request);
        var body = Json.MAPPER.readTree(request.getInputStream());
        return sessions.startTest(body.path("candidate").asText(""), body.path("whisperEnabled").asBoolean(false));
    }

    /**
     * Кусок аудио эталона.
     *
     * @param request запрос с телом PCM
     * @param token идентификатор сессии
     * @param sequence номер блока
     * @return {@code {ok: true}}
     * @throws IOException при ошибке чтения тела
     */
    @PostMapping("/api/enrollment/audio")
    public Map<String, Object> enrollmentAudio(HttpServletRequest request,
                                               @RequestHeader(value = "X-Session-Id", required = false) String token,
                                               @RequestHeader(value = "X-Sequence", required = false) String sequence) throws IOException {
        checkOrigin(request);
        sessions.enrollAudio(token, number(sequence), readAudio(request));
        return Json.map("ok", true);
    }

    /**
     * Завершение записи эталона.
     *
     * @param request запрос
     * @param token идентификатор сессии
     * @param audioMode формат кадров после записи эталона
     * @return состояние
     */
    @PostMapping("/api/enrollment/finish")
    public Map<String, Object> finishEnrollment(HttpServletRequest request,
                                                @RequestHeader(value = "X-Session-Id", required = false) String token,
                                                @RequestHeader(value = "X-Audio-Mode", defaultValue = "mono") String audioMode) {
        checkOrigin(request);
        return sessions.finishEnrollment(token, audioMode);
    }

    /**
     * Кадр интервью (смешанный микрофон + звук компьютера).
     *
     * @param request запрос с телом PCM
     * @param token идентификатор сессии
     * @param sequence номер кадра
     * @return {@code {ok: true}}
     * @throws IOException при ошибке чтения тела
     */
    @PostMapping("/api/audio")
    public Map<String, Object> audio(HttpServletRequest request,
                                     @RequestHeader(value = "X-Session-Id", required = false) String token,
                                     @RequestHeader(value = "X-Sequence", required = false) String sequence) throws IOException {
        checkOrigin(request);
        sessions.liveAudio(token, number(sequence), readAudio(request));
        return Json.map("ok", true);
    }

    /** Принимает синхронную пару PCM16: сначала системный звук, затем микрофон.
     * @param request запрос с чередующимися сэмплами двух каналов
     * @param token идентификатор интервью
     * @param sequence номер пары кадров
     * @return подтверждение приёма
     * @throws IOException при ошибке чтения тела
     */
    @PostMapping("/api/audio/channels")
    public Map<String, Object> audioChannels(HttpServletRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String token,
            @RequestHeader(value = "X-Sequence", required = false) String sequence) throws IOException {
        checkOrigin(request);
        byte[] bytes = request.getInputStream().readNBytes(MAX_CHANNEL_AUDIO_BYTES + 1);
        if (bytes.length < 4 || bytes.length > MAX_CHANNEL_AUDIO_BYTES || bytes.length % 4 != 0)
            throw new ApiException(400, "Ожидаются два канала PCM16 16 кГц, до 0,64 с на источник.");
        sessions.liveAudioChannels(token, number(sequence), bytes);
        return Json.map("ok", true);
    }

    /**
     * Вопрос задан.
     *
     * @param request запрос
     * @param token идентификатор сессии
     * @return состояние
     */
    @PostMapping("/api/question/asked")
    public Map<String, Object> questionAsked(HttpServletRequest request,
                                             @RequestHeader(value = "X-Session-Id", required = false) String token) {
        checkOrigin(request);
        sessions.questionAsked(token);
        return sessions.status(token);
    }

    /**
     * Вопрос пропущен.
     *
     * @param request запрос
     * @param token идентификатор сессии
     * @return состояние
     */
    @PostMapping("/api/question/skip")
    public Map<String, Object> questionSkip(HttpServletRequest request,
                                            @RequestHeader(value = "X-Session-Id", required = false) String token) {
        checkOrigin(request);
        sessions.questionSkipped(token);
        return sessions.status(token);
    }

    /**
     * Событие озвучки вопроса.
     *
     * @param request запрос с JSON {@code {event, question, voice, voice_lang}}
     * @param token идентификатор сессии
     * @return {@code {ok: true}}
     * @throws IOException если тело не является JSON
     */
    @PostMapping("/api/tts/event")
    public Map<String, Object> ttsEvent(HttpServletRequest request,
                                        @RequestHeader(value = "X-Session-Id", required = false) String token) throws IOException {
        checkOrigin(request);
        var event = Json.MAPPER.readTree(request.getInputStream());
        sessions.ttsEvent(token, event.path("event").asText("unknown"), event.path("question").asText(""),
                event.path("voice").asText(""), event.path("voice_lang").asText(""));
        return Json.map("ok", true);
    }

    /**
     * Подсказка показана на экране.
     *
     * @param request запрос
     * @param token идентификатор сессии
     * @param eventId идентификатор события
     * @return {@code {ok: true}}
     */
    @PostMapping("/api/ui/seen")
    public Map<String, Object> uiSeen(HttpServletRequest request,
                                      @RequestHeader(value = "X-Session-Id", required = false) String token,
                                      @RequestHeader(value = "X-Event-Id", required = false) String eventId) {
        checkOrigin(request);
        sessions.suggestionSeen(token, eventId);
        return Json.map("ok", true);
    }

    /**
     * Завершение интервью.
     *
     * @param request запрос
     * @param token идентификатор сессии
     * @param reason причина завершения
     * @return состояние (возможна фаза stopping)
     */
    @PostMapping("/api/finish")
    public Map<String, Object> finish(HttpServletRequest request,
                                      @RequestHeader(value = "X-Session-Id", required = false) String token,
                                      @RequestHeader(value = "X-Stop-Reason", defaultValue = "requested") String reason) {
        checkOrigin(request);
        if (!STOP_REASONS.contains(reason)) throw new ApiException(400, "Неверная причина завершения.");
        return sessions.finish(token, reason);
    }

    /**
     * Скачивание Excel-отчёта: проксирует файл из analytics-service.
     *
     * @param name имя файла
     * @return XLSX или 404
     */
    @GetMapping("/api/reports/{name:.+}")
    public ResponseEntity<byte[]> report(@PathVariable String name) {
        try {
            HttpResponse<byte[]> reply = http.send(HttpRequest.newBuilder(
                            URI.create(analyticsUrl + "/api/reports/" + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20")))
                    .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (reply.statusCode() != 200) return ResponseEntity.status(reply.statusCode()).build();
            ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
            reply.headers().firstValue(HttpHeaders.CONTENT_TYPE).ifPresent(v -> ok.header(HttpHeaders.CONTENT_TYPE, v));
            ok.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString());
            return ok.body(reply.body());
        } catch (IOException e) {
            return ResponseEntity.status(502).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(503).build();
        }
    }

    /**
     * Преобразует ошибки в JSON {@code {error}}.
     *
     * @param error ошибка действия
     * @return ответ с кодом ошибки
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handle(ApiException error) {
        return ResponseEntity.status(error.code()).body(Json.map("error", error.getMessage()));
    }

    /**
     * Непредвиденные ошибки тоже возвращаются JSON-ом: клиент всегда ждёт {@code {error}}.
     *
     * @param error ошибка
     * @return ответ 500
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAny(Exception error) {
        return ResponseEntity.status(500).body(Json.map("error", error.getMessage() == null ? error.toString() : error.getMessage()));
    }

    private static long number(String value) {
        if (value == null) throw new ApiException(400, "Отсутствует номер чанка.");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ApiException(400, "Неверный номер чанка.");
        }
    }

    private static byte[] readAudio(HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(MAX_AUDIO_BYTES + 2);
        if (bytes.length < 2 || bytes.length > MAX_AUDIO_BYTES || bytes.length % 2 != 0)
            throw new ApiException(400, "Ожидается PCM16 16 кГц, до 0,64 с на источник.");
        return bytes;
    }

    /** Запросы принимаются только с той же страницы: чужой {@code Origin} отклоняется. */
    private static void checkOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null) return;
        try {
            if (!URI.create(origin).getHost().equalsIgnoreCase(request.getServerName()))
                throw new ApiException(403, "Недопустимый источник запроса.");
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(403, "Недопустимый источник запроса.");
        }
    }
}
