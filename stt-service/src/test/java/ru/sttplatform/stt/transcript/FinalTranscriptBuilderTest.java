package ru.sttplatform.stt.transcript;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FinalTranscriptBuilderTest {
    @TempDir Path directory;

    private PostInterviewWhisper.Word word(int channel, String text, double start, double end) {
        return new PostInterviewWhisper.Word(channel, text, Math.round(start * 16_000), Math.round(end * 16_000), 0, .9);
    }

    @Test void assignsStableRemoteInterviewersCandidateAndUnknownWithoutMixingChannels() throws Exception {
        try (var audio = new InterviewAudioSpool(directory, "session", true)) {
            audio.append(0, new byte[6 * 32_000], new byte[6 * 32_000]); audio.seal(6 * 16_000);
            var spans = List.of(new FinalTranscriptBuilder.SpeakerSpan(0, 16_000, "Интервьюер 1", "a"),
                    new FinalTranscriptBuilder.SpeakerSpan(16_000, 32_000, "Кандидат", "c"),
                    new FinalTranscriptBuilder.SpeakerSpan(32_000, 48_000, "Интервьюер 2", "b"),
                    new FinalTranscriptBuilder.SpeakerSpan(48_000, 64_000, "Интервьюер 1", "a"));
            var result = new FinalTranscriptBuilder().build(audio, List.of(word(0, "раз", .2, .5),
                    word(0, "ответ", 1.2, 1.5), word(0, "два", 2.2, 2.5),
                    word(0, "снова", 3.2, 3.5), word(0, "кто", 4.2, 4.5),
                    word(1, "местный", 1.2, 1.5)), spans, false);
            assertEquals(List.of("Интервьюер 2", "Кандидат", "Интервьюер 3", "Интервьюер 2", "Не определено", "Интервьюер 1"),
                    result.stream().map(row -> row.get("role")).toList());
            assertTrue(result.stream().allMatch(row -> "whisper".equals(row.get("model"))));
        }
    }

    @Test void removesOnlyStronglyCorrelatedMultiwordEcho() throws Exception {
        byte[] system = new byte[32_000], microphone = new byte[32_000];
        for (int i = 0; i < 16_000; i++) {
            short sample = (short) ((i * 73 % 4000) - 2000);
            system[i * 2] = (byte) sample; system[i * 2 + 1] = (byte) (sample >> 8);
            short echo = (short) (sample / 4);
            microphone[i * 2] = (byte) echo; microphone[i * 2 + 1] = (byte) (echo >> 8);
        }
        try (var audio = new InterviewAudioSpool(directory, "session", true)) {
            audio.append(0, system, microphone); audio.seal(16_000);
            List<PostInterviewWhisper.Word> words = new ArrayList<>();
            for (int channel = 0; channel < 2; channel++) {
                words.add(word(channel, "один", 0, .3));
                words.add(word(channel, "два", .3, .6));
                words.add(word(channel, "три", .6, .9));
            }
            var result = new FinalTranscriptBuilder().build(audio, words, List.of(), false);
            assertEquals(3, result.size());
            assertTrue(result.stream().allMatch(row -> "system".equals(row.get("source"))));
            var shortOverlap = new FinalTranscriptBuilder().build(audio,
                    List.of(words.get(0), words.get(1), words.get(3), words.get(4)), List.of(), false);
            assertEquals(4, shortOverlap.size());
        }
    }

    @Test void sameWordsFromDifferentSignalsAreNotDeleted() throws Exception {
        byte[] system = new byte[32_000], microphone = new byte[32_000];
        for (int i = 0; i < 16_000; i++) {
            short a = (short) ((i * 73 % 4000) - 2000), b = (short) ((i * 127 % 4000) - 2000);
            system[2 * i] = (byte) a; system[2 * i + 1] = (byte) (a >> 8);
            microphone[2 * i] = (byte) b; microphone[2 * i + 1] = (byte) (b >> 8);
        }
        try (var audio = new InterviewAudioSpool(directory, "overlap", true)) {
            audio.append(0, system, microphone); audio.seal(16_000);
            List<PostInterviewWhisper.Word> words = new ArrayList<>();
            for (int channel = 0; channel < 2; channel++) {
                words.add(word(channel, "один", 0, .3));
                words.add(word(channel, "два", .3, .6));
                words.add(word(channel, "три", .6, .9));
            }
            assertEquals(6, new FinalTranscriptBuilder().build(audio, words, List.of(), false).size());
        }
    }
}
