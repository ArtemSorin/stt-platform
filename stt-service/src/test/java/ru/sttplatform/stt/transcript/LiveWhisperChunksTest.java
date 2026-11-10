package ru.sttplatform.stt.transcript;

import org.junit.jupiter.api.Test;
import ru.sttplatform.stt.whisper.WhisperClient;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LiveWhisperChunksTest {
    @Test void dispatchesAtThirtySecondsAndReusesResultsAfterRetraction() throws Exception {
        WhisperClient client = mock(WhisperClient.class);
        AtomicInteger calls = new AtomicInteger();
        when(client.post(eq("/transcribe-json"), any(byte[].class))).thenAnswer(call -> {
            calls.incrementAndGet();
            return new WhisperClient.Reply(200, "{\"words\":[{\"word\":\"да\",\"start\":0,\"end\":0.1}]}");
        });
        var attempt = new LiveWhisperChunks(client, Runnable::run, 100);
        attempt.append(100, new byte[29 * 32_000]);
        assertEquals(0, calls.get());
        attempt.append(100 + 29 * 16_000L, new byte[32_000]);
        assertEquals(1, calls.get());
        attempt.append(100 + 30 * 16_000L, new byte[32_000]);
        var firstDecision = attempt.snapshot();
        assertEquals(2, calls.get());
        attempt.append(100 + 31 * 16_000L, new byte[32_000]);
        var continued = attempt.snapshot();
        assertSame(firstDecision.get(0), continued.get(0));
        assertEquals(3, calls.get());
        assertEquals(31 * 16_000L, continued.get(2).get().offset());
        verify(client, times(3)).post(eq("/transcribe-json"), any(byte[].class));
    }
}
