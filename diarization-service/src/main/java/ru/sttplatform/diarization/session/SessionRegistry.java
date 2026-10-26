package ru.sttplatform.diarization.session;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.diarization.config.DiarizationProperties;

import java.util.Map;

/**
 * Реестр единственной активной сессии: на машину приходится одна пара моделей и один worker.
 *
 * @version 1.0.0
 */
@Component
public class SessionRegistry {
    private final DiarizationProperties props;
    private volatile DiarizationSession current;

    /**
     * Создаёт реестр.
     *
     * @param props настройки сервиса
     */
    public SessionRegistry(DiarizationProperties props) {
        this.props = props;
    }

    /**
     * Начинает новую сессию, заменяя завершённую или ожидающую повторной записи эталона.
     *
     * @return новая сессия
     * @throws Exception если модели не загрузились
     */
    public synchronized DiarizationSession start() throws Exception {
        DiarizationSession active = current;
        if (active != null && !active.terminal() && !active.retryable())
            throw new SessionException(409, "Интервью уже запущено.");
        if (active != null) active.dispose();
        current = new DiarizationSession(props);
        return current;
    }

    /**
     * Находит сессию по идентификатору.
     *
     * @param id идентификатор из URL или заголовка {@code X-Session-Id}
     * @return сессия
     */
    public DiarizationSession require(String id) {
        DiarizationSession active = current;
        if (active == null) throw new SessionException(409, "Сначала начните запись эталона.");
        if (!active.id().equals(id)) throw new SessionException(403, "Неверный идентификатор интервью.");
        return active;
    }

    /**
     * Состояние текущей сессии.
     *
     * @param token идентификатор клиента (может быть {@code null})
     * @return состояние или {@code {phase: idle}}
     */
    public Map<String, Object> status(String token) {
        DiarizationSession active = current;
        return active == null ? Json.map("phase", "idle") : active.status(token);
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    void watchdog() {
        DiarizationSession active = current;
        if (active != null) active.watchdog();
    }

    @PreDestroy
    void shutdown() {
        DiarizationSession active = current;
        if (active != null) {
            active.requestStop("server_shutdown");
            active.dispose();
        }
    }
}
