package ru.sttplatform.stt.transcript;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioSystem;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveWaveTest {
    @TempDir Path directory;

    @Test
    void preservesInterleavedChannelsAndTiming() throws Exception {
        byte[] pcm = {1, 0, 2, 0, 3, 0, 4, 0};
        Path source = directory.resolve("input.pcm"), target = directory.resolve("audio.wav");
        Files.write(source, pcm);
        ArchiveWave.write(source, target, 2, 2);
        try (var audio = AudioSystem.getAudioInputStream(target.toFile())) {
            assertEquals(2, audio.getFormat().getChannels());
            assertEquals(16000, audio.getFormat().getSampleRate());
            assertEquals(2, audio.getFrameLength());
            assertArrayEquals(pcm, audio.readAllBytes());
        }
        assertArrayEquals(pcm, Files.readAllBytes(source));
    }

    @Test
    void rejectsIncompleteRecordAndLeavesSourceIntact() throws Exception {
        Path source = directory.resolve("input.pcm"), target = directory.resolve("audio.wav");
        Files.write(source, new byte[]{1, 2, 3, 4});
        assertThrows(java.io.IOException.class, () -> ArchiveWave.write(source, target, 2, 2));
        assertThrows(java.io.IOException.class, () -> ArchiveWave.write(source, target, 2, 0));
        assertFalse(Files.exists(target));
        assertEquals(4, Files.size(source));
    }
}
