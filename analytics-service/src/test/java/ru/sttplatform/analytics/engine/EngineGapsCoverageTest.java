package ru.sttplatform.analytics.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Остатки движка: чтение английских слов по-русски и детектор конца ответа (все переходы состояний). */
class EngineGapsCoverageTest {

    // ---------------------------------------------------------------- SpokenText

    @Test
    void translit_reads_every_latin_letter_and_the_common_digraphs() {
        assertEquals("эбкдефгхиджклмнопкрставвксиз", SpokenText.translit("abcdefghijklmnopqrstuvwxyz"));
        assertEquals("сити", SpokenText.translit("city"));            // c перед i → «с»
        assertEquals("кэт", SpokenText.translit("cat"));               // c перед a → «к»
        assertEquals("шн", SpokenText.translit("tion"));
        assertTrue(SpokenText.translit("quick").startsWith("кв"));
        assertTrue(SpokenText.translit("deep").contains("и"));
        assertTrue(SpokenText.translit("boot").contains("у"));
        assertTrue(SpokenText.translit("beach").contains("и"));
        assertTrue(SpokenText.translit("rain").contains("эй"));
        assertTrue(SpokenText.translit("play").contains("эй"));
        assertTrue(SpokenText.translit("house").contains("ау"));
        assertTrue(SpokenText.translit("light").contains("ай"));
        assertTrue(SpokenText.translit("thing").startsWith("т"));
        assertTrue(SpokenText.translit("shell").startsWith("ш"));
        assertTrue(SpokenText.translit("chat").startsWith("ч"));
        assertTrue(SpokenText.translit("phone").startsWith("ф"));
        assertTrue(SpokenText.translit("deck").endsWith("к"));
        assertEquals("ит", SpokenText.translit("eat"));
        assertEquals("1", SpokenText.translit("1"));                   // не буква — как есть
        assertEquals("кэч", SpokenText.translit("cache"), "немое e на конце отбрасывается");
    }

    @Test
    void spell_reads_acronyms_by_letters_and_skips_non_letters() {
        assertEquals("эс-кью-эл", SpokenText.spell("SQL"));
        assertEquals("", SpokenText.spell("123"));
        assertEquals("эй-пи-ай", SpokenText.spell("a1p-i"));
    }

    // ---------------------------------------------------------------- EndpointDetector

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

    @Test
    void external_state_is_taken_as_is_and_getters_report_it() {
        EndpointDetector d = new EndpointDetector(new EndpointDetector.Config());
        d.observeExternal(EndpointDetector.State.ENDPOINT, 5000, 1200, 2300, 450);
        assertEquals(EndpointDetector.State.ENDPOINT, d.state());
        assertEquals(5000, d.audioMs());
        assertEquals(1200, d.speechStartMs());
        assertEquals(450, d.silenceMs());
        // Длительность речи считается из позиции последнего голоса, а не из переданного параметра speechDurationMs
        // (в боевом потоке они совпадают): 5000 − 450 − 1200
        assertEquals(3350, d.speechDurationMs());

        d.observeExternal(EndpointDetector.State.WAITING, 700, -1, 0, 700);
        assertEquals(700, d.speechStartMs(), "речь не начиналась — начало равно текущей позиции");
        assertEquals(0, d.speechDurationMs());
        assertEquals(700, d.silenceMs());
        assertEquals(0, new EndpointDetector(new EndpointDetector.Config()).speechStartMs());
        assertEquals(0, new EndpointDetector(new EndpointDetector.Config()).speechDurationMs());
    }
}
