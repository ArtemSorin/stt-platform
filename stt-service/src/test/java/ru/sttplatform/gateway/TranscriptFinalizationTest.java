package ru.sttplatform.gateway.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import ru.sttplatform.diarization.session.SessionRegistry;
import ru.sttplatform.gateway.config.GatewayProperties;
import ru.sttplatform.stt.integration.IntegrationProperties;
import ru.sttplatform.stt.vosk.SpeechSessionFactory;
import ru.sttplatform.stt.whisper.WhisperClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TranscriptFinalizationTest {
    @TempDir Path root;

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
        verify(orchestrator).deleteAudioSpool();

        Object failed = constructor.newInstance(UUID.randomUUID().toString());
        Orchestrator broken = mock(Orchestrator.class);
        when(broken.transcriptError()).thenReturn("Whisper недоступен");
        ReflectionTestUtils.setField(failed, "orchestrator", broken);
        ReflectionTestUtils.invokeMethod(manager, "publishTranscript", failed);
        assertEquals("Whisper недоступен", ReflectionTestUtils.getField(failed, "transcriptError"));
        verify(broken, never()).deleteAudioSpool();
    }
}
