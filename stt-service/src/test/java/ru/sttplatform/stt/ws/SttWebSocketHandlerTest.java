package ru.sttplatform.stt.ws;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;
import ru.sttplatform.stt.vosk.SpeechSession;
import ru.sttplatform.stt.vosk.SpeechSessionFactory;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SttWebSocketHandlerTest {
    @Test
    void microphone_recognition_temporarily_yields_thread_priority() throws Exception {
        var factory = mock(SpeechSessionFactory.class);
        var speech = mock(SpeechSession.class);
        when(factory.create()).thenReturn(speech);
        var session = mock(WebSocketSession.class);
        var headers = new HttpHeaders();
        headers.add("X-Audio-Source", "microphone");
        when(session.getHandshakeHeaders()).thenReturn(headers);
        when(session.getId()).thenReturn("mic");
        AtomicInteger actual = new AtomicInteger();
        when(speech.accept(any(byte[].class), anyInt())).thenAnswer(call -> {
            actual.set(Thread.currentThread().getPriority());
            return null;
        });
        int before = Thread.currentThread().getPriority();
        var handler = new SttWebSocketHandler(factory);
        handler.afterConnectionEstablished(session);
        handler.handleBinaryMessage(session, new BinaryMessage(new byte[]{0, 0}));
        assertEquals(Thread.MIN_PRIORITY, actual.get());
        assertEquals(before, Thread.currentThread().getPriority());
    }
}
