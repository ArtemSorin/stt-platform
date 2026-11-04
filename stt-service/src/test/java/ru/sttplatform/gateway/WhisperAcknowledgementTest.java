package ru.sttplatform.gateway.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.stt.transcript.InterviewAudioSpool;
import ru.sttplatform.stt.transcript.FinalTranscriptBuilder;
import ru.sttplatform.stt.transcript.TranscriptStore;
import ru.sttplatform.gateway.config.GatewayProperties;
import ru.sttplatform.stt.whisper.WhisperClient;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WhisperAcknowledgementTest {
    @TempDir Path directory;

    @Test void acknowledgementUsesCachedWordsAndRetractionRestoresVosk() throws Exception {
        Orchestrator orchestrator = mock(Orchestrator.class, CALLS_REAL_METHODS);
        var rows = new ArrayList<Map<String, Object>>();
        rows.add(new LinkedHashMap<>(Map.of("word", "прежний", "start", 1.0, "end", 1.5,
                "role", "Кандидат", "source", "system", "model", "vosk", "status", "final", "word_id", "a")));
        rows.add(new LinkedHashMap<>(Map.of("word", "вопрос", "start", 1.0, "end", 1.5,
                "role", "Интервьюер 1", "source", "microphone", "model", "vosk", "status", "final", "word_id", "b")));
        set(orchestrator, "sessionId", "session");
        set(orchestrator, "directory", directory);
        set(orchestrator, "timelineLog", new BufferedWriter(new StringWriter()));
        set(orchestrator, "expectedAnalyticsSequence", 1L);
        set(orchestrator, "finalWords", rows);
        set(orchestrator, "preliminaryWords", new HashMap<>());
        set(orchestrator, "decisionTimes", new HashMap<>());
        set(orchestrator, "whisperLatencies", new ArrayList<>());
        set(orchestrator, "answerBases", new HashMap<>(Map.of(3, 16000L)));
        set(orchestrator, "decisionAudioEnds", new HashMap<>(Map.of(3, 32000L)));
        set(orchestrator, "cancelledWhisperAnswers", new HashSet<>());
        set(orchestrator, "acceptedWhisperRanges", new HashMap<>());
        set(orchestrator, "whisperCompletion", new ConcurrentHashMap<>());
        set(orchestrator, "pendingWhisperWords", new ConcurrentHashMap<>(Map.of(3,
                Json.tree("[{\"word\":\"новый\",\"start\":0.1,\"end\":0.5}]"))));
        set(orchestrator, "lastWhisperAnswerId", 3);
        ReflectionTestUtils.invokeMethod(orchestrator, "onInterview",
                Json.tree("{\"type\":\"refined\",\"sessionId\":\"session\",\"sequence\":1,\"answerId\":3,\"text\":\"новый\"}"));
        assertEquals("replaced_by_whisper", rows.get(0).get("status"));
        assertEquals("final", rows.get(1).get("status"));
        assertEquals("новый", rows.get(2).get("word"));
        assertEquals(1.1, rows.get(2).get("start"));
        ReflectionTestUtils.invokeMethod(orchestrator, "onInterview",
                Json.tree("{\"type\":\"retract\",\"sessionId\":\"session\",\"sequence\":2}"));
        assertEquals(2, rows.size());
        assertEquals("final", rows.get(0).get("status"));
        assertNull(ReflectionTestUtils.getField(orchestrator, "refined"));
        ReflectionTestUtils.invokeMethod(orchestrator, "onInterview",
                Json.tree("{\"type\":\"refined\",\"sessionId\":\"session\",\"sequence\":3,\"answerId\":3,\"text\":\"запоздалый\"}"));
        assertNull(ReflectionTestUtils.getField(orchestrator, "refined"));
        assertEquals(2, rows.size());
    }

    @Test void incompleteWhisperDoesNotEraseEarlierVoskWords() throws Exception {
        Orchestrator orchestrator = mock(Orchestrator.class, CALLS_REAL_METHODS);
        var rows = new ArrayList<Map<String, Object>>();
        rows.add(new LinkedHashMap<>(Map.of("word", "начало", "start", 1.0, "end", 1.4,
                "role", "Кандидат", "source", "system", "model", "vosk", "status", "final", "word_id", "a")));
        rows.add(new LinkedHashMap<>(Map.of("word", "конец", "start", 4.0, "end", 4.5,
                "role", "Кандидат", "source", "system", "model", "vosk", "status", "final", "word_id", "b")));
        set(orchestrator, "sessionId", "session");
        set(orchestrator, "directory", directory);
        set(orchestrator, "timelineLog", new BufferedWriter(new StringWriter()));
        set(orchestrator, "expectedAnalyticsSequence", 1L);
        set(orchestrator, "finalWords", rows);
        set(orchestrator, "preliminaryWords", new HashMap<>());
        set(orchestrator, "decisionTimes", new HashMap<>());
        set(orchestrator, "whisperLatencies", new ArrayList<>());
        set(orchestrator, "answerBases", new HashMap<>(Map.of(3, 0L)));
        set(orchestrator, "decisionAudioEnds", new HashMap<>(Map.of(3, 80_000L)));
        set(orchestrator, "cancelledWhisperAnswers", new HashSet<>());
        set(orchestrator, "acceptedWhisperRanges", new HashMap<>());
        set(orchestrator, "whisperCompletion", new ConcurrentHashMap<>());
        set(orchestrator, "pendingWhisperWords", new ConcurrentHashMap<>(Map.of(3,
                Json.tree("[{\"word\":\"уточнённый\",\"start\":4.0,\"end\":4.5}]"))));
        ReflectionTestUtils.invokeMethod(orchestrator, "onInterview",
                Json.tree("{\"type\":\"refined\",\"sessionId\":\"session\",\"sequence\":1,\"answerId\":3,\"text\":\"уточнённый\"}"));
        assertEquals("final", rows.get(0).get("status"));
        assertEquals("replaced_by_whisper", rows.get(1).get("status"));
    }

    @Test void finalPassUsesBothChannelsAndExcludesLiveAndVoskWords() throws Exception {
        Orchestrator orchestrator = mock(Orchestrator.class, CALLS_REAL_METHODS);
        WhisperClient client = mock(WhisperClient.class);
        when(client.post(eq("/transcribe-json"), any(byte[].class))).thenAnswer(call -> {
            byte[] pcm = call.getArgument(1);
            String text = pcm[0] == 1 ? "кандидат" : "интервьюер";
            return new WhisperClient.Reply(200, "{\"words\":[{\"word\":\"" + text + "\",\"start\":0.1,\"end\":0.5}]}");
        });
        try (var source = new InterviewAudioSpool(directory, "session", true)) {
            byte[] system = new byte[32_000], mic = new byte[32_000];
            java.util.Arrays.fill(system, (byte) 1); java.util.Arrays.fill(mic, (byte) 2);
            source.append(0, system, mic); source.seal(16_000);
            set(orchestrator, "audioSpool", source);
            set(orchestrator, "whisperClient", client);
            set(orchestrator, "timelineLog", new BufferedWriter(new StringWriter()));
            set(orchestrator, "finalWords", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of(
                    "word", "старое", "start", .1, "end", .5, "model", "vosk", "source", "system")))));
            set(orchestrator, "finalDiarizationSpans", List.of(new FinalTranscriptBuilder.SpeakerSpan(0, 16_000, "Кандидат", "c")));
            set(orchestrator, "spans", new ArrayList<>(List.of(new Orchestrator.VoiceSpan(0, 16_000, "Интервьюер 9", "c", true))));
            ReflectionTestUtils.invokeMethod(orchestrator, "postprocessWhisper");
            var result = orchestrator.finalTranscriptWords();
            assertEquals(List.of("кандидат", "интервьюер"), result.stream().map(row -> row.get("word")).toList());
            assertEquals(List.of("Кандидат", "Интервьюер 1"), result.stream().map(row -> row.get("role")).toList());
            assertTrue(orchestrator.transcriptComplete());
        }
    }

    @Test void finalPassUsesMarkedVoskOnlyForTheFailedChannel() throws Exception {
        Orchestrator orchestrator = mock(Orchestrator.class, CALLS_REAL_METHODS);
        WhisperClient client = mock(WhisperClient.class);
        when(client.post(eq("/transcribe-json"), any(byte[].class))).thenAnswer(call -> {
            byte[] pcm = call.getArgument(1);
            return pcm[0] == 1 ? new WhisperClient.Reply(503, "unavailable")
                    : new WhisperClient.Reply(200, "{\"words\":[{\"word\":\"вопрос\",\"start\":0.1,\"end\":0.5}]}");
        });
        try (var source = new InterviewAudioSpool(directory, "session", true)) {
            byte[] system = new byte[32_000], mic = new byte[32_000];
            java.util.Arrays.fill(system, (byte) 1); java.util.Arrays.fill(mic, (byte) 2);
            source.append(0, system, mic); source.seal(16_000);
            set(orchestrator, "audioSpool", source);
            set(orchestrator, "whisperClient", client);
            set(orchestrator, "timelineLog", new BufferedWriter(new StringWriter()));
            set(orchestrator, "finalWords", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of(
                    "word", "резерв", "start", .1, "end", .5, "model", "vosk", "source", "system", "status", "replaced_by_whisper")))));
            set(orchestrator, "finalDiarizationSpans", List.of(new FinalTranscriptBuilder.SpeakerSpan(0, 16_000, "Кандидат", "c")));
            ReflectionTestUtils.invokeMethod(orchestrator, "postprocessWhisper");
            var result = orchestrator.finalTranscriptWords();
            assertEquals(2, result.size());
            assertTrue(result.stream().anyMatch(row -> "вопрос".equals(row.get("word")) && "whisper".equals(row.get("model"))));
            assertTrue(result.stream().anyMatch(row -> "резерв".equals(row.get("word")) && "Кандидат (резерв Vosk)".equals(row.get("role"))));
            assertTrue(orchestrator.transcriptFallback());
            assertTrue(orchestrator.transcriptComplete());
            Path sessionDir = directory.resolve("output").resolve("session");
            var store = new TranscriptStore(new GatewayProperties(null, null, sessionDir.getParent().toString()));
            store.publish(sessionDir, "83394956-cbfb-4ce8-8f4c-a746a6ba2287", result);
            String markdown = java.nio.file.Files.readString(sessionDir.resolve("transcripts/transcript.md"));
            assertTrue(markdown.contains("Кандидат (резерв Vosk): резерв"));
            assertTrue(markdown.contains("Интервьюер 1: вопрос"));
        }
    }

    @Test void marksUnrecognizedAudioWhenNeitherWhisperNorVoskHasWords() throws Exception {
        Orchestrator orchestrator = mock(Orchestrator.class, CALLS_REAL_METHODS);
        WhisperClient client = mock(WhisperClient.class);
        when(client.post(eq("/transcribe-json"), any(byte[].class))).thenReturn(new WhisperClient.Reply(503, "unavailable"));
        try (var source = new InterviewAudioSpool(directory, "session", true)) {
            source.append(0, new byte[32_000], new byte[32_000]); source.seal(16_000);
            set(orchestrator, "audioSpool", source);
            set(orchestrator, "whisperClient", client);
            set(orchestrator, "timelineLog", new BufferedWriter(new StringWriter()));
            set(orchestrator, "finalWords", new ArrayList<>());
            set(orchestrator, "finalDiarizationSpans", List.of());
            ReflectionTestUtils.invokeMethod(orchestrator, "postprocessWhisper");
            var result = orchestrator.finalTranscriptWords();
            assertEquals(2, result.size());
            assertTrue(result.stream().allMatch(row -> "[Аудио этого участка не распознано]".equals(row.get("word"))));
            assertTrue(orchestrator.transcriptFallback());
        }
    }

    private static void set(Object target, String name, Object value) {
        ReflectionTestUtils.setField(target, name, value);
    }
}
