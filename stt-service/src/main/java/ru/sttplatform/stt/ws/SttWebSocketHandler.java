package ru.sttplatform.stt.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import ru.sttplatform.common.audio.Pcm;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.stt.vosk.SpeechSession;
import ru.sttplatform.stt.vosk.SpeechSessionFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /ws/stt} — потоковое распознавание Vosk, по одной сессии распознавателя на соединение.
 * <pre>
 *  клиент → сервер: binary — PCM 16 кГц / 16 бит / моно;
 *                   text "flush" — дораспознать остаток и продолжить (ответ {"type":"final","flush":true,...});
 *                   text "eof"   — дораспознать, прислать последний final и закрыть соединение
 *  сервер → клиент: {"type":"partial"|"final","text":"...","words":[...]};
 *                   {"type":"stats","rtf":0.42} — доля времени звука, которую занимает распознавание (каждые 20 кадров)
 * </pre>
 *
 * @version 2.0.0
 */
@Component
public class SttWebSocketHandler extends AbstractWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(SttWebSocketHandler.class);
    private static final int STATS_EVERY_FRAMES = 20;

    /** Состояние одного соединения. */
    private static final class State {
        final SpeechSession speech;
        double rtf;
        int frames;
        State(SpeechSession speech) { this.speech = speech; }
    }

    private final SpeechSessionFactory factory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, State> sessions = new ConcurrentHashMap<>();

    /**
     * Создаёт обработчик.
     *
     * @param factory фабрика сессий Vosk
     */
    public SttWebSocketHandler(SpeechSessionFactory factory) {
        this.factory = factory;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), new State(factory.create()));
        log.info("STT-клиент подключён: {}", session.getId());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        State state = sessions.get(session.getId());
        if (state == null) return;
        ByteBuffer buf = message.getPayload();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);

        long t0 = System.nanoTime();
        SpeechSession.Event event = state.speech.accept(data, data.length);
        double ratio = ((System.nanoTime() - t0) / 1e6) / Math.max(1.0, data.length / (double) Pcm.BYTES_PER_MS);
        state.rtf = state.frames == 0 ? ratio : 0.9 * state.rtf + 0.1 * ratio;
        state.frames++;

        if (event != null) send(session, mapper.writeValueAsString(event));
        if (state.frames % STATS_EVERY_FRAMES == 0)
            send(session, Json.write(Json.map("type", "stats", "rtf", Json.round(state.rtf, 3))));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        State state = sessions.get(session.getId());
        if (state == null) return;
        String command = message.getPayload().trim();
        if ("flush".equals(command)) {
            SpeechSession.Event fin = state.speech.finish();
            Map<String, Object> reply = Json.map("type", "final", "text", fin.text(), "words", fin.words(), "flush", true);
            send(session, Json.write(reply));
        } else if ("eof".equals(command)) {
            send(session, mapper.writeValueAsString(state.speech.finish()));
            session.close(CloseStatus.NORMAL);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Ошибка WebSocket {}: {}", session.getId(), exception.toString());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        State state = sessions.remove(session.getId());
        if (state != null) state.speech.close();
        log.info("STT-клиент отключён: {}", session.getId());
    }

    private void send(WebSocketSession session, String json) throws IOException {
        synchronized (session) {
            if (session.isOpen()) session.sendMessage(new TextMessage(json));
        }
    }
}
