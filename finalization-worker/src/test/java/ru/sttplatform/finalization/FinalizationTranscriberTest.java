package ru.sttplatform.finalization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.stt.transcript.ArchiveWave;
import ru.sttplatform.stt.whisper.WhisperClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinalizationTranscriberTest {
    @TempDir Path directory;

    @Test
    void restoresMonoAnswerAndRequiresCompleteJournal() throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        Path pcm = directory.resolve("source.pcm"), wave = directory.resolve("source.wav"), journal = directory.resolve("events.jsonl");
        Files.write(pcm, new byte[32000]);
        ArchiveWave.write(pcm, wave, 1, 16000);
        String prefix = "{\"sessionId\":\"" + id + "\",\"eventSequence\":0,\"audioSample\":0,\"attemptId\":1,\"type\":\"ready\",\"questionId\":\"Q1\"}\n"
                + "{\"sessionId\":\"" + id + "\",\"eventSequence\":1,\"audioSample\":16000,\"attemptId\":1,\"type\":\"decision\",\"questionId\":\"Q1\",\"answerId\":1}\n";
        Files.writeString(journal, prefix + "{\"sessionId\":\"" + id + "\",\"eventSequence\":2,\"audioSample\":16000,\"attemptId\":1,\"type\":\"journal_complete\"}\n");
        var input = Json.tree("{\"schemaVersion\":1,\"sessionId\":\"" + id + "\",\"sampleRate\":16000,\"samples\":16000,\"channels\":1,\"testMode\":true,\"words\":[],\"roles\":[]}");
        var whisper = mock(WhisperClient.class);
        when(whisper.post(anyString(), any(byte[].class))).thenReturn(new WhisperClient.Reply(200,
                "{\"words\":[{\"word\":\"ответ\",\"start\":0.1,\"end\":0.4}]}"));
        var result = new FinalizationTranscriber().transcribe(id, wave, journal, input, directory.resolve("output.pcm"), whisper);
        assertEquals("whisper", result.source());
        assertEquals("ответ", result.answers().get(0).get("text"));
        Files.writeString(journal, prefix);
        assertThrows(java.io.IOException.class, () -> new FinalizationTranscriber().transcribe(
                id, wave, journal, input, directory.resolve("another.pcm"), whisper));
    }

    @Test
    void transcribesBothSynchronizedChannelsAndUsesCandidateAnswer() throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        int samples = 16_000;
        ByteBuffer pcm = ByteBuffer.allocate(samples * 4).order(ByteOrder.LITTLE_ENDIAN);
        byte[] system = new byte[samples * 2], microphone = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            short first = (short) (1000 + i % 100), second = (short) (-4000 - i % 100);
            pcm.putShort(first).putShort(second);
            ByteBuffer.wrap(system, i * 2, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(first);
            ByteBuffer.wrap(microphone, i * 2, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(second);
        }
        Path source = directory.resolve("stereo.pcm"), wave = directory.resolve("stereo.wav");
        Path journal = directory.resolve("stereo.jsonl");
        Files.write(source, pcm.array());
        ArchiveWave.write(source, wave, 2, samples);
        writeCompleteJournal(id, journal, samples);
        var input = Json.tree("{\"schemaVersion\":1,\"sessionId\":\"" + id
                + "\",\"sampleRate\":16000,\"samples\":" + samples
                + ",\"channels\":2,\"testMode\":false,\"words\":[],"
                + "\"roles\":[{\"start\":0,\"end\":16000,\"role\":\"Кандидат\",\"speaker\":\"candidate-1\"}]}");
        var whisper = mock(WhisperClient.class);
        List<byte[]> sent = new ArrayList<>();
        when(whisper.post(anyString(), any(byte[].class))).thenAnswer(call -> {
            sent.add(call.getArgument(1));
            String word = sent.size() == 1 ? "ответ" : "вопрос";
            return new WhisperClient.Reply(200, "{\"words\":[{\"word\":\"" + word
                    + "\",\"start\":0.1,\"end\":0.4}]}");
        });

        var result = new FinalizationTranscriber().transcribe(id, wave, journal, input,
                directory.resolve("stereo-restored.pcm"), whisper);

        assertEquals(2, sent.size());
        assertArrayEquals(system, sent.get(0));
        assertArrayEquals(microphone, sent.get(1));
        assertEquals("ответ", result.answers().get(0).get("text"));
        assertTrue(result.words().stream().anyMatch(word -> "Интервьюер 1".equals(word.get("role"))));
    }

    @Test
    void onlyUsesExplicitVoskFallbackAfterRetriesAndRejectsMissingFallback() throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        Path pcm = directory.resolve("fallback.pcm"), wave = directory.resolve("fallback.wav");
        Path journal = directory.resolve("fallback.jsonl");
        Files.write(pcm, new byte[32000]);
        ArchiveWave.write(pcm, wave, 1, 16000);
        writeCompleteJournal(id, journal, 16000);
        var input = Json.tree("{\"schemaVersion\":1,\"sessionId\":\"" + id
                + "\",\"sampleRate\":16000,\"samples\":16000,\"channels\":1,\"testMode\":true,"
                + "\"words\":[{\"model\":\"vosk\",\"source\":\"system\",\"start\":0.1,\"end\":0.4,\"word\":\"резерв\"}],\"roles\":[]}");
        var whisper = mock(WhisperClient.class);
        when(whisper.post(anyString(), any(byte[].class))).thenReturn(new WhisperClient.Reply(503, "недоступен"));
        var transcriber = new FinalizationTranscriber();

        assertThrows(java.io.IOException.class, () -> transcriber.transcribe(id, wave, journal, input,
                directory.resolve("retry.pcm"), whisper));
        var fallback = transcriber.transcribe(id, wave, journal, input,
                directory.resolve("fallback-output.pcm"), whisper, true);
        assertEquals("vosk_fallback", fallback.source());
        assertEquals("резерв", fallback.answers().get(0).get("text"));
        assertTrue(fallback.words().stream().anyMatch(word -> String.valueOf(word.get("role")).contains("резерв Vosk")));

        var noReserve = input.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) noReserve).set("words", Json.MAPPER.createArrayNode());
        assertThrows(java.io.IOException.class, () -> transcriber.transcribe(id, wave, journal, noReserve,
                directory.resolve("no-reserve.pcm"), whisper, true));
    }

    private static void writeCompleteJournal(String id, Path journal, int samples) throws Exception {
        String prefix = "{\"sessionId\":\"" + id + "\",\"eventSequence\":0,\"audioSample\":0,\"attemptId\":1,\"type\":\"ready\",\"questionId\":\"Q1\"}\n"
                + "{\"sessionId\":\"" + id + "\",\"eventSequence\":1,\"audioSample\":" + samples
                + ",\"attemptId\":1,\"type\":\"decision\",\"questionId\":\"Q1\",\"answerId\":1}\n";
        Files.writeString(journal, prefix + "{\"sessionId\":\"" + id + "\",\"eventSequence\":2,\"audioSample\":"
                + samples + ",\"attemptId\":1,\"type\":\"journal_complete\"}\n");
    }
}
