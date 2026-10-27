package ru.sttplatform.stt.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhisperPropertiesTest {
    @Test
    void uses_local_url_when_autostart_is_enabled_without_an_explicit_url() {
        WhisperProperties properties = new WhisperProperties("", true, null, null, null, null, null, null, null);

        assertEquals("http://127.0.0.1:9000", properties.url());
        assertTrue(properties.configured());
    }

    @Test
    void leaves_whisper_disabled_by_default() {
        WhisperProperties properties = new WhisperProperties("", false, null, null, null, null, null, null, null);

        assertEquals("", properties.url());
        assertFalse(properties.configured());
    }
}
