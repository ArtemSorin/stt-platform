package ru.sttplatform.gateway.session;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import ru.sttplatform.gateway.api.BrowserApiController;
import ru.sttplatform.gateway.clients.ApiException;
import ru.sttplatform.gateway.config.GatewayProperties;
import ru.sttplatform.diarization.session.DiarizationSession;
import ru.sttplatform.diarization.session.SessionRegistry;
import ru.sttplatform.stt.integration.IntegrationProperties;
import ru.sttplatform.stt.vosk.SpeechSessionFactory;
import ru.sttplatform.stt.whisper.WhisperClient;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChannelAudioTest {
    @Test
    void separates_little_endian_samples_without_mixing_or_changing_time() {
        byte[] input = {1, 2, 11, 12, 3, 4, 13, 14, 5, 6, 15, 16};
        var channels = SessionManager.splitChannels(input);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, channels.system());
        assertArrayEquals(new byte[]{11, 12, 13, 14, 15, 16}, channels.microphone());
    }

    @Test
    void rejects_truncated_or_oversized_channel_frames() {
        for (int size : new int[]{0, 2, 3, 5, 40964})
            assertThrows(ApiException.class, () -> SessionManager.splitChannels(new byte[size]));
        assertEquals(20480, SessionManager.splitChannels(new byte[40960]).system().length);
    }

    @Test
    void controller_forwards_one_pair_and_rejects_invalid_or_unsequenced_data() throws Exception {
        SessionManager sessions = mock(SessionManager.class);
        var controller = new BrowserApiController(sessions, new GatewayProperties(null, null, null));
        MockHttpServletRequest request = new MockHttpServletRequest();
        byte[] frame = {1, 2, 3, 4};
        request.setContent(frame);
        assertEquals(true, controller.audioChannels(request, "session", "7").get("ok"));
        verify(sessions).liveAudioChannels("session", 7, frame);

        MockHttpServletRequest bad = new MockHttpServletRequest();
        bad.setContent(new byte[]{1, 2, 3});
        assertThrows(ApiException.class, () -> controller.audioChannels(bad, "session", "8"));
        MockHttpServletRequest noSequence = new MockHttpServletRequest();
        noSequence.setContent(frame);
        assertThrows(ApiException.class, () -> controller.audioChannels(noSequence, "session", null));
        verifyNoMoreInteractions(sessions);
    }

    @Test
    void channel_session_rejects_repeated_sequence_and_format_switch() throws Exception {
        var manager = new SessionManager(mock(SessionRegistry.class), new GatewayProperties(null, null, null),
                mock(WhisperClient.class), mock(SpeechSessionFactory.class), mock(IntegrationProperties.class));
        Class<?> activeClass = Class.forName("ru.sttplatform.gateway.session.SessionManager$Active");
        var constructor = activeClass.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        Object active = constructor.newInstance("session");
        var diarization = mock(DiarizationSession.class);
        var orchestrator = mock(Orchestrator.class);
        ReflectionTestUtils.setField(active, "diarization", diarization);
        ReflectionTestUtils.setField(active, "orchestrator", orchestrator);
        ReflectionTestUtils.setField(active, "phase", "live");
        ReflectionTestUtils.setField(active, "audioMode", "channels");
        ReflectionTestUtils.setField(manager, "current", active);

        byte[] pair = {1, 2, 11, 12, 3, 4, 13, 14};
        manager.liveAudioChannels("session", 0, pair);
        verify(diarization).liveAudio(new byte[]{1, 2, 3, 4});
        verify(orchestrator).submitChannels(0, new byte[]{1, 2, 3, 4}, new byte[]{11, 12, 13, 14});
        assertThrows(ApiException.class, () -> manager.liveAudioChannels("session", 0, pair));
        assertThrows(ApiException.class, () -> manager.liveAudio("session", 1, new byte[]{1, 2}));
        manager.liveAudioChannels("session", 1, pair);
        verify(orchestrator).submitChannels(2, new byte[]{1, 2, 3, 4}, new byte[]{11, 12, 13, 14});
    }
}
