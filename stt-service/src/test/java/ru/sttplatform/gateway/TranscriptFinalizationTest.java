package ru.sttplatform.gateway.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.diarization.session.SessionRegistry;
import ru.sttplatform.gateway.config.GatewayProperties;
import ru.sttplatform.stt.integration.IntegrationProperties;
import ru.sttplatform.stt.transcript.InterviewAudioSpool;
import ru.sttplatform.stt.vosk.SpeechSessionFactory;
import ru.sttplatform.stt.whisper.WhisperClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.BufferedWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TranscriptFinalizationTest {
    @TempDir Path root;

    @Test void publishedFallbackKeepsTheOriginalRecording() throws Exception {
        var manager = new SessionManager(mock(SessionRegistry.class), new GatewayProperties(null, null, root.toString()),
                mock(WhisperClient.class), mock(SpeechSessionFactory.class), mock(IntegrationProperties.class));
        var constructor = Class.forName("ru.sttplatform.gateway.session.SessionManager$Active").getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        Object active = constructor.newInstance(UUID.randomUUID().toString());
        Orchestrator orchestrator = mock(Orchestrator.class);
        Path output = Files.createDirectory(root.resolve("fallback"));
        when(orchestrator.transcriptComplete()).thenReturn(true);
        when(orchestrator.transcriptFallback()).thenReturn(true);
        when(orchestrator.outputDirectory()).thenReturn(output);
        when(orchestrator.finalTranscriptWords()).thenReturn(List.of());
        ReflectionTestUtils.setField(active, "orchestrator", orchestrator);
        ReflectionTestUtils.invokeMethod(manager, "publishTranscript", active);
        assertTrue(Files.exists(output.resolve("transcripts/ready.json")));
        verify(orchestrator).finishTranscriptTiming(true);
        verify(orchestrator, never()).deleteAudioSpool();
    }

    @Test void reportFailureDoesNotHideSuccessfulTranscriptAndFailedPassDoesNotPublish() throws Exception {
        var manager = new SessionManager(mock(SessionRegistry.class), new GatewayProperties(null, null, root.toString()),
                mock(WhisperClient.class), mock(SpeechSessionFactory.class), mock(IntegrationProperties.class));
        var constructor = Class.forName("ru.sttplatform.gateway.session.SessionManager$Active").getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        String id = UUID.randomUUID().toString();
        Object active = constructor.newInstance(id);
        Orchestrator orchestrator = mock(Orchestrator.class);
        Path output = Files.createDirectory(root.resolve("session"));
        when(orchestrator.transcriptComplete()).thenReturn(true);
        when(orchestrator.outputDirectory()).thenReturn(output);
        when(orchestrator.finalTranscriptWords()).thenReturn(List.of());
        ReflectionTestUtils.setField(active, "orchestrator", orchestrator);
        ReflectionTestUtils.setField(active, "failure", "ошибка отчёта");
        ReflectionTestUtils.invokeMethod(manager, "publishTranscript", active);
        assertEquals(true, ReflectionTestUtils.getField(active, "transcriptReady"));
        assertTrue(Files.exists(output.resolve("transcripts/ready.json")));
        verify(orchestrator).finishTranscriptTiming(true);
        verify(orchestrator).deleteAudioSpool();

        Object failed = constructor.newInstance(UUID.randomUUID().toString());
        Orchestrator broken = mock(Orchestrator.class);
        when(broken.transcriptError()).thenReturn("Whisper недоступен");
        ReflectionTestUtils.setField(failed, "orchestrator", broken);
        ReflectionTestUtils.invokeMethod(manager, "publishTranscript", failed);
        assertEquals("Whisper недоступен", ReflectionTestUtils.getField(failed, "transcriptError"));
        verify(broken).finishTranscriptTiming(false);
        verify(broken, never()).deleteAudioSpool();
    }

    @Test void failedPublicationRecordsErrorAndKeepsAudio() throws Exception {
        var manager = new SessionManager(mock(SessionRegistry.class), new GatewayProperties(null, null, root.toString()),
                mock(WhisperClient.class), mock(SpeechSessionFactory.class), mock(IntegrationProperties.class));
        var constructor = Class.forName("ru.sttplatform.gateway.session.SessionManager$Active").getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        Object active = constructor.newInstance(UUID.randomUUID().toString());
        Orchestrator orchestrator = mock(Orchestrator.class);
        when(orchestrator.transcriptComplete()).thenReturn(true);
        when(orchestrator.outputDirectory()).thenReturn(root.resolve("nested").resolve("outside-session-root"));
        when(orchestrator.finalTranscriptWords()).thenReturn(List.of());
        ReflectionTestUtils.setField(active, "orchestrator", orchestrator);
        ReflectionTestUtils.invokeMethod(manager, "publishTranscript", active);
        assertEquals(false, ReflectionTestUtils.getField(active, "transcriptReady"));
        assertNotNull(ReflectionTestUtils.getField(active, "transcriptError"));
        verify(orchestrator).finishTranscriptTiming(false);
        verify(orchestrator, never()).deleteAudioSpool();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fullPassAndPublicationContinueEvenIfTimingFileCannotBeWritten(boolean timingFileBlocked) throws Exception {
        String id = UUID.randomUUID().toString();
        Path output = Files.createDirectory(root.resolve("session"));
        Path timingPath = output.resolve("transcription-timing.json");
        if (timingFileBlocked) Files.createDirectory(timingPath);
        WhisperClient client = mock(WhisperClient.class);
        AtomicInteger calls = new AtomicInteger();
        when(client.post(eq("/transcribe-json-vad"), any(byte[].class))).thenAnswer(call -> {
            int attempt = calls.getAndIncrement();
            if (attempt == 0) return new WhisperClient.Reply(503, "temporary error");
            String word = attempt == 1 ? "система" : "микрофон";
            return new WhisperClient.Reply(200, "{\"words\":[{\"word\":\"" + word
                    + "\",\"start\":0.1,\"end\":0.2}]}");
        });
        try (var audio = new InterviewAudioSpool(root.resolve("audio"), id, true)) {
            audio.append(0, new byte[32_000], new byte[32_000]);
            audio.seal(16_000);
            var orchestrator = mock(Orchestrator.class, CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(orchestrator, "directory", output);
            ReflectionTestUtils.setField(orchestrator, "sessionId", id);
            ReflectionTestUtils.setField(orchestrator, "audioSpool", audio);
            ReflectionTestUtils.setField(orchestrator, "whisperClient", client);
            ReflectionTestUtils.setField(orchestrator, "separateChannels", true);
            ReflectionTestUtils.setField(orchestrator, "finalDiarizationSpans", List.of());
            ReflectionTestUtils.setField(orchestrator, "finalWords", new ArrayList<>());
            ReflectionTestUtils.setField(orchestrator, "timelineLog", new BufferedWriter(new StringWriter()));

            ReflectionTestUtils.invokeMethod(orchestrator, "postprocessWhisper");
            verify(client, times(3)).post(eq("/transcribe-json-vad"), any(byte[].class));
            if (!timingFileBlocked) {
                var progress = Json.MAPPER.readTree(timingPath.toFile());
                assertEquals("processing", progress.path("status").asText());
                assertTrue(progress.path("system_ms").isNumber());
                assertTrue(progress.path("microphone_ms").isNumber());
                assertTrue(progress.path("total_ms").isNull());
            }

            var manager = new SessionManager(mock(SessionRegistry.class), new GatewayProperties(null, null, root.toString()),
                    client, mock(SpeechSessionFactory.class), mock(IntegrationProperties.class));
            var constructor = Class.forName("ru.sttplatform.gateway.session.SessionManager$Active").getDeclaredConstructor(String.class);
            constructor.setAccessible(true);
            Object active = constructor.newInstance(id);
            ReflectionTestUtils.setField(active, "orchestrator", orchestrator);
            ReflectionTestUtils.invokeMethod(manager, "publishTranscript", active);

            assertEquals(true, ReflectionTestUtils.getField(active, "transcriptReady"));
            assertNull(ReflectionTestUtils.getField(active, "transcriptError"));
            assertTrue(Files.exists(output.resolve("transcripts/transcripts.zip")));
            String transcript = Files.readString(output.resolve("transcripts/transcript.md"));
            assertTrue(transcript.contains("Не определено: система"));
            assertTrue(transcript.contains("Интервьюер 1: микрофон"));
            assertFalse(Files.exists(audio.path()));
            if (!timingFileBlocked) {
                var timings = Json.MAPPER.readTree(timingPath.toFile());
                assertEquals("ready", timings.path("status").asText());
                assertTrue(timings.path("total_ms").asLong() >= timings.path("system_ms").asLong()
                        + timings.path("microphone_ms").asLong());
            }
        }
    }
}
