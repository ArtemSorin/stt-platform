package ru.sttplatform.diarization.session;

import org.junit.jupiter.api.Test;
import ru.sttplatform.diarization.audio.AudioChunk;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiarizationAudioQueueTest {
    @Test
    void overflow_disables_only_diarization_and_counts_every_skipped_frame() throws Exception {
        var queue = new DiarizationAudioQueue(1);
        AudioChunk accepted = new AudioChunk(0, 0, new float[]{.1f, .2f}, 1);
        AudioChunk overflow = new AudioChunk(1, 2, new float[]{.3f, .4f, .5f}, 2);
        AudioChunk afterOverflow = new AudioChunk(2, 2, new float[]{.6f}, 3);

        assertEquals(DiarizationAudioQueue.Admission.ACCEPTED, queue.offer(accepted));
        assertEquals(DiarizationAudioQueue.Admission.OVERFLOW, queue.offer(overflow));
        assertEquals(DiarizationAudioQueue.Admission.DISCARDED, queue.offer(afterOverflow));

        var capture = queue.snapshot();
        assertEquals(2, capture.acceptedSamples());
        assertEquals(4, capture.droppedSamples());
        assertEquals(2, capture.droppedChunks());
        assertEquals(1, capture.overflowCount());
        assertEquals(1, capture.maxDepth());
        assertEquals(1, queue.capacity());
        assertEquals(0, queue.poll(0, TimeUnit.MILLISECONDS).index());
    }
}
