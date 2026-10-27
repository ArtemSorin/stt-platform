package ru.sttplatform.analytics.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Проверяет общий секрет до открытия внутреннего WebSocket STT → аналитика.
 * @version 1.0.0
 */
@Component
public final class InternalEventsHandshakeInterceptor implements HandshakeInterceptor {
    private final byte[] expectedToken;

    /** Сохраняет общий секрет только для проверки handshake.
     * @param token секрет из конфигурации сервиса
     */
    public InternalEventsHandshakeInterceptor(
            @Value("${interview.events.token:${INTERVIEW_EVENTS_TOKEN:}}") String token) {
        expectedToken = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    /** Проверяет общий токен до установки внутреннего WebSocket-соединения.
     * @param request входящий HTTP-запрос handshake
     * @param response HTTP-ответ handshake
     * @param handler WebSocket-обработчик маршрута
     * @param attributes атрибуты будущей WebSocket-сессии
     * @return {@code true}, если токен совпал и соединение разрешено
     */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        String supplied = request.getHeaders().getFirst("X-Internal-Token");
        byte[] actual = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        boolean accepted = expectedToken.length >= 32 && MessageDigest.isEqual(expectedToken, actual);
        if (!accepted) response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return accepted;
    }

    /** Завершает handshake без сохранения переданного секрета.
     * @param request исходный HTTP-запрос
     * @param response ответ handshake
     * @param handler WebSocket-обработчик маршрута
     * @param exception ошибка handshake или {@code null} при успехе
     */
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
        // Секрет не сохраняется после проверки handshake.
    }
}
