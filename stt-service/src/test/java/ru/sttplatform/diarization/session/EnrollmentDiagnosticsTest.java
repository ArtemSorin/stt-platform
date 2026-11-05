package ru.sttplatform.diarization.session;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnrollmentDiagnosticsTest {
    private DiarizationSession sessionWithBytes(int count) {
        DiarizationSession session = mock(DiarizationSession.class, CALLS_REAL_METHODS);
        ByteArrayOutputStream reference = new ByteArrayOutputStream();
        reference.writeBytes(new byte[count]);
        ReflectionTestUtils.setField(session, "reference", reference);
        ReflectionTestUtils.setField(session, "phase", "enrolling");
        ReflectionTestUtils.setField(session, "id", "test-session");
        return session;
    }

    @Test void silentTenSecondRecordingExplainsSystemAudioProblem() throws Exception {
        DiarizationSession session = sessionWithBytes(10 * 16_000 * 2);
        session.finishEnrollment(true);
        assertEquals("retry", ReflectionTestUtils.getField(session, "phase"));
        assertTrue(String.valueOf(ReflectionTestUtils.getField(session, "error")).contains("Системный звук"));
    }

    @Test void shortRecordingAndExtractorFailureHaveDistinctMessages() throws Exception {
        DiarizationSession session = sessionWithBytes(1_600);
        session.finishEnrollment(true);
        assertTrue(String.valueOf(ReflectionTestUtils.getField(session, "error")).contains("слишком короткая"));
        assertTrue(DiarizationSession.enrollmentError(10, .02).contains("голосовой профиль"));
    }

    @Test void audibleRecordingWithInvalidEmbeddingReportsModelFailure() throws Exception {
        DiarizationSession session = sessionWithBytes(0);
        ByteArrayOutputStream reference = new ByteArrayOutputStream();
        for (int i = 0; i < 16_000; i++) { reference.write(0xe8); reference.write(0x03); }
        ReflectionTestUtils.setField(session, "reference", reference);
        SpeakerEmbeddingExtractor extractor = mock(SpeakerEmbeddingExtractor.class);
        OnlineStream stream = mock(OnlineStream.class);
        when(extractor.createStream()).thenReturn(stream);
        when(extractor.compute(stream)).thenReturn(new float[0]);
        ReflectionTestUtils.setField(session, "extractor", extractor);
        session.finishEnrollment(true);
        assertEquals("retry", ReflectionTestUtils.getField(session, "phase"));
        assertTrue(String.valueOf(ReflectionTestUtils.getField(session, "error")).contains("голосовой профиль"));
        verify(stream).release();
    }
}
