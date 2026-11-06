package ru.sttplatform.analytics.live;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EventSpeechEngineAndConnectorsTest {

    @Test
    void event_engine_keeps_only_the_latest_partial_and_flushes_it_on_finish() {
        EventSpeechEngine e = new EventSpeechEngine();
        assertEquals(List.of(), e.finish());                       // ничего не накоплено
        e.observe(null);                                           // нет обновления
        e.observe(new SpeechEngine.Result(false, "привет"));
        e.observe(new SpeechEngine.Result(false, "привет мир"));
        assertEquals(List.of(new SpeechEngine.Result(true, "привет мир")), e.finish());
        assertEquals(List.of(), e.finish(), "остаток отдан один раз");
        e.observe(new SpeechEngine.Result(false, "что-то"));
        e.observe(new SpeechEngine.Result(true, "итог"));         // final фиксирует фразу и очищает гипотезу
        assertEquals(List.of(), e.finish());
    }

    @Test
    void event_engine_reset_close_and_blank_partial() {
        EventSpeechEngine e = new EventSpeechEngine();
        e.observe(new SpeechEngine.Result(false, "текст"));
        e.reset();
        assertEquals(List.of(), e.finish());
        e.observe(new SpeechEngine.Result(false, "   "));
        assertEquals(List.of(), e.finish(), "пустая гипотеза не превращается в фразу");
        e.observe(new SpeechEngine.Result(false, "текст"));
        e.close();
        assertEquals(List.of(), e.finish());
        assertNull(e.realtimeFactor());
    }

    @Test
    void event_engine_refuses_raw_audio() {
        var ex = assertThrows(UnsupportedOperationException.class, () -> new EventSpeechEngine().accept(new byte[2], 2));
        assertTrue(ex.getMessage().contains("событ"));
    }

    @Test
    void detailed_transcript_records_hold_their_fields() {
        var w = new DetailedTranscriber.Word("слово", 0.1, 0.5, null);
        var t = new DetailedTranscriber.Transcript("слово", List.of(w));
        assertEquals("слово", t.words().get(0).word());
        assertEquals(0.5, w.end());
        assertNull(w.confidence());
        assertEquals(0.1, w.start());
        assertEquals("слово", t.text());
    }

    @Test
    void connectors_detect_a_trailing_conjunction_ignoring_punctuation_and_case() {
        assertTrue(Connectors.endsWithConnector("это работает и"));
        assertTrue(Connectors.endsWithConnector("это работает И..."));
        assertTrue(Connectors.endsWithConnector("для запуска, потому"));
        assertTrue(Connectors.endsWithConnector("ну эм"));
        assertFalse(Connectors.endsWithConnector("это байткод"));
        assertFalse(Connectors.endsWithConnector(""));
        assertFalse(Connectors.endsWithConnector("..."));
        assertFalse(Connectors.endsWithConnector("   "));
        assertFalse(Connectors.endsWithConnector("версия 17"));
    }

    @Test
    void connectors_count_words_by_letters_and_digits() {
        assertEquals(0, Connectors.countWords(""));
        assertEquals(0, Connectors.countWords("  ,.  "));
        assertEquals(1, Connectors.countWords("слово"));
        assertEquals(3, Connectors.countWords("раз, два... три"));
        assertEquals(3, Connectors.countWords("java 17 lts"));
    }
}
