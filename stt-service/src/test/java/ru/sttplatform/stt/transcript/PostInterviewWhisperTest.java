package ru.sttplatform.stt.transcript;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.sttplatform.stt.whisper.WhisperClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PostInterviewWhisperTest {
    @TempDir Path directory;

    @Test void recordsAlignedChannelsAndProcessesEverySecondOnlyAfterSeal() throws Exception {
        WhisperClient client = mock(WhisperClient.class);
        List<Integer> seen = new ArrayList<>();
        when(client.post(eq("/transcribe-json"), any(byte[].class))).thenAnswer(call -> {
            byte[] pcm = call.getArgument(1);
            assertTrue(pcm.length <= 12 * 16_000 * 2);
            seen.add((int) pcm[0]);
            return new WhisperClient.Reply(200, "{\"words\":[{\"word\":\"слово\",\"start\":1,\"end\":1.2}]}");
        });
        Path path;
        try (InterviewAudioSpool source = new InterviewAudioSpool(directory, "session", true)) {
            path = source.path();
            for (int second = 0; second < 25; second++) {
                byte[] system = new byte[32_000], mic = new byte[32_000];
                java.util.Arrays.fill(system, (byte) 1); java.util.Arrays.fill(mic, (byte) 2);
                source.append(second * 16_000L, system, mic);
            }
            verifyNoInteractions(client);
            source.seal(25 * 16_000L);
            assertEquals(25L * 16_000 * 4, Files.size(path));
            assertEquals(1, source.read(0, 0, 1)[0]);
            assertEquals(2, source.read(1, 0, 1)[0]);
            List<Integer> progress = new ArrayList<>();
            var result = new PostInterviewWhisper(source).transcribe(List.of(), client, (done, total) -> {
                assertEquals(6, total); progress.add(done);
            });
            assertEquals(List.of(1, 1, 1, 2, 2, 2), seen);
            assertEquals(List.of(0, 1, 2, 3, 4, 5, 6), progress);
            assertEquals(List.of(16_000L, 184_000L, 360_000L, 16_000L, 184_000L, 360_000L),
                    result.words().stream().map(PostInterviewWhisper.Word::start).toList());
            source.delete();
        }
        assertFalse(Files.exists(path));
    }

    @Test void retriesAnEmptySpeechChunkAndRejectsRepeatedFailureButAcceptsSilence() throws Exception {
        WhisperClient client = mock(WhisperClient.class);
        when(client.post(anyString(), any(byte[].class))).thenReturn(new WhisperClient.Reply(200, "{\"words\":[]}"));
        try (InterviewAudioSpool source = new InterviewAudioSpool(directory, "session", false)) {
            source.append(0, new byte[32_000], null); source.seal(16_000);
            var pass = new PostInterviewWhisper(source);
            assertTrue(pass.transcribe(List.of(), client, (a, b) -> {}).words().isEmpty());
            assertThrows(IOException.class, () -> pass.transcribe(
                    List.of(new PostInterviewWhisper.SpeechEvidence(0, 0, 16_000)), client, (a, b) -> {}));
            assertThrows(IOException.class, () -> pass.transcribe(
                    List.of(new PostInterviewWhisper.SpeechEvidence(0, 4_000, 5_600, 800)), client, (a, b) -> {}));
            verify(client, times(5)).post(anyString(), any(byte[].class));
        }
    }

    @Test void retriesInvalidResponsesAndDoesNotDuplicateBoundaryWord() throws Exception {
        WhisperClient client = mock(WhisperClient.class);
        AtomicInteger calls = new AtomicInteger();
        when(client.post(anyString(), any(byte[].class))).thenAnswer(call -> switch (calls.getAndIncrement()) {
            case 0 -> new WhisperClient.Reply(500, "error");
            case 1 -> new WhisperClient.Reply(200, "{\"words\":[{\"word\":\"граница\",\"start\":10.8,\"end\":11.18,\"confidence\":0.8}]}");
            default -> new WhisperClient.Reply(200, "{\"words\":[{\"word\":\"граница\",\"start\":0.34,\"end\":0.7,\"confidence\":0.9}]}");
        });
        try (InterviewAudioSpool source = new InterviewAudioSpool(directory, "session", false)) {
            source.append(0, new byte[24 * 32_000], null); source.seal(24 * 16_000L);
            var result = new PostInterviewWhisper(source).transcribe(List.of(), client, (a, b) -> {});
            assertEquals(2, result.words().size()); // один раз на первой границе и ещё слово в последней части
            assertEquals(0.9, result.words().get(0).confidence());
        }
    }

    @Test void rejectsOutOfOrderAndIncompleteFrames() throws Exception {
        try (InterviewAudioSpool source = new InterviewAudioSpool(directory, "session", true)) {
            source.append(0, new byte[]{1, 2}, new byte[]{3, 4});
            source.append(2, new byte[]{1, 2}, new byte[]{3, 4});
            assertThrows(IOException.class, () -> source.seal(2));
        }
    }

    @Test void overflowAndDiskErrorsMakeRecordingUnavailable() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var source = new InterviewAudioSpool(directory, "overflow", false, 1, path -> new java.io.OutputStream() {
            @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                started.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            }
        })) {
            source.append(0, new byte[32_000], null);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            source.append(16_000, new byte[32_000], null);
            source.append(32_000, new byte[32_000], null);
            release.countDown();
            assertThrows(IOException.class, () -> source.seal(48_000));
        }
        try (var source = new InterviewAudioSpool(directory, "disk", false, 2, path -> new java.io.OutputStream() {
            @Override public void write(int b) throws IOException { throw new IOException("диск"); }
            @Override public void write(byte[] b, int off, int len) throws IOException { throw new IOException("диск"); }
        })) {
            source.append(0, new byte[32_000], null);
            assertThrows(IOException.class, () -> source.seal(16_000));
        }
    }
}
