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
        when(client.post(eq("/transcribe-json-vad"), any(byte[].class))).thenAnswer(call -> {
            byte[] pcm = call.getArgument(1);
            assertEquals(25 * 16_000 * 2, pcm.length);
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
                assertEquals(2, total); progress.add(done);
            });
            assertEquals(List.of(1, 2), seen);
            assertEquals(List.of(0, 1, 2), progress);
            assertEquals(List.of(16_000L, 16_000L),
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
            verify(client).post(eq("/transcribe-json-vad"), any(byte[].class));

            clearInvocations(client);
            assertEquals(1, pass.transcribe(List.of(new PostInterviewWhisper.SpeechEvidence(0, 0, 16_000)), client, (a, b) -> {}).gaps().size());
            verify(client).post(eq("/transcribe-json-vad"), any(byte[].class));
            verify(client, times(2)).post(eq("/transcribe-json"), any(byte[].class));

            clearInvocations(client);
            assertEquals(1, pass.transcribe(List.of(new PostInterviewWhisper.SpeechEvidence(0, 4_000, 5_600, 800)), client, (a, b) -> {}).gaps().size());
            verify(client).post(eq("/transcribe-json-vad"), any(byte[].class));
            verify(client, times(2)).post(eq("/transcribe-json"), any(byte[].class));
        }
    }

    @Test void mergesNearbyUncoveredEvidenceAndRetriesItWithoutVad() throws Exception {
        WhisperClient client = mock(WhisperClient.class);
        when(client.post(eq("/transcribe-json-vad"), any(byte[].class))).thenReturn(new WhisperClient.Reply(200,
                "{\"words\":[{\"word\":\"первое\",\"start\":2.1,\"end\":2.5,\"confidence\":0.9}]}"));
        when(client.post(eq("/transcribe-json"), any(byte[].class))).thenAnswer(call -> {
            byte[] pcm = call.getArgument(1);
            assertEquals(2 * 16_000 * 2, pcm.length);
            return new WhisperClient.Reply(200,
                    "{\"words\":[{\"word\":\"тихое\",\"start\":0.6,\"end\":1.4,\"confidence\":0.8}]}" );
        });
        try (InterviewAudioSpool source = new InterviewAudioSpool(directory, "vad-retry", false)) {
            source.append(0, new byte[20 * 32_000], null);
            source.seal(20L * 16_000);
            var result = new PostInterviewWhisper(source).transcribe(List.of(
                    new PostInterviewWhisper.SpeechEvidence(0, 10L * 16_000, 10L * 16_000 + 3_200, 1_000),
                    new PostInterviewWhisper.SpeechEvidence(0, 10L * 16_000 + 12_800, 11L * 16_000, 1_000)),
                    client, (a, b) -> {});

            assertEquals(List.of("первое", "тихое"), result.words().stream().map(PostInterviewWhisper.Word::text).toList());
            assertEquals(List.of(2L * 16_000 + 1_600, 10L * 16_000 + 1_600),
                    result.words().stream().map(PostInterviewWhisper.Word::start).toList());
            assertTrue(result.gaps().isEmpty());
            verify(client).post(eq("/transcribe-json-vad"), any(byte[].class));
            verify(client).post(eq("/transcribe-json"), any(byte[].class));
        }
    }

    @Test void retriesInvalidResponsesBeforeReturningWholeChannel() throws Exception {
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
            assertEquals(1, result.words().size());
            assertEquals(0.8, result.words().get(0).confidence());
            assertEquals(2, calls.get());
        }
    }

    @Test void splitsOnlyAfterWholeChannelFailsAndKeepsWordsOnBothSides() throws Exception {
        WhisperClient client = mock(WhisperClient.class);
        AtomicInteger calls = new AtomicInteger();
        when(client.post(anyString(), any(byte[].class))).thenAnswer(call -> {
            int n = calls.getAndIncrement();
            if (n < 2) return new WhisperClient.Reply(503, "error");
            return n == 2
                    ? new WhisperClient.Reply(200, "{\"words\":[{\"word\":\"первое\",\"start\":10.4,\"end\":10.8}]}")
                    : new WhisperClient.Reply(200, "{\"words\":[{\"word\":\"второе\",\"start\":0.6,\"end\":0.8}]}");
        });
        try (InterviewAudioSpool source = new InterviewAudioSpool(directory, "edge", false)) {
            source.append(0, new byte[22 * 32_000], null); source.seal(22L * 16_000);
            var result = new PostInterviewWhisper(source).transcribe(
                    List.of(new PostInterviewWhisper.SpeechEvidence(0, 10 * 16_000 + 6_400, 10 * 16_000 + 12_800, 1)),
                    client, (a, b) -> {});
            assertEquals(4, calls.get());
            assertEquals(2, result.words().size());
            assertTrue(result.gaps().isEmpty());
            assertEquals(Math.round(10.4 * 16_000), result.words().get(0).start());
        }
    }

    @Test void returnsUnresolvedSpeechGapsForVoskFallback() throws Exception {
        WhisperClient client = mock(WhisperClient.class);
        when(client.post(anyString(), any(byte[].class))).thenReturn(new WhisperClient.Reply(200, "{\"words\":[]}"));
        try (InterviewAudioSpool source = new InterviewAudioSpool(directory, "edge", false)) {
            source.append(0, new byte[22 * 32_000], null); source.seal(22L * 16_000);
            var result = new PostInterviewWhisper(source).transcribe(
                    List.of(new PostInterviewWhisper.SpeechEvidence(0, 10 * 16_000, 11 * 16_000)), client, (a, b) -> {});
            assertEquals(1, result.gaps().size());
            assertEquals(10 * 16_000L, result.gaps().get(0).start());
            assertEquals(11 * 16_000L, result.gaps().get(0).end());
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
