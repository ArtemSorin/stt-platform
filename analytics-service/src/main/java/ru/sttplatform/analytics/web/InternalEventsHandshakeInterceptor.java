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

/** Проверяет общий секрет до открытия внутреннего WebSocket STT → аналитика. */
@Component
public final class InternalEventsHandshakeInterceptor implements HandshakeInterceptor {
    private final byte[] expectedToken;

    public InternalEventsHandshakeInterceptor(
            @Value("${interview.events.token:${INTERVIEW_EVENTS_TOKEN:}}") String token) {
        expectedToken = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        String supplied = request.getHeaders().getFirst("X-Internal-Token");
        byte[] actual = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        boolean accepted = expectedToken.length >= 32 && MessageDigest.isEqual(expectedToken, actual);
        if (!accepted) response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return accepted;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
        // Секрет не сохраняется после проверки handshake.
    }
}
