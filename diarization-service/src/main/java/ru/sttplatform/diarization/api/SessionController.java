package ru.sttplatform.diarization.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.diarization.session.DiarizationSession;
import ru.sttplatform.diarization.session.SessionException;
import ru.sttplatform.diarization.session.SessionRegistry;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST-управление сессией диаризации. Живой звук идёт отдельно — по WebSocket {@code /ws/diarization/{id}}.
 *
 * @version 1.0.0
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {
    private static final Set<String> STOP_REASONS =
            Set.of("requested", "system_audio_lost", "microphone_lost", "capture_error");

    private final SessionRegistry registry;

    /**
     * Создаёт контроллер.
     *
     * @param registry реестр сессий
     */
    public SessionController(SessionRegistry registry) {
        this.registry = registry;
    }

    /**
     * Начинает сессию и загружает модели.
     *
     * @return состояние (фаза enrolling) с {@code session_id}
     * @throws Exception если модели не загрузились
     */
    @PostMapping
    public Map<String, Object> start() throws Exception {
        DiarizationSession session = registry.start();
        return session.status(session.id());
    }

    /**
     * Состояние текущей сессии.
     *
     * @param token заголовок {@code X-Session-Id}; совпадение продлевает сессию
     * @return состояние или {@code {phase: idle}}
     */
    @GetMapping("/current")
    public Map<String, Object> current(@RequestHeader(value = "X-Session-Id", required = false) String token) {
        return registry.status(token);
    }

    /**
     * Состояние сессии по идентификатору.
     *
     * @param id идентификатор сессии
     * @return состояние
     */
    @GetMapping("/{id}")
    public Map<String, Object> status(@PathVariable String id) {
        return registry.require(id).status(id);
    }

    /**
     * Принимает кусок аудио эталона.
     *
     * @param id идентификатор сессии
     * @param sequence порядковый номер блока с нуля
     * @param pcm PCM16 16 кГц моно
     * @return {@code {ok: true}}
     */
    @PostMapping("/{id}/enrollment/audio")
    public Map<String, Object> enrollmentAudio(@PathVariable String id,
                                               @RequestHeader("X-Sequence") long sequence,
                                               @RequestBody byte[] pcm) {
        registry.require(id).enrollAudio(sequence, pcm);
        return Json.map("ok", true);
    }

    /**
     * Завершает запись эталона.
     *
     * @param id идентификатор сессии
     * @return состояние: live — интервью началось, retry — эталон не получился
     * @throws Exception если не удалось подготовить файлы результата
     */
    @PostMapping("/{id}/enrollment/finish")
    public Map<String, Object> finishEnrollment(@PathVariable String id) throws Exception {
        DiarizationSession session = registry.require(id);
        session.finishEnrollment();
        return session.status(id);
    }

    /**
     * Запрашивает остановку интервью.
     *
     * @param id идентификатор сессии
     * @param reason причина остановки
     * @return состояние (возможна промежуточная фаза stopping)
     */
    @PostMapping("/{id}/finish")
    public Map<String, Object> finish(@PathVariable String id,
                                      @RequestHeader(value = "X-Stop-Reason", defaultValue = "requested") String reason) {
        if (!STOP_REASONS.contains(reason)) throw new SessionException(400, "Неверная причина завершения.");
        DiarizationSession session = registry.require(id);
        session.requestStop(reason);
        return session.status(id);
    }

    /**
     * Все подтверждённые сегменты ролей на текущий момент.
     *
     * @param id идентификатор сессии
     * @return список сегментов
     */
    @GetMapping("/{id}/segments")
    public List<Map<String, Object>> segments(@PathVariable String id) {
        return registry.require(id).segments();
    }

    /**
     * Преобразует ошибки действий в JSON {@code {error}}.
     *
     * @param error ошибка действия
     * @return ответ с кодом ошибки
     */
    @ExceptionHandler(SessionException.class)
    public ResponseEntity<Map<String, Object>> handle(SessionException error) {
        return ResponseEntity.status(error.code()).body(Json.map("error", error.getMessage()));
    }
}
