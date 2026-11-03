package ru.sttplatform.stt.transcript;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.sttplatform.stt.whisper.WhisperClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PostInterviewWhisperTest {
    @TempDir Path directory;

    @Test void processesEveryConfirmedSecondAfterCaptureInShortAbsoluteTimeChunks() throws Exception {
        WhisperClient client = mock(WhisperClient.class);
        AtomicInteger calls = new AtomicInteger();
        when(client.post(eq("/transcribe-json"), any(byte[].class))).thenAnswer(invocation -> {
            byte[] pcm = invocation.getArgument(1);
            assertTrue(pcm.length <= 13 * 16_000 * 2);
            calls.incrementAndGet();
            return new WhisperClient.Reply(200,
                    "{\"words\":[{\"word\":\"слово\",\"start\":1.0,\"end\":1.2,\"confidence\":0.9}]}");
        });
        try (PostInterviewWhisper store = new PostInterviewWhisper(directory)) {
            for (int second = 0; second < 30; second++)
                store.append(second * 16_000L, new byte[16_000 * 2]);
            verifyNoInteractions(client);
            PostInterviewWhisper.Result result = store.transcribe(
                    List.of(new PostInterviewWhisper.Range(2 * 16_000L, 28 * 16_000L)), client);
            assertEquals(3, calls.get());
            assertEquals(List.of(3 * 16_000L, 14 * 16_000L + 8_000L, 26 * 16_000L + 8_000L),
                    result.words().stream().map(PostInterviewWhisper.Word::start).toList());
            assertEquals(3, result.chunks().size());
            assertEquals(3, result.words().size());
        }
        assertFalse(Files.exists(directory.resolve(".whisper-source.pcm.tmp")));
    }

    @Test void keepsIntervalsSeparateAndRejectsBrokenAudioTimeline() throws Exception {
        try (PostInterviewWhisper store = new PostInterviewWhisper(directory)) {
            store.append(0, new byte[32_000]);
            assertThrows(java.io.IOException.class, () -> store.append(32_000, new byte[2]));
        }
    }
}
