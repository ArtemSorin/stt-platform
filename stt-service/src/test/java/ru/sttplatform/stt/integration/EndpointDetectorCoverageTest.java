package ru.sttplatform.stt.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Детектор конца ответа на стороне STT — тот же алгоритм, что в analytics-service; проверяются все переходы. */
class EndpointDetectorCoverageTest {

    private static final int N = 800;                              // 50 мс

    private static EndpointDetector.State run(EndpointDetector d, int frames, double rms, int words, boolean connector) {
        EndpointDetector.State s = d.state();
        for (int i = 0; i < frames; i++) s = d.onFrameRms(N, rms, words, connector);
        return s;
    }

    @Test
    void waits_for_two_voiced_frames_before_declaring_speech() {
        EndpointDetector d = new EndpointDetector(new EndpointDetector.Config());
        assertEquals(EndpointDetector.State.WAITING, d.state());
        assertEquals(EndpointDetector.State.WAITING, d.onFrameRms(N, 3000, 0, false));
        assertEquals(EndpointDetector.State.SPEAKING, d.onFrameRms(N, 3000, 0, false));
        assertEquals(0, d.speechStartMs(), "начало речи — с первого звонкого кадра");
        assertEquals(100, d.audioMs());
    }

    @Test
    void empty_frames_are_ignored_and_pcm_frames_use_their_energy() {
        EndpointDetector d = new EndpointDetector(new EndpointDetector.Config());
        assertEquals(EndpointDetector.State.WAITING, d.onFrame(new byte[0], 0, 0, false));
        assertEquals(EndpointDetector.State.WAITING, d.onFrameRms(0, 5000, 0, false));
        assertEquals(0, d.audioMs());
        byte[] loud = new byte[N * 2];
        for (int i = 0; i < N; i++) { loud[2 * i] = (byte) 0xE8; loud[2 * i + 1] = 0x03; }   // 1000
        d.onFrame(loud, loud.length, 0, false);
        assertEquals(EndpointDetector.State.SPEAKING, d.onFrame(loud, loud.length, 0, false));
    }

    @Test
    void no_speech_for_too_long_is_final_and_sticky() {
        EndpointDetector.Config c = new EndpointDetector.Config();
        c.noSpeechTimeoutMs = 1000;
        EndpointDetector d = new EndpointDetector(c);
        assertEquals(EndpointDetector.State.NO_SPEECH, run(d, 25, 0, 0, false));
        assertEquals(EndpointDetector.State.NO_SPEECH, d.onFrameRms(N, 5000, 3, false));
        assertEquals(EndpointDetector.State.NO_SPEECH, d.onFrame(new byte[N * 2], N * 2, 0, false));
        d.reset();
        assertEquals(EndpointDetector.State.WAITING, d.state());
        assertEquals(0, d.audioMs());
    }

    @Test
    void end_of_answer_waits_longer_after_connectors_and_for_short_answers_but_never_beyond_the_ceiling() {
        for (Object[] row : new Object[][]{
                {6, false, 450}, {6, true, 800}, {2, false, 800}, {2, true, 1150}}) {
            EndpointDetector d = new EndpointDetector(new EndpointDetector.Config());
            run(d, 10, 4000, (int) row[0], (boolean) row[1]);
            long waited = 0;
            while (d.state() == EndpointDetector.State.SPEAKING && waited < 5000) {
                d.onFrameRms(N, 0, (int) row[0], (boolean) row[1]);
                waited += 50;
            }
            assertEquals(((Integer) row[2]).longValue(), waited, "слов=" + row[0] + ", союз=" + row[1]);
            assertEquals(waited, d.silenceMs());
        }
        EndpointDetector.Config capped = new EndpointDetector.Config();
        capped.maxSilenceMs = 600;
        EndpointDetector d = new EndpointDetector(capped);
        run(d, 10, 4000, 1, true);
        assertEquals(EndpointDetector.State.ENDPOINT, run(d, 12, 0, 1, true), "потолок 600 мс обязан сработать");
    }

    @Test
    void floor_for_a_very_quiet_room_never_drops_below_the_absolute_minimum() {
        EndpointDetector d = new EndpointDetector(new EndpointDetector.Config());
        run(d, 40, 1, 0, false);                                   // почти полная тишина, шумовой пол падает
        assertEquals(EndpointDetector.State.WAITING, d.onFrameRms(N, 200, 0, false), "200 < абсолютного минимума 250");
    }

    @Test
    void speech_resumes_only_after_three_voiced_frames_in_a_row() {
        EndpointDetector d = new EndpointDetector(new EndpointDetector.Config());
        run(d, 10, 4000, 6, false);
        run(d, 9, 0, 6, false);
        assertEquals(EndpointDetector.State.ENDPOINT, d.state());
        assertEquals(EndpointDetector.State.ENDPOINT, run(d, 2, 4000, 6, false));
        d.onFrameRms(N, 0, 6, false);                              // серия прервалась
        assertEquals(EndpointDetector.State.ENDPOINT, run(d, 2, 4000, 6, false));
        assertEquals(EndpointDetector.State.SPEAKING, run(d, 1, 4000, 6, false));
        // после возобновления тишина считается заново
        assertEquals(EndpointDetector.State.SPEAKING, run(d, 5, 0, 6, false));
        assertTrue(d.silenceMs() < 450);
    }

    @Test
    void a_single_click_during_endpoint_is_not_speech() {
        EndpointDetector d = new EndpointDetector(new EndpointDetector.Config());
        run(d, 10, 4000, 6, false);
        run(d, 9, 0, 6, false);
        d.onFrameRms(N, 4000, 6, false);
        assertEquals(EndpointDetector.State.ENDPOINT, run(d, 3, 0, 6, false));
    }

    @Test
    void very_long_answer_is_cut_by_the_limit() {
        EndpointDetector.Config c = new EndpointDetector.Config();
        c.maxAnswerMs = 2000;
        EndpointDetector d = new EndpointDetector(c);
        assertEquals(EndpointDetector.State.ENDPOINT, run(d, 60, 4000, 50, false));
        assertTrue(d.speechDurationMs() >= 2000);
    }
}
