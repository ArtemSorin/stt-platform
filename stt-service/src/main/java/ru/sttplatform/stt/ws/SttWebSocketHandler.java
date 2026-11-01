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
 * @version 1.0.0
 */
@Component
public class SttWebSocketHandler extends AbstractWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(SttWebSocketHandler.class);
    private static final int STATS_EVERY_FRAMES = 20;

    /** Состояние одного соединения.
     * @version 1.0.0
     */
    private static final class State {
        final SpeechSession speech;
        final boolean background;
        double rtf;
        int frames;
        State(SpeechSession speech, boolean background) { this.speech = speech; this.background = background; }
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

    /** Создаёт независимую STT-сессию для подключённого клиента.
     * @param session WebSocket-сессия клиента
     * @throws Exception если не удалось открыть распознаватель Vosk
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), new State(factory.create(),
                "microphone".equals(session.getHandshakeHeaders().getFirst("X-Audio-Source"))));
        log.info("STT-клиент подключён: {}", session.getId());
    }

    /** Принимает бинарный PCM16-кадр и передаёт его распознавателю сессии.
     * @param session WebSocket-сессия клиента
     * @param message сообщение с PCM-данными
     * @throws IOException при ошибке отправки ответа клиенту
     */
    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        State state = sessions.get(session.getId());
        if (state == null) return;
        ByteBuffer buf = message.getPayload();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);

        long t0 = System.nanoTime();
        Thread thread = Thread.currentThread();
        int previousPriority = thread.getPriority();
        boolean changedPriority = false;
        SpeechSession.Event event;
        try {
            // При нагрузке распознавание интервьюера уступает процессор голосу кандидата.
            if (state.background && previousPriority > Thread.MIN_PRIORITY) {
                try { thread.setPriority(Thread.MIN_PRIORITY); changedPriority = true; }
                catch (SecurityException ignored) { /* Среда может запрещать изменение приоритета. */ }
            }
            event = state.speech.accept(data, data.length);
        } finally {
            if (changedPriority) try { thread.setPriority(previousPriority); } catch (SecurityException ignored) {}
        }
        double ratio = ((System.nanoTime() - t0) / 1e6) / Math.max(1.0, data.length / (double) Pcm.BYTES_PER_MS);
        state.rtf = state.frames == 0 ? ratio : 0.9 * state.rtf + 0.1 * ratio;
        state.frames++;

        if (event != null) send(session, mapper.writeValueAsString(event));
        if (state.frames % STATS_EVERY_FRAMES == 0)
            send(session, Json.write(Json.map("type", "stats", "rtf", Json.round(state.rtf, 3))));
    }

    /** Обрабатывает управляющую текстовую команду клиента.
     * @param session WebSocket-сессия клиента
     * @param message команда, например {@code flush}
     * @throws IOException при ошибке отправки финального результата
     */
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

    /** Записывает транспортную ошибку WebSocket для диагностики.
     * @param session WebSocket-сессия, в которой произошла ошибка
     * @param exception причина транспортной ошибки
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Ошибка WebSocket {}: {}", session.getId(), exception.toString());
    }

    /** Закрывает распознаватель после отключения клиента.
     * @param session завершённая WebSocket-сессия
     * @param status причина закрытия соединения
     */
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
