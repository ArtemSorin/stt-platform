package ru.sttplatform.diarization.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.diarization.session.DiarizationSession;
import ru.sttplatform.diarization.session.SessionException;
import ru.sttplatform.diarization.session.SessionRegistry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * {@code /ws/diarization/{sessionId}} — живой звук интервью.
 * <pre>
 *  клиент → сервер (binary): смешанный моно PCM16 16 кГц, кадры до 0,64 с (обычно по 160 мс)
 *  сервер → клиент (text):   {"type":"diarization","role","audio_seconds","segments":[…],"active_segments":[…]}
 *                            {"type":"finished","phase","error","audio_seconds"}
 *                            {"type":"error","message"} — кадр отклонён (например, переполнена очередь)
 * </pre>
 *
 * @version 1.0.0
 */
@Component
public class DiarizationWebSocketHandler extends AbstractWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(DiarizationWebSocketHandler.class);
    private static final String SESSION = "diarizationSession";

    private final SessionRegistry registry;

    /**
     * Создаёт обработчик.
     *
     * @param registry реестр сессий
     */
    public DiarizationWebSocketHandler(SessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession ws) throws Exception {
        String path = ws.getUri() == null ? "" : ws.getUri().getPath();
        String id = path.substring(path.lastIndexOf('/') + 1);
        try {
            DiarizationSession session = registry.require(id);
            ws.getAttributes().put(SESSION, session);
            session.setListener(event -> send(ws, event));
        } catch (SessionException e) {
            send(ws, Json.map("type", "error", "message", e.getMessage()));
            ws.close(CloseStatus.POLICY_VIOLATION);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession ws, BinaryMessage message) {
        DiarizationSession session = (DiarizationSession) ws.getAttributes().get(SESSION);
        if (session == null) return;
        ByteBuffer buf = message.getPayload();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        try {
            session.liveAudio(data);
        } catch (SessionException e) {
            send(ws, Json.map("type", "error", "message", e.getMessage()));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) {
        // управляющих сообщений нет: остановка идёт через REST POST /api/sessions/{id}/finish
    }

    @Override
    public void handleTransportError(WebSocketSession ws, Throwable ex) {
        log.warn("Ошибка WebSocket {}: {}", ws.getId(), ex.toString());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        DiarizationSession session = (DiarizationSession) ws.getAttributes().get(SESSION);
        if (session != null) session.setListener(null);
    }

    private void send(WebSocketSession ws, Map<String, Object> event) {
        try {
            String json = Json.write(event);
            synchronized (ws) {
                if (ws.isOpen()) ws.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
