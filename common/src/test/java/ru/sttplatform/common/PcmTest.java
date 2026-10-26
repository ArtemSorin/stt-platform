package ru.sttplatform.common;

import org.junit.jupiter.api.Test;
import ru.sttplatform.common.audio.Pcm;
import ru.sttplatform.common.json.Json;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PcmTest {
    @Test
    void floats_survive_a_round_trip_through_pcm16() {
        float[] source = {0f, .5f, -.5f, 1f, -1f};
        float[] back = Pcm.toFloats(Pcm.toBytes(source));
        for (int i = 0; i < source.length; i++) assertEquals(source[i], back[i], 0.001f);
    }

    @Test
    void silence_is_detected_only_for_all_zero_bytes() {
        assertTrue(Pcm.isSilent(new byte[16]));
        assertFalse(Pcm.isSilent(new byte[]{0, 0, 1, 0}));
    }

    @Test
    void percentile_interpolates_and_empty_distribution_is_explicit() {
        assertEquals(2.5, Json.percentile(List.of(1.0, 2.0, 3.0, 4.0), .5), 1e-9);
        assertEquals(0, Json.distribution(List.of()).get("count"));
    }
}
