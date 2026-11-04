package ru.sttplatform.gateway.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.common.ws.WsPeer;
import ru.sttplatform.stt.transcript.LiveWhisperChunks;
import ru.sttplatform.stt.whisper.WhisperClient;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LiveWhisperFlowTest {
    @TempDir Path directory;

    @Test void longAttemptIsCombinedAndAcceptedThroughAnalyticsAcknowledgement() throws Exception {
        WhisperClient client = mock(WhisperClient.class);
        when(client.post(anyString(), any(byte[].class))).thenReturn(new WhisperClient.Reply(200,
                "{\"words\":[{\"word\":\"слово\",\"start\":0.1,\"end\":0.4}]}"));
        var chunks = new LiveWhisperChunks(client, Runnable::run, 0);
        chunks.append(0, new byte[30 * 32_000]);
        verify(client).post(anyString(), any(byte[].class));
        chunks.append(30 * 16_000L, new byte[32_000]);

        var orchestrator = mock(Orchestrator.class, CALLS_REAL_METHODS);
        var peer = mock(WsPeer.class);
        when(peer.isOpen()).thenReturn(true);
        set(orchestrator, "whisperEnabled", true); set(orchestrator, "testMode", true);
        set(orchestrator, "sessionId", "session"); set(orchestrator, "interview", peer);
        set(orchestrator, "analyticsSendLock", new Object());
        set(orchestrator, "liveByAttempt", new ConcurrentHashMap<>(Map.of(7L, chunks)));
        set(orchestrator, "answerAttempts", new ConcurrentHashMap<>(Map.of(3, 7L)));
        set(orchestrator, "answerBases", new HashMap<>(Map.of(3, 0L)));
        set(orchestrator, "decisionAudioEnds", new HashMap<>(Map.of(3, 31 * 16_000L)));
        set(orchestrator, "cancelledWhisperAnswers", ConcurrentHashMap.newKeySet());
        set(orchestrator, "whisperCompletion", new ConcurrentHashMap<Integer, CompletableFuture<Void>>());
        set(orchestrator, "pendingWhisperWords", new ConcurrentHashMap<>());
        set(orchestrator, "expectedAnalyticsSequence", 1L);
        set(orchestrator, "decisionTimes", new HashMap<>()); set(orchestrator, "whisperLatencies", new ArrayList<>());
        set(orchestrator, "acceptedWhisperRanges", new HashMap<>());
        set(orchestrator, "finalWords", new ArrayList<>()); set(orchestrator, "preliminaryWords", new HashMap<>());
        set(orchestrator, "directory", directory); set(orchestrator, "timelineLog", new BufferedWriter(new StringWriter()));
        ReflectionTestUtils.invokeMethod(orchestrator, "startWhisperRefinement", 3);
        verify(peer, timeout(3000)).sendJson(argThat(value -> value instanceof Map<?, ?> m && "refined".equals(m.get("type"))));
        ReflectionTestUtils.invokeMethod(orchestrator, "onInterview",
                Json.tree("{\"type\":\"refined\",\"sessionId\":\"session\",\"sequence\":1,\"answerId\":3,\"text\":\"слово слово\"}"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) ReflectionTestUtils.getField(orchestrator, "finalWords");
        assertEquals(List.of(.1, 30.1), rows.stream().map(row -> row.get("start")).toList());
    }

    @Test void cancelledAnswerDoesNotSendLateResult() throws Exception {
        WhisperClient client = mock(WhisperClient.class);
        when(client.post(anyString(), any(byte[].class))).thenReturn(new WhisperClient.Reply(200,
                "{\"words\":[{\"word\":\"слово\",\"start\":0.1,\"end\":0.4}]}"));
        List<Runnable> queued = new ArrayList<>();
        var chunks = new LiveWhisperChunks(client, queued::add, 0);
        chunks.append(0, new byte[30 * 32_000]);
        var orchestrator = mock(Orchestrator.class, CALLS_REAL_METHODS);
        var peer = mock(WsPeer.class); when(peer.isOpen()).thenReturn(true);
        var cancelled = ConcurrentHashMap.<Integer>newKeySet();
        var completions = new ConcurrentHashMap<Integer, CompletableFuture<Void>>();
        set(orchestrator, "whisperEnabled", true); set(orchestrator, "testMode", true);
        set(orchestrator, "sessionId", "session"); set(orchestrator, "interview", peer);
        set(orchestrator, "analyticsSendLock", new Object());
        set(orchestrator, "liveByAttempt", new ConcurrentHashMap<>(Map.of(7L, chunks)));
        set(orchestrator, "answerAttempts", new ConcurrentHashMap<>(Map.of(3, 7L)));
        set(orchestrator, "answerBases", new HashMap<>(Map.of(3, 0L)));
        set(orchestrator, "decisionAudioEnds", new HashMap<>(Map.of(3, 30 * 16_000L)));
        set(orchestrator, "cancelledWhisperAnswers", cancelled); set(orchestrator, "whisperCompletion", completions);
        set(orchestrator, "pendingWhisperWords", new ConcurrentHashMap<>());
        ReflectionTestUtils.invokeMethod(orchestrator, "startWhisperRefinement", 3);
        cancelled.add(3); queued.get(0).run();
        completions.get(3).get(3, TimeUnit.SECONDS);
        verify(peer, never()).sendJson(any());
    }

    private static void set(Object target, String field, Object value) { ReflectionTestUtils.setField(target, field, value); }
}
