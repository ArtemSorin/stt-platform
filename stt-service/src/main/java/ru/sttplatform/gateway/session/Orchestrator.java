package ru.sttplatform.gateway.session;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.sttplatform.common.audio.Pcm;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.common.ws.WsPeer;
import ru.sttplatform.stt.integration.EndpointDetector;
import ru.sttplatform.stt.integration.IntegrationProperties;
import ru.sttplatform.stt.whisper.WhisperClient;
import ru.sttplatform.stt.transcript.PostInterviewWhisper;
import ru.sttplatform.stt.transcript.InterviewAudioSpool;
import ru.sttplatform.stt.transcript.FinalTranscriptBuilder;
import ru.sttplatform.stt.transcript.LiveWhisperChunks;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;

/**
 * Передаёт аудио и события между диаризацией и WebSocket-сервисами STT и аналитики.
 *
 * @version 1.0.0
 */
public final class Orchestrator implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Orchestrator.class);
    private static final int RATE = Pcm.RATE;
    private static final int HOLD_SAMPLES = RATE;
    // Длинная непрерывная речь может получить окончательную роль только после паузы.
    private static final int MAX_UNCONFIRMED_SAMPLES = 120 * RATE;
    private static final double VOICE_RMS_FLOOR = 250;
    private static final byte[] END = new byte[0];
    /** Блок смешанного аудио с позицией первого сэмпла.
     *
     * @version 1.0.0
     */
    private record AudioBlock(long first, byte[] mixed) {
        long end() { return first + mixed.length / 2; }
    }
    /** Интервал голоса с предварительной или подтверждённой ролью.
     *
     * @version 1.0.0
     */
    record VoiceSpan(long start, long end, String label, String speaker, boolean preliminary) {}
    record SampleRange(long start, long end) {}
    /** Фрагмент речи, ожидающий окончательного подтверждения роли.
     *
     * @version 1.0.0
     */
    private static final class PendingSpan {
        long start, lastSeen;
        PendingSpan(long start, long lastSeen) { this.start = start; this.lastSeen = lastSeen; }
    }

    private final Path directory;
    private final BufferedWriter sttLog, timelineLog;
    private InterviewEventJournal interviewJournal;
    private final ArrayBlockingQueue<byte[]> sttQueue = new ArrayBlockingQueue<>(128);
    private final ArrayDeque<AudioBlock> audio = new ArrayDeque<>();
    private final List<VoiceSpan> spans = new ArrayList<>();
    private final List<SampleRange> testCandidateEnabledRanges = new ArrayList<>();
    private final Map<String, PendingSpan> pendingSpans = new HashMap<>();
    private final Map<String, Integer> activePreviewVotes = new HashMap<>();
    private final Map<String, String> activePreviewLabels = new HashMap<>();
    private final Map<String, Long> candidatePreviewEnds = new HashMap<>();
    private final Map<String, VoiceSpan> provisionalSpans = new HashMap<>();
    private final Set<String> seenSegments = new HashSet<>();
    private final Set<String> seenUiEvents = new HashSet<>();
    private final List<Map<String, Object>> finalWords = new ArrayList<>();
    private final Map<String, List<Map<String, Object>>> preliminaryWords = new HashMap<>();
    private final Map<String, Integer> utteranceNumbers = new HashMap<>();
    private final List<Double> uiLatencies = new ArrayList<>();
    private final List<Double> decisionLatencies = new ArrayList<>();
    private final List<Double> diarizationLatencies = new ArrayList<>();
    private final List<Double> roleWaitLatencies = new ArrayList<>();
    private final List<Double> voskLatencies = new ArrayList<>();
    private final List<Double> partialLatencies = new ArrayList<>();
    private final List<Double> previewLatencies = new ArrayList<>();
    private final List<Double> endToPreliminaryHintLatencies = new ArrayList<>();
    private final List<Double> preliminaryHintLeadLatencies = new ArrayList<>();
    private final List<Double> peekComputeLatencies = new ArrayList<>();
    private final List<Double> roleLatencies = new ArrayList<>();
    private final List<Double> candidateDispatchLatencies = new ArrayList<>();
    private final List<Double> whisperLatencies = new ArrayList<>();
    private final Map<Integer, Long> answerBases = new HashMap<>();
    private final Map<Integer, String> voskAnswerText = new HashMap<>();
    private final Map<Integer, Long> answerAttempts = new ConcurrentHashMap<>();
    private final Map<Integer, Long> decisionTimes = new HashMap<>();
    private final Map<Integer, Long> decisionAudioEnds = new HashMap<>();
    private final Map<String, Long> previewEventSamples = new HashMap<>();
    private final Map<String, Long> previewEventVisibleNanos = new HashMap<>();
    private final Map<String, Long> peekRequestSamples = new HashMap<>();
    private final Map<String, Long> peekRequestNanos = new HashMap<>();
    private final Set<Integer> measuredHintAnswers = new HashSet<>();
    private final List<Double> cpuLoadPercent = new CopyOnWriteArrayList<>();
    private final List<Double> systemCpuLoadPercent = new CopyOnWriteArrayList<>();
    private final CountDownLatch reportReceived = new CountDownLatch(1);
    private final CountDownLatch finalizationReady = new CountDownLatch(1);
    private final boolean deferredFinalReport;
    private final long startedNanos = System.nanoTime();
    private final WsPeer stt, interview;
    private final IntegrationProperties integrationProps;
    /** VAD обрабатывает только уже подтверждённую речь кандидата; второй ASR не создаётся. */
    private final EndpointDetector candidateDetector;
    private final WhisperClient whisperClient;
    private final InterviewAudioSpool audioSpool;
    private final String sessionId;
    private final boolean whisperEnabled;
    private final boolean testMode;
    private final boolean separateChannels;
    private final String candidateName;
    private final String internalToken = System.getenv().getOrDefault("INTERVIEW_EVENTS_TOKEN", "");
    private final Object analyticsSendLock = new Object();
    private long analyticsSequence = 1;
    private long expectedAnalyticsSequence = 1;
    private long nextAttemptId;
    private volatile long currentAttemptId;
    private final Thread sttWorker;
    private final ThreadPoolExecutor liveWhisperExecutor;
    private final Map<Long, LiveWhisperChunks> liveByStart = new ConcurrentHashMap<>();
    private final Map<Long, LiveWhisperChunks> liveByAttempt = new ConcurrentHashMap<>();
    private LiveWhisperChunks activeLiveWhisper;
    private final Set<Integer> cancelledWhisperAnswers = ConcurrentHashMap.newKeySet();
    private final Map<Integer, JsonNode> pendingWhisperWords = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<Void>> whisperCompletion = new ConcurrentHashMap<>();
    private final Map<Integer, List<SampleRange>> acceptedWhisperRanges = new HashMap<>();
    private boolean postprocessStarted;
    private TranscriptTiming transcriptTiming;
    private volatile List<Map<String, Object>> fullTranscriptWords;
    private volatile String transcriptError;
    private volatile int transcriptChunksDone, transcriptChunksTotal;
    private List<FinalTranscriptBuilder.SpeakerSpan> finalDiarizationSpans = List.of();
    private volatile int lastWhisperAnswerId;
    private final Thread cpuSampler;
    private long capturedSamples, gatedSamples, lastCapturedNanos, lastCandidateEnd;
    private long readySample, answerStartSample;
    private long questionAvailableNanos;
    private Map<String, Object> pendingQuestion;
    private int pendingQuestionAnswerId;
    private boolean questionPending;
    private long nextEventId;
    private long nextPeekRequestId;
    private long lastAppliedPeekRequestId;
    private volatile String mixedPartial = "", whisperState = "выключен", failure;
    private volatile String candidateWarning;
    private volatile boolean transcriptFallback;
    private volatile String currentRole = "Ожидаем речь";
    private final StringBuilder mixedTranscript = new StringBuilder();
    private volatile Map<String, Object> question, decision, refined, preview, finalReport;
    private volatile String reportTranscriptSource;
    private volatile boolean reportScoringIncomplete;
    private volatile boolean reportTranscriptIncomplete;
    private volatile boolean reportReferencesMissing;
    private volatile Map<String, Object> excelReport;
    private volatile String excelError;
    private volatile String decisionEventId;
    private volatile String previewEventId;
    private volatile long decisionAudioEnd, decisionAtNanos;
    private long lastPartialSample, lastRoleSample, lastCandidateDispatchSample;
    private long lastPeekSample;
    private long lastPeekAtNanos;
    private String lastPeekText = "";
    private String lastCandidateSnapshotText = "";
    private volatile boolean closed;
    private volatile boolean finishing;
    private boolean answerArmed;
    private volatile boolean decisionRetracted;
    private volatile boolean lastPeekCandidateEvidence;
    private int sttEvents, unknownWords, lateCandidateSegments;
    private long sttQueueOverflowCount, maxSttQueueDepth;
    private long microphoneFrames, microphoneSamples;
    private long systemFrames;

    /** Создаёт оркестратор основного сценария с включённой диаризацией.
     * @param directory каталог артефактов сессии
     * @param sttBaseUrl адрес STT-сервиса
     * @param analyticsBaseUrl адрес сервиса аналитики
     * @param sessionId идентификатор интервью
     * @param whisperEnabled включено ли уточнение через Whisper
     * @param integrationProps настройки определения конца ответа
     * @param whisperClient HTTP-клиент помощника Whisper
     * @throws java.io.IOException если не удалось открыть журналы сессии
     */
    public Orchestrator(Path directory, String sttBaseUrl, String analyticsBaseUrl,
                         String sessionId, boolean whisperEnabled,
                        IntegrationProperties integrationProps, WhisperClient whisperClient) throws java.io.IOException {
        this(directory, sttBaseUrl, analyticsBaseUrl, sessionId, whisperEnabled,
                integrationProps, whisperClient, false);
    }

    /** Создаёт оркестратор с возможностью отключить диаризацию в тестовом сценарии.
     * @param directory каталог артефактов сессии
     * @param sttBaseUrl адрес STT-сервиса
     * @param analyticsBaseUrl адрес сервиса аналитики
     * @param sessionId идентификатор интервью
     * @param whisperEnabled включено ли уточнение через Whisper
     * @param integrationProps настройки определения конца ответа
     * @param whisperClient HTTP-клиент помощника Whisper
     * @param testMode отключает диаризацию для изолированного теста аналитики
     * @throws java.io.IOException если не удалось открыть журналы сессии
     */
    public Orchestrator(Path directory, String sttBaseUrl, String analyticsBaseUrl,
                         String sessionId, boolean whisperEnabled,
                        IntegrationProperties integrationProps, WhisperClient whisperClient,
                        boolean testMode) throws java.io.IOException {
        this(directory, sttBaseUrl, analyticsBaseUrl, sessionId, whisperEnabled,
                integrationProps, whisperClient, testMode, "");
    }

    /** Создаёт оркестратор основной или тестовой сессии с именем кандидата в отчёте.
     * @param directory каталог артефактов сессии
     * @param sttBaseUrl адрес STT-сервиса
     * @param analyticsBaseUrl адрес сервиса аналитики
     * @param sessionId идентификатор интервью
     * @param whisperEnabled включено ли уточнение через Whisper
     * @param integrationProps настройки определения конца ответа
     * @param whisperClient HTTP-клиент помощника Whisper
     * @param testMode отключает диаризацию для изолированного теста аналитики
     * @param candidateName имя кандидата для отчёта
     * @throws java.io.IOException если не удалось открыть журналы сессии
     */
    public Orchestrator(Path directory, String sttBaseUrl, String analyticsBaseUrl,
                         String sessionId, boolean whisperEnabled,
                        IntegrationProperties integrationProps, WhisperClient whisperClient,
                        boolean testMode, String candidateName) throws java.io.IOException {
        this(directory, sttBaseUrl, analyticsBaseUrl, sessionId, whisperEnabled,
                integrationProps, whisperClient, testMode, candidateName, false);
    }

    /** Открывает отдельный поток микрофона, если браузер передаёт два канала.
     * @param separateChannels включить независимое распознавание источников
     * @throws java.io.IOException если не удалось создать журналы
     */
    public Orchestrator(Path directory, String sttBaseUrl, String analyticsBaseUrl,
                         String sessionId, boolean whisperEnabled,
                        IntegrationProperties integrationProps, WhisperClient whisperClient,
                        boolean testMode, String candidateName, boolean separateChannels) throws java.io.IOException {
        this(directory, sttBaseUrl, analyticsBaseUrl, sessionId, whisperEnabled,
                integrationProps, whisperClient, testMode, candidateName, separateChannels,
                Path.of(System.getProperty("java.io.tmpdir"), "stt-platform", "audio"));
    }

    /** Создаёт оркестратор с заданным каталогом временной аудиозаписи.
     * @param directory каталог результатов
     * @param sttBaseUrl адрес STT
     * @param analyticsBaseUrl адрес аналитики
     * @param sessionId идентификатор сессии
     * @param whisperEnabled доступен ли Whisper
     * @param integrationProps настройки интеграции
     * @param whisperClient клиент Whisper
     * @param testMode тестовый сценарий
     * @param candidateName имя кандидата
     * @param separateChannels передаются ли два канала
     * @param spoolDirectory каталог временного PCM
     * @throws java.io.IOException если не удалось подготовить файлы
     */
    public Orchestrator(Path directory, String sttBaseUrl, String analyticsBaseUrl,
                         String sessionId, boolean whisperEnabled,
                        IntegrationProperties integrationProps, WhisperClient whisperClient,
                        boolean testMode, String candidateName, boolean separateChannels,
                        Path spoolDirectory) throws java.io.IOException {
        this(directory, sttBaseUrl, analyticsBaseUrl, sessionId, whisperEnabled,
                integrationProps, whisperClient, testMode, candidateName, separateChannels, spoolDirectory, false);
    }

    /** Создаёт оркестратор с итоговой оценкой после полной транскрипции. */
    public Orchestrator(Path directory, String sttBaseUrl, String analyticsBaseUrl,
                         String sessionId, boolean whisperEnabled,
                        IntegrationProperties integrationProps, WhisperClient whisperClient,
                        boolean testMode, String candidateName, boolean separateChannels,
                        Path spoolDirectory, boolean deferredFinalReport) throws java.io.IOException {
        this.deferredFinalReport = deferredFinalReport;
        this.directory = directory;
        this.sessionId = sessionId;
        this.whisperEnabled = whisperEnabled;
        this.testMode = testMode;
        this.separateChannels = separateChannels;
        this.candidateName = candidateName == null ? "" : candidateName;
        if (testMode) currentRole = "Кандидат · тестовый режим без диаризации";
        this.integrationProps = integrationProps;
        this.candidateDetector = integrationProps.newDetector();
        this.whisperClient = whisperClient;
        liveWhisperExecutor = whisperEnabled && !deferredFinalReport ? new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(4), runnable -> {
                    Thread thread = new Thread(runnable, "whisper-live"); thread.setDaemon(true); return thread;
                }) : null;
        Files.createDirectories(directory);
        InterviewAudioSpool createdSpool = null;
        // В отложенном режиме сохраняем исходную запись и при выключенном Whisper,
        // чтобы после резервного отчёта её можно было обработать повторно.
        if (whisperEnabled || deferredFinalReport) {
            try { createdSpool = new InterviewAudioSpool(spoolDirectory, sessionId, separateChannels); }
            catch (java.io.IOException error) { transcriptError = "Не удалось открыть временную запись аудио: " + error.getMessage(); }
        }
        audioSpool = createdSpool;
        sttLog = Files.newBufferedWriter(directory.resolve("stt-events.jsonl"), StandardCharsets.UTF_8);
        timelineLog = Files.newBufferedWriter(directory.resolve("timeline-events.jsonl"), StandardCharsets.UTF_8);
        interviewJournal = new InterviewEventJournal(directory.resolve("interview-events.jsonl"), sessionId, 1024);
        WsPeer openedStt = null, openedInterview = null;
        try {
            if (internalToken.length() < 32)
                throw new IllegalStateException("Задайте общий секрет INTERVIEW_EVENTS_TOKEN длиной не менее 32 символов.");
            openedStt = WsPeer.connect(wsUrl(sttBaseUrl) + "/ws/stt", json -> onStt(separateChannels ? "system" : "mixed", json),
                    () -> onPeerClosed("vosk-system", "Потоковый STT закрыл WebSocket."));
            openedInterview = WsPeer.connect(wsUrl(analyticsBaseUrl) + "/ws/interview-events",
                    Map.of("X-Internal-Token", internalToken), this::onInterview,
                    () -> onPeerClosed("analytics", "Внутренний WebSocket аналитики закрыт."));
        } catch (Exception error) {
            if (openedStt != null) try { openedStt.sendClose(1000, "startup failed"); } catch (Exception ignored) {}
            if (openedInterview != null) try { openedInterview.sendClose(1000, "startup failed"); } catch (Exception ignored) {}
            sttLog.close();
            timelineLog.close();
            try { interviewJournal.close(); } catch (java.io.IOException ignored) {}
            if (audioSpool != null) { audioSpool.close(); try { audioSpool.delete(); } catch (java.io.IOException ignored) {} }
            throw new IllegalStateException("Не удалось подключиться к stt-service (" + sttBaseUrl
                    + ") или analytics-service (" + analyticsBaseUrl + "). Сначала запустите эти сервисы.", error);
        }
        stt = openedStt;
        interview = openedInterview;
        sttWorker = worker("vosk-system", sttQueue, stt);
        cpuSampler = new Thread(this::sampleCpuLoad, "integration-cpu-load");
        cpuSampler.setDaemon(true);
        cpuSampler.start();
        interview.sendJson(Json.map("type", "start", "sessionId", sessionId, "sequence", 0, "attemptId", 0,
                "levels", List.of(),
                "whisperEnabled", whisperEnabled, "candidate", this.candidateName, "testMode", testMode,
                "deferredFinalReport", deferredFinalReport));
        writeTimeline();
        writeSummary(false);
    }

    private static String wsUrl(String base) {
        return base.replaceAll("/+$", "").replaceFirst("^http", "ws");
    }

    private void onPeerClosed(String worker, String message) {
        if (closed || finishing) return;
        failure = message;
        logQuietly(Json.map("type", "bridge_error", "worker", worker, "error", message));
    }

    private void sendAnalytics(Map<String, ?> payload) {
        synchronized (analyticsSendLock) {
            if (!interview.isOpen()) throw new IllegalStateException("Внутренний канал аналитики закрыт.");
            Map<String, Object> event = new LinkedHashMap<>();
            payload.forEach(event::put);
            event.put("sessionId", sessionId);
            event.put("sequence", analyticsSequence++);
            if ("ready".equals(event.get("type"))) {
                currentAttemptId = ++nextAttemptId;
                event.put("attemptId", currentAttemptId);
                journal("ready", event.get("audioStartSample") instanceof Number n ? n.longValue() : capturedSamples,
                        Json.map("attemptId", currentAttemptId, "questionId", question == null ? null : question.get("questionId"),
                                "followUpId", question == null ? null : question.get("followUpId")));
                if (event.get("audioStartSample") instanceof Number start) {
                    LiveWhisperChunks attempt = liveByStart.remove(start.longValue());
                    if (attempt != null) liveByAttempt.put(currentAttemptId, attempt);
                }
            } else {
                event.putIfAbsent("attemptId", currentAttemptId);
            }
            interview.sendJson(event);
        }
    }

    private Thread worker(String name, ArrayBlockingQueue<byte[]> queue, WsPeer peer) {
        Thread thread = new Thread(() -> {
            try {
                while (true) {
                    byte[] block = queue.take();
                    if (block == END) break;
                    peer.sendAudio(block);
                }
            } catch (Throwable error) {
                failure = name + ": " + error;
                logQuietly(Json.map("type", "bridge_error", "worker", name, "error", failure));
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Передаёт VAD только подтверждённую речь кандидата. Текст берётся из единственного
     * системного Vosk, уже размеченного диаризацией, поэтому аудио не распознаётся повторно.
     */
    private void observeCandidateAudio(long firstSample, byte[] pcm) {
        for (int from = 0; from < pcm.length; from += 1600) {
            int size = Math.min(1600, pcm.length - from);
            byte[] frame = java.util.Arrays.copyOfRange(pcm, from, from + size);
            long first = firstSample + from / 2L;
            long end = first + frame.length / 2L;
            String text = candidateText();
            candidateDetector.onFrame(frame, frame.length, wordCount(text), endsWithConnector(text));
            sendCandidateFrame("snapshot", text, frame.length / 2, rms(frame), first, end);
        }
    }

    /** Отправляет аналитике снимок текста и состояние VAD без нового вызова Vosk. */
    private void sendCandidateFrame(String type, String text, int samples, double rms, long first, long end) {
        sendAnalytics(Json.map("type", "frame", "audioStartSample", first, "audioEndSample", end,
                "samples", samples, "rms", rms, "asrMs", 0,
                "asrType", type, "text", text,
                "vadState", candidateDetector.state().name(), "audioMs", candidateDetector.audioMs(),
                "speechStartMs", candidateDetector.speechStartMs(), "speechDurationMs", candidateDetector.speechDurationMs(),
                "silenceMs", candidateDetector.silenceMs()));
    }

    private static int wordCount(String text) {
        return text == null || text.isBlank() ? 0 : text.trim().split("\\s+").length;
    }

    private static boolean endsWithConnector(String text) {
        if (text == null || text.isBlank()) return false;
        String[] words = text.toLowerCase(java.util.Locale.ROOT).trim().split("\\s+");
        return Set.of("и", "или", "но", "что", "потому", "например", "для", "при", "с", "в", "на", "к")
                .contains(words[words.length - 1]);
    }

    private static double rms(byte[] pcm) {
        if (pcm.length < 2) return 0;
        double sum = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int value = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            sum += (double) value * value;
        }
        return Math.sqrt(sum / (pcm.length / 2));
    }

    /** Передаёт кадр PCM единственному потоку Vosk и фиксирует его позицию на общей шкале.
     * @param firstSample индекс первого сэмпла кадра
     * @param block бинарный PCM16-кадр
     */
    public synchronized void submit(long firstSample, byte[] block) {
        submitSystem(firstSample, block, true, true);
    }

    /** Сохраняет тестовое аудио и при необходимости исключает его из оценки ответа.
     * @param firstSample позиция первого сэмпла
     * @param block PCM16
     * @param candidateEnabled направлять ли звук в оценку */
    public synchronized void submit(long firstSample, byte[] block, boolean candidateEnabled) {
        submitSystem(firstSample, block, true, candidateEnabled);
    }

    private void submitSystem(long firstSample, byte[] block, boolean recordMono, boolean candidateEnabled) {
        if (closed || finishing) return;
        if (firstSample != capturedSamples) throw new IllegalStateException("Разрыв общей шкалы аудиосэмплов.");
        if (!sttQueue.offer(block)) {
            sttQueueOverflowCount++;
            failure = "Очередь Vosk переполнена; STT отстаёт от захвата.";
            logQuietly(Json.map("type", "stt_overflow", "audio_start_s", firstSample / (double) RATE));
            throw new IllegalStateException(failure);
        }
        if (testMode && candidateEnabled) {
            long end = firstSample + block.length / 2L;
            int last = testCandidateEnabledRanges.size() - 1;
            if (last >= 0 && testCandidateEnabledRanges.get(last).end() == firstSample) {
                SampleRange previous = testCandidateEnabledRanges.get(last);
                testCandidateEnabledRanges.set(last, new SampleRange(previous.start(), end));
            } else testCandidateEnabledRanges.add(new SampleRange(firstSample, end));
            // В тестовом режиме диапазон ответа расширился: ранее полученные слова могут стать его частью.
            publishCandidateTextSnapshot();
        }
        if (answerArmed && activeLiveWhisper != null)
            activeLiveWhisper.append(firstSample, candidateEnabled ? block : new byte[block.length]);
        if (recordMono && audioSpool != null) audioSpool.append(firstSample, block, null);
        maxSttQueueDepth = Math.max(maxSttQueueDepth, sttQueue.size());
        systemFrames++;
        capturedSamples += block.length / 2;
        lastCapturedNanos = System.nanoTime();
        if (testMode) {
            // В тестовом сценарии весь сигнал считается речью кандидата: референс и роли не нужны.
            observeCandidateAudio(firstSample, candidateEnabled ? block : new byte[block.length]);
            gatedSamples = firstSample + block.length / 2L;
            return;
        }
        audio.addLast(new AudioBlock(firstSample, block));
    }

    /** Принимает синхронные каналы: системный — в live STT, оба — в запись для Whisper.
     * @param firstSample позиция первого сэмпла обоих источников
     * @param system системный звук в PCM16
     * @param microphone микрофон в PCM16
     */
    public synchronized void submitChannels(long firstSample, byte[] system, byte[] microphone) {
        if (closed || finishing) return;
        if (!separateChannels || system.length != microphone.length)
            throw new IllegalStateException("Неверная пара аудиоканалов.");
        submitSystem(firstSample, system, false, true);
        if (audioSpool != null) audioSpool.append(firstSample, system, microphone);
        microphoneFrames++;
        microphoneSamples += microphone.length / 2L;
    }

    /** Сопоставляет новые интервалы говорящих со словами общего потока Vosk.
     * @param segments новые сегменты диаризации
     * @param activeSegments текущие предварительные интервалы говорящих
     * @param audioSeconds длительность принятого аудио в секундах
     * @param flush завершить сопоставление, не ожидая новых интервалов
     */
    public synchronized void onDiarization(List<Map<String, Object>> segments,
                                           List<Map<String, Object>> activeSegments,
                                           double audioSeconds, boolean flush) {
        if (closed) return;
        for (Map<String, Object> row : segments) {
            long start = Math.round(((Number) row.get("start")).doubleValue() * RATE);
            long end = Math.round(((Number) row.get("end")).doubleValue() * RATE);
            String label = String.valueOf(row.get("label"));
            String key = start + ":" + end + ":" + row.get("source_speaker_id");
            if (!seenSegments.add(key)) continue;
            String speaker = String.valueOf(row.get("source_speaker_id"));
            PendingSpan pending = pendingSpans.get(speaker);
            if (pending != null && Math.abs(start - pending.start) <= RATE / 2L)
                pendingSpans.remove(speaker);
            VoiceSpan priorPreview = provisionalSpans.get(speaker);
            if ("Не определено".equals(label) && priorPreview != null) {
                // Показываем предварительную роль по двум окнам CAM++ до накопления
                // 2,5 с речи, необходимых для окончательного подтверждения.
                currentRole = priorPreview.label() + " · предварительно";
            } else {
                spans.removeIf(span -> span.preliminary() && span.speaker().equals(speaker));
                provisionalSpans.remove(speaker);
                spans.add(new VoiceSpan(start, end, label, speaker, false));
                currentRole = label;
                if (!"Не определено".equals(label))
                    logQuietly(Json.map("type", "role_confirmed", "speaker_id", speaker, "role", label,
                            "start_s", Json.round(start / (double) RATE, 3),
                            "end_s", Json.round(end / (double) RATE, 3), "source", "nemotron_campp"));
                if (!"Кандидат".equals(label) && priorPreview != null) {
                    preview = null;
                    lastPeekCandidateEvidence = false;
                    lastPeekText = "";
                    logQuietly(Json.map("type", "role_preview_retracted", "speaker_id", speaker,
                            "confirmed_role", label, "start_s", start / (double) RATE));
                }
            }
        if ("Кандидат".equals(label)) {
                lastCandidateEnd = Math.max(lastCandidateEnd, end);
                if (start < gatedSamples) {
                    lateCandidateSegments++;
                    logQuietly(Json.map("type", "late_candidate_role", "start_s", start / (double) RATE,
                            "end_s", end / (double) RATE,
                            "already_gated_to_s", gatedSamples / (double) RATE));
                }
            }
            double latency = Math.max(0, (System.nanoTime() - estimateCapturedAt(end)) / 1e6);
            diarizationLatencies.add(latency);
            logQuietly(Json.map("type", "diarization_segment", "start_s", start / (double) RATE,
                    "end_s", end / (double) RATE, "role", label,
                    "end_to_diarization_ms", Json.round(latency, 1)));
        }
        reviseRoles();
        if (!flush) {
            long now = Math.round(audioSeconds * RATE);
            for (Map<String, Object> active : activeSegments) {
                Object start = active.get("start");
                if (!(start instanceof Number number)) continue;
                String speaker = String.valueOf(active.get("source_speaker_id"));
                long sample = Math.max(0, Math.round(number.doubleValue() * RATE));
                long activeEnd = Math.round(((Number) active.getOrDefault("observed_end", audioSeconds)).doubleValue() * RATE);
                long previousPreviewEnd = candidatePreviewEnds.getOrDefault(speaker, 0L);
                if (activeEnd > previousPreviewEnd) {
                    candidatePreviewEnds.put(speaker, activeEnd);
                    String previewLabel = String.valueOf(active.getOrDefault("label", "Не определено"));
                    if (isSpeakerRole(previewLabel) && previewRoleMargin(active, previewLabel)) {
                        String priorLabel = activePreviewLabels.get(speaker);
                        int votes = previewLabel.equals(priorLabel) ? activePreviewVotes.getOrDefault(speaker, 0) + 1 : 1;
                        activePreviewLabels.put(speaker, previewLabel);
                        activePreviewVotes.put(speaker, votes);
                        currentRole = votes >= 2 ? previewLabel + " · предварительно" : "Проверяем: " + previewLabel;
                        if (votes >= 2) {
                            VoiceSpan prior = provisionalSpans.get(speaker);
                            long roleStart = prior != null && prior.label().equals(previewLabel) ? prior.start() : sample;
                            VoiceSpan provisional = new VoiceSpan(roleStart, activeEnd, previewLabel, speaker, true);
                            provisionalSpans.put(speaker, provisional);
                            spans.removeIf(span -> span.preliminary() && span.speaker().equals(speaker));
                            spans.add(provisional);
                            if (votes == 2 && activeEnd > lastRoleSample) {
                                roleLatencies.add(Math.max(0, (System.nanoTime() - estimateCapturedAt(activeEnd)) / 1e6));
                                lastRoleSample = activeEnd;
                            }
                            if (votes == 2)
                                logQuietly(Json.map("type", "role_preliminary", "speaker_id", speaker,
                                        "role", previewLabel, "start_s", roleStart / (double) RATE,
                                        "observed_end_s", activeEnd / (double) RATE, "consecutive_matches", votes));
                            reviseRoles();
                        }
                    } else {
                        activePreviewVotes.put(speaker, 0);
                        activePreviewLabels.remove(speaker);
                        if (provisionalSpans.remove(speaker) != null) {
                            spans.removeIf(span -> span.preliminary() && span.speaker().equals(speaker));
                            preview = null;
                            lastPeekCandidateEvidence = false;
                            lastPeekText = "";
                            currentRole = "Не определено · проверяем голос";
                            logQuietly(Json.map("type", "role_preview_retracted", "speaker_id", speaker,
                                    "confirmed_role", active.get("label")));
                            reviseRoles();
                        }
                    }
                }
                PendingSpan pending = pendingSpans.get(speaker);
                if (pending == null)
                    pendingSpans.put(speaker, new PendingSpan(sample, now));
                else {
                    pending.start = Math.min(pending.start, sample);
                    pending.lastSeen = now;
                }
            }
            var iter = pendingSpans.entrySet().iterator();
            while (iter.hasNext()) {
                var entry = iter.next();
                if (now - entry.getValue().lastSeen <= 5L * RATE) continue;
                logQuietly(Json.map("type", "unresolved_role_expired",
                        "speaker_id", entry.getKey(),
                        "start_s", entry.getValue().start / (double) RATE));
                iter.remove();
            }
        } else pendingSpans.clear();
        if (separateChannels && answerArmed && !flush && !audio.isEmpty()
                && capturedSamples - Math.max(audio.peekFirst().first(), answerStartSample) > MAX_UNCONFIRMED_SAMPLES) {
            candidateWarning = "Роль говорящего слишком долго не подтверждается; часть ответа может отсутствовать.";
            failure = "Диаризация не успевает подтвердить роль для накопленного аудио.";
            throw new IllegalStateException(failure);
        }
        long target = flush ? capturedSamples : Math.min(capturedSamples,
                Math.max(0, Math.round(audioSeconds * RATE) - HOLD_SAMPLES));
        if (!flush) {
            for (PendingSpan pending : pendingSpans.values())
                target = Math.min(target, pending.start);
        }
        while (!audio.isEmpty() && audio.peekFirst().end() <= target) {
            AudioBlock block = audio.peekFirst();
            var lookahead = audio.iterator();
            lookahead.next();
            AudioBlock next = lookahead.hasNext() ? lookahead.next() : null;
            if (separateChannels && answerArmed && block.end() > answerStartSample
                    && awaitsRoleWithLookahead(block.first(), block.mixed(),
                    !flush && next != null && next.end() > answerStartSample ? next.mixed() : null, spans)) {
                // Не отправляем громкий неподтверждённый звук как тишину: начало речи может подтвердиться позже.
                if (!flush && capturedSamples - block.first() < MAX_UNCONFIRMED_SAMPLES) break;
                candidateWarning = "Часть системного звука не удалось подтвердить как речь кандидата; проверьте расшифровку.";
                if (!flush) {
                    failure = "Диаризация не подтвердила роль для накопленного аудио кандидата.";
                    throw new IllegalStateException(failure);
                }
            }
            audio.removeFirst();
            byte[] gated = gateCandidateAudio(block.first(), block.mixed(), spans, answerStartSample, separateChannels);
            boolean candidateSpeech = !Pcm.isSilent(gated);
            observeCandidateAudio(block.first(), gated);
            if (candidateSpeech && block.end() > lastCandidateDispatchSample) {
                candidateDispatchLatencies.add(Math.max(0,
                        (System.nanoTime() - estimateCapturedAt(block.end())) / 1e6));
                lastCandidateDispatchSample = block.end();
            }
            if (candidateSpeech) roleWaitLatencies.add(Math.max(0,
                    (System.nanoTime() - estimateCapturedAt(block.end())) / 1e6));
            gatedSamples = block.end();
        }
        writeSummary(false);
    }

    static boolean awaitsVoiceRole(long first, byte[] pcm, List<VoiceSpan> spans) {
        if (rms(pcm) < VOICE_RMS_FLOOR) return false;
        long end = first + pcm.length / 2L;
        for (VoiceSpan span : spans) {
            if (!span.preliminary() && isSpeakerRole(span.label())
                    && Math.min(end, span.end()) > Math.max(first, span.start())) return false;
        }
        return true;
    }

    static boolean awaitsRoleWithLookahead(long first, byte[] pcm, byte[] next, List<VoiceSpan> spans) {
        return awaitsVoiceRole(first, pcm, spans)
                || (next != null && awaitsVoiceRole(first + pcm.length / 2L, next, spans));
    }

    static byte[] gateCandidateAudio(long first, byte[] pcm, List<VoiceSpan> spans,
                                     long answerStart, boolean separateChannels) {
        long end = first + pcm.length / 2L;
        byte[] gated = new byte[pcm.length];
        for (VoiceSpan span : spans) {
            // Предварительная роль годится для подсказки, но не для изменения состояния аналитики.
            if (!"Кандидат".equals(span.label()) || span.preliminary()) continue;
            long left = Math.max(first, span.start()), right = Math.min(end, span.end());
            if (right > left) System.arraycopy(pcm, (int) ((left - first) * 2), gated,
                    (int) ((left - first) * 2), (int) ((right - left) * 2));
        }
        // В смешанном режиме речь другого человека загрязняет общий сигнал.
        if (!separateChannels) for (VoiceSpan span : spans) {
            if ("Кандидат".equals(span.label())) continue;
            long left = Math.max(first, span.start()), right = Math.min(end, span.end());
            if (right > left) java.util.Arrays.fill(gated, (int) ((left - first) * 2),
                    (int) ((right - first) * 2), (byte) 0);
        }
        long beforeAnswer = Math.min(end, answerStart);
        if (beforeAnswer > first) java.util.Arrays.fill(gated, 0, (int) ((beforeAnswer - first) * 2), (byte) 0);
        return gated;
    }

    private static boolean isSpeakerRole(String label) {
        return "Кандидат".equals(label) || label.startsWith("Интервьюер ");
    }

    private static boolean previewRoleMargin(Map<String, Object> active, String label) {
        if (!(active.get("candidate_similarity") instanceof Number candidate))
            return isSpeakerRole(label) && String.valueOf(active.get("basis")).contains("speaker ID");
        double candidateScore = candidate.doubleValue();
        double interviewer = active.get("interviewer_similarity") instanceof Number n
                ? n.doubleValue() : Double.NEGATIVE_INFINITY;
        if ("Кандидат".equals(label))
            return candidateScore >= .60 && candidateScore - interviewer >= .05;
        return interviewer >= .60 && interviewer - candidateScore >= .05;
    }

    private String currentPreviewRole() {
        return lastPeekCandidateEvidence ? "Кандидат" : "Не определено";
    }

    /** Отмечает готовность вопроса и начинает собирать следующий ответ кандидата. */
    public synchronized void questionAsked() {
        if (question == null) throw new IllegalStateException("Сервис аналитики ещё не выдал вопрос.");
        if (questionPending || decisionRetracted)
            throw new IllegalStateException("Ожидается подтверждение следующего вопроса.");
        readySample = capturedSamples;
        answerStartSample = readySample;
        answerArmed = true;
        if (liveWhisperExecutor != null) {
            liveByStart.clear();
            liveByAttempt.clear();
            activeLiveWhisper = new LiveWhisperChunks(whisperClient, liveWhisperExecutor, readySample);
            liveByStart.put(readySample, activeLiveWhisper);
        }
        preview = null;
        lastPeekCandidateEvidence = false;
        lastPeekText = "";
        lastCandidateSnapshotText = "";
        lastPeekSample = readySample;
        candidateDetector.reset();
        sendAnalytics(Json.map("type", "ready", "audioStartSample", readySample));
        logQuietly(Json.map("type", "question_asked", "question", question,
                "audio_s", capturedSamples / (double) RATE));
    }

    /** Закрывает текущую попытку без добавления аудиоданных ответа. */
    public synchronized void questionSkipped() {
        if (questionPending || decisionRetracted)
            throw new IllegalStateException("Ожидается подтверждение следующего вопроса.");
        journal("skip", capturedSamples, Json.map("attemptId", currentAttemptId,
                "questionId", question == null ? null : question.get("questionId"),
                "followUpId", question == null ? null : question.get("followUpId")));
        answerArmed = false;
        activeLiveWhisper = null;
        sendAnalytics(Json.map("type", "skip", "audioStartSample", capturedSamples, "audioEndSample", capturedSamples));
        logQuietly(Json.map("type", "question_skipped", "audio_s", capturedSamples / (double) RATE));
    }

    /** Передаёт событие браузерного синтеза речи аналитике интервью.
     * @param event событие TTS: {@code start}, {@code finish}, {@code cancel} или {@code error}
     * @param questionText произнесённый текст вопроса
     * @param voice выбранный голос синтеза
     * @param voiceLang язык синтеза
     */
    public synchronized void ttsEvent(String event, String questionText, String voice, String voiceLang) {
        if (closed) return;
        if (!List.of("start", "finish", "cancel", "error").contains(event)) return;
        if ("finish".equals(event)) {
            sendAnalytics(Json.map("type", "tts_end", "afterMs",
                    Math.max(0, capturedSamples - readySample) * 1000L / RATE,
                    "audioEndSample", capturedSamples));
        }
        logQuietly(Json.map("type", "tts_" + event, "question", questionText,
                "voice", voice, "voice_lang", voiceLang,
                "audio_sample", capturedSamples, "audio_s", Json.round(capturedSamples / (double) RATE, 3),
                "received_wall_s", Json.round((System.nanoTime() - startedNanos) / 1e9, 3)));
    }

    /** Фиксирует показ подсказки и связывает его с временной позицией аудио.
     * @param id идентификатор события предварительной подсказки
     */
    public synchronized void suggestionSeen(String id) {
        if (id == null || closed) return;
        if (id.equals(previewEventId)) {
            if (!seenUiEvents.add(id)) return;
            long sample = previewEventSamples.getOrDefault(id, lastPeekSample);
            long visibleAt = System.nanoTime();
            previewEventVisibleNanos.put(id, visibleAt);
            double previewLatency = Math.max(0, (visibleAt - estimateCapturedAt(sample)) / 1e6);
            previewLatencies.add(previewLatency);
            logQuietly(Json.map("type", "preliminary_hint_visible", "event_id", id,
                    "audio_end_s", Json.round(sample / (double) RATE, 3),
                    "end_to_ui_ack_ms", Json.round(previewLatency, 1)));
            for (int answerId : decisionAudioEnds.keySet()) recordPreliminaryHintLatency(answerId);
            writeSummary(false);
            return;
        }
        if (!id.equals(decisionEventId)) return;
        if (decisionAudioEnd <= readySample) return;
        if (!seenUiEvents.add(id)) return;
        double latency = Math.max(0, (System.nanoTime() - estimateCapturedAt(decisionAudioEnd)) / 1e6);
        uiLatencies.add(latency);
        logQuietly(Json.map("type", "suggestion_visible", "event_id", id,
                "answer_end_s", decisionAudioEnd / (double) RATE,
                "end_to_ui_ack_ms", Json.round(latency, 1),
                "decision_to_ui_ack_ms", Json.round((System.nanoTime() - decisionAtNanos) / 1e6, 1)));
        writeSummary(false);
    }

    private long estimateCapturedAt(long sample) {
        return lastCapturedNanos - Math.max(0, capturedSamples - sample) * 1_000_000_000L / RATE;
    }

    private synchronized void onStt(String source, JsonNode data) {
        if (closed) return;
        String type = data.path("type").asText();
        if (!"partial".equals(type) && !"final".equals(type)) return;
        String text = data.path("text").asText("");
        if ("final".equals(type) && !text.isBlank()) {
            if (mixedTranscript.length() > 0) mixedTranscript.append(' ');
            mixedTranscript.append(text);
        }
        mixedPartial = "final".equals(type) ? "" : text;
        List<Map<String, Object>> words = new ArrayList<>();
        int utterance = utteranceNumbers.getOrDefault(source, 0);
        for (JsonNode word : data.path("words")) {
            long start = Math.round(word.path("start").asDouble() * RATE);
            long end = Math.round(word.path("end").asDouble() * RATE);
            Map<String, Object> row = Json.map("word", word.path("word").asText(""),
                    "word_id", source + ":" + utterance + ":" + words.size(),
                    "start", Json.round(start / (double) RATE, 3),
                    "end", Json.round(end / (double) RATE, 3),
                    "role", role(start, end),
                    "role_status", roleStatus(start, end),
                    "source", source, "model", "vosk",
                    "status", "final".equals(type) ? "final" : "preliminary",
                    "confidence", word.hasNonNull("confidence") ? word.path("confidence").asDouble() : null);
            words.add(row);
            if ("final".equals(type)) finalWords.add(row);
            if ("final".equals(type)) voskLatencies.add(Math.max(0,
                    (System.nanoTime() - estimateCapturedAt(end)) / 1e6));
        }
        if (!text.isBlank()) {
            long sample = words.isEmpty() ? capturedSamples : Math.round(
                    ((Number) words.get(words.size() - 1).get("end")).doubleValue() * RATE);
            if (sample > lastPartialSample) {
                partialLatencies.add(Math.max(0, (System.nanoTime() - estimateCapturedAt(sample)) / 1e6));
                lastPartialSample = sample;
            }
        }
        if ("final".equals(type)) {
            markWhisperReplacements();
            preliminaryWords.remove(source);
            utteranceNumbers.put(source, utterance + 1);
        } else preliminaryWords.put(source, words);
        publishCandidateTextSnapshot();
        requestCandidatePeek();
        sttEvents++;
        Map<String, Object> event = Json.map("type", "stt_" + type, "source", source,
                "text", text, "words", words, "received_wall_s",
                Json.round((System.nanoTime() - startedNanos) / 1e9, 3));
        try {
            write(sttLog, event);
            emit(event);
            writeTimeline();
        } catch (Exception error) { failure = error.toString(); }
    }

    private String role(long start, long end) {
        if (testMode) return "Кандидат";
        long best = 0, second = 0;
        String label = "Не определено";
        for (VoiceSpan span : spans) {
            long overlap = Math.max(0, Math.min(end, span.end()) - Math.max(start, span.start()));
            if (overlap > best) { second = best; best = overlap; label = span.label(); }
            else second = Math.max(second, overlap);
        }
        if (end <= start || best * 2 < end - start || second * 4 >= best * 3) return "Не определено";
        return label;
    }

    private String roleStatus(long start, long end) {
        if (testMode) return "confirmed";
        long best = 0;
        boolean preliminary = false;
        for (VoiceSpan span : spans) {
            long overlap = Math.max(0, Math.min(end, span.end()) - Math.max(start, span.start()));
            if (overlap > best) { best = overlap; preliminary = span.preliminary(); }
        }
        return best == 0 ? "unknown" : preliminary ? "preliminary" : "confirmed";
    }

    private void reviseRoles() {
        boolean changed = false;
        for (Map<String, Object> word : currentWords()) {
            if ("microphone".equals(word.get("source")) || "whisper".equals(word.get("model"))) continue;
            long start = Math.round(((Number) word.get("start")).doubleValue() * RATE);
            long end = Math.round(((Number) word.get("end")).doubleValue() * RATE);
            String updated = role(start, end);
            String status = roleStatus(start, end);
            if (!updated.equals(word.get("role")) || !status.equals(word.get("role_status"))) {
                word.put("role", updated);
                word.put("role_status", status);
                changed = true;
                logQuietly(Json.map("type", "word_role_revision", "word", word));
            }
        }
        if (changed) {
            markWhisperReplacements();
            writeTimeline();
            publishCandidateTextSnapshot();
            requestCandidatePeek();
        }
    }

    /** Обновляет текст ответа после нового результата Vosk или уточнения роли. */
    private void publishCandidateTextSnapshot() {
        if (!answerArmed) return;
        String text = candidateText();
        if (text.equals(lastCandidateSnapshotText)) return;
        lastCandidateSnapshotText = text;
        sendCandidateFrame("snapshot", text, 0, 0, capturedSamples, capturedSamples);
    }

    /** Собирает текст текущего ответа кандидата из общей временной шкалы Vosk. */
    private void requestCandidatePeek() {
        List<Map<String, Object>> answerWords = candidateWords();
        String candidateText = wordsText(answerWords);
        lastPeekCandidateEvidence = !candidateText.isBlank();
        if (!lastPeekCandidateEvidence) {
            if (preview != null) {
                preview = null;
                logQuietly(Json.map("type", "preliminary_hint_retracted", "reason", "candidate_role_not_currently_supported"));
            }
            lastPeekText = "";
            return;
        }
        lastPeekSample = answerWords.stream()
                .mapToLong(word -> Math.round(((Number) word.get("end")).doubleValue() * RATE))
                .max().orElse(readySample);
        long now = System.nanoTime();
        if (candidateText.equals(lastPeekText) || now - lastPeekAtNanos < 350_000_000L) return;
        lastPeekText = candidateText;
        lastPeekAtNanos = now;
        String requestId = "peek-" + (++nextPeekRequestId);
        peekRequestSamples.put(requestId, lastPeekSample);
        peekRequestNanos.put(requestId, now);
        try {
            sendAnalytics(Json.map("type", "peek", "requestId", requestId,
                    "text", candidateText, "audioStartSample", readySample, "audioEndSample", lastPeekSample));
            logQuietly(Json.map("type", "peek_requested", "request_id", requestId,
                    "text", candidateText, "audio_sample", lastPeekSample,
                    "audio_s", Json.round(lastPeekSample / (double) RATE, 3)));
        } catch (Exception error) {
            failure = "Не удалось передать предварительный текст в analysis peek: " + error;
        }
    }

    /** Возвращает слова кандидата из единственной live-транскрипции с уже назначенной ролью. */
    private List<Map<String, Object>> candidateWords() {
        return currentWords().stream()
                .filter(word -> "vosk".equals(word.get("model")) && (testMode || "Кандидат".equals(word.get("role"))))
                .filter(word -> !separateChannels || "system".equals(word.get("source")))
                .filter(word -> !testMode || testWordEnabled(word))
                .filter(word -> ((Number) word.get("start")).doubleValue() * RATE >= readySample)
                .sorted((a, b) -> Double.compare(((Number) a.get("start")).doubleValue(),
                        ((Number) b.get("start")).doubleValue()))
                .toList();
    }

    private String candidateText() { return wordsText(candidateWords()); }

    private static String wordsText(List<Map<String, Object>> words) {
        return words.stream().map(word -> String.valueOf(word.get("word")))
                .filter(word -> !word.isBlank()).collect(java.util.stream.Collectors.joining(" "));
    }

    private boolean testWordEnabled(Map<String, Object> word) {
        long start = Math.round(((Number) word.get("start")).doubleValue() * RATE);
        long end = Math.round(((Number) word.get("end")).doubleValue() * RATE);
        long midpoint = start + (end - start) / 2;
        return testCandidateEnabledRanges.stream().anyMatch(range -> midpoint >= range.start() && midpoint < range.end());
    }

    private synchronized void onInterview(JsonNode data) {
        if (closed) return;
        if (!sessionId.equals(data.path("sessionId").asText())) {
            failure = "Analytics прислал событие с чужим sessionId.";
            return;
        }
        long incomingSequence = data.path("sequence").asLong(-1);
        if (incomingSequence != expectedAnalyticsSequence++) {
            failure = "Нарушен порядок событий analytics-service.";
            logQuietly(Json.map("type", "bridge_error", "worker", "analytics", "error", failure,
                    "sequence", incomingSequence));
            return;
        }
        String type = data.path("type").asText();
        @SuppressWarnings("unchecked")
        Map<String, Object> event = Json.MAPPER.convertValue(data, Map.class);
        if ("scenario_question".equals(type) || "scenario_followup".equals(type) || "scenario_rubric".equals(type)) {
            event.remove("type");
            event.remove("sessionId");
            event.remove("sequence");
            event.remove("audioSample");
            journal(type, 0, event);
            return;
        }
        if ("question".equals(type)) {
            if (data.path("graceMs").asLong() > 0) {
                pendingQuestion = event;
                pendingQuestionAnswerId = lastWhisperAnswerId;
                questionPending = true;
            } else {
                question = event;
                pendingQuestion = null;
                questionPending = false;
                decisionRetracted = false;
            }
            journal("question_issued", Math.max(0, data.path("audioSample").asLong(capturedSamples)),
                    Json.map("questionId", data.path("questionId").asText(), "followUpId", data.has("followUpId") ? data.path("followUpId").asText() : null,
                            "attemptId", data.path("attemptId").asLong()));
            questionAvailableNanos = System.nanoTime()
                    + Math.max(0, data.path("graceMs").asLong()) * 1_000_000L;
        }
        if ("grace_complete".equals(type) && pendingQuestion != null
                && data.path("answerId").asInt(-1) == pendingQuestionAnswerId) {
            question = pendingQuestion;
            pendingQuestion = null;
            questionPending = false;
            decisionRetracted = false;
            questionAvailableNanos = System.nanoTime();
        }
        if ("preview".equals(type)) {
            if (!"Кандидат".equals(currentPreviewRole())) return;
            String requestId = data.path("requestId").asText("");
            long requestOrdinal = parsePeekRequestOrdinal(requestId);
            if (requestOrdinal > 0 && requestOrdinal < lastAppliedPeekRequestId) return;
            long previewSample = peekRequestSamples.getOrDefault(requestId, lastPeekSample);
            if (isPreviewAtOrBeforeDecision(previewSample, decisionAudioEnd)) {
                logQuietly(Json.map("type", "preliminary_hint_ignored",
                        "reason", "answer_decision_already_received",
                        "audio_s", Json.round(previewSample / (double) RATE, 3),
                        "decision_audio_end_s", Json.round(decisionAudioEnd / (double) RATE, 3)));
                return;
            }
            lastAppliedPeekRequestId = Math.max(lastAppliedPeekRequestId, requestOrdinal);
            preview = event;
            long requestedAt = peekRequestNanos.getOrDefault(requestId, System.nanoTime());
            peekComputeLatencies.add(data.path("computeMs").isNumber()
                    ? data.path("computeMs").asDouble() : Math.max(0, (System.nanoTime() - requestedAt) / 1e6));
            event.put("event_id", "preview-" + (++nextEventId));
            previewEventId = String.valueOf(event.get("event_id"));
            previewEventSamples.put(previewEventId, previewSample);
            event.put("peek_compute_ms", Json.round(peekComputeLatencies.get(peekComputeLatencies.size() - 1), 1));
            logQuietly(Json.map("type", "preliminary_hint_shown", "preview", event,
                    "audio_s", Json.round(previewSample / (double) RATE, 3)));
        }
        if ("preview_retract".equals(type)) {
            preview = null;
            lastPeekText = "";
            logQuietly(Json.map("type", "preliminary_hint_retracted", "reason", data.path("reason").asText("updated")));
        }
        if ("retract".equals(type)) {
            pendingQuestion = null;
            questionPending = false;
            journal("retract", Math.max(0, data.path("audioSample").asLong(capturedSamples)),
                    Json.map("attemptId", data.path("attemptId").asLong(), "answerId", lastWhisperAnswerId));
            decisionRetracted = true;
            if (lastWhisperAnswerId > 0) {
                cancelledWhisperAnswers.add(lastWhisperAnswerId);
                pendingWhisperWords.remove(lastWhisperAnswerId);
                if (refined != null && refined.get("answerId") instanceof Number id && id.intValue() == lastWhisperAnswerId)
                    refined = null;
                CompletableFuture<Void> cancelledFuture = whisperCompletion.get(lastWhisperAnswerId);
                if (cancelledFuture != null) cancelledFuture.complete(null);
                int cancelled = lastWhisperAnswerId;
                acceptedWhisperRanges.remove(cancelled);
                finalWords.removeIf(word -> "whisper".equals(word.get("model")) && Integer.valueOf(cancelled).equals(word.get("answer_id")));
                markWhisperReplacements();
                writeTimeline();
            }
            decision = null;
            if (preview != null) {
                preview = null;
                lastPeekText = "";
                logQuietly(Json.map("type", "preliminary_hint_retracted", "reason", "speech_resumed"));
            }
            if (data.has("question")) {
                @SuppressWarnings("unchecked") Map<String, Object> restored =
                        Json.MAPPER.convertValue(data.path("question"), Map.class);
                question = restored;
            }
            questionAvailableNanos = System.nanoTime();
        }
        if ("decision".equals(type)) {
            // До следующего question нельзя показывать восстановленный после отмены вопрос как новый.
            questionPending = true;
            int answerId = data.path("answerId").asInt();
            answerAttempts.put(answerId, data.path("attemptId").asLong(currentAttemptId));
            lastWhisperAnswerId = answerId;
            answerBases.put(answerId, readySample);
            voskAnswerText.put(answerId, data.path("recognized").asText(""));
            decisionEventId = Long.toString(++nextEventId);
            decisionAudioEnd = data.path("audioSample").asLong(-1);
            if (decisionAudioEnd < readySample) decisionAudioEnd = lastCandidateEnd > readySample ? lastCandidateEnd : 0;
            if (lastPeekSample > readySample) decisionAudioEnd = Math.max(decisionAudioEnd, lastPeekSample);
            if (decisionAudioEnd > 0) decisionAudioEnds.put(answerId, decisionAudioEnd);
            journal("decision", Math.max(0, decisionAudioEnd),
                    Json.map("attemptId", data.path("attemptId").asLong(), "answerId", answerId,
                            "questionId", question == null ? null : question.get("questionId"),
                            "followUpId", question == null ? null : question.get("followUpId")));
            decisionAtNanos = System.nanoTime();
            event.put("event_id", decisionEventId);
            if (decisionAudioEnd > 0) {
                event.put("answer_end_s", Json.round(decisionAudioEnd / (double) RATE, 3));
                event.put("end_to_decision_ms", Json.round(
                        (decisionAtNanos - estimateCapturedAt(decisionAudioEnd)) / 1e6, 1));
                decisionLatencies.add(((Number) event.get("end_to_decision_ms")).doubleValue());
            } else {
                event.put("answer_end_s", null);
                event.put("end_to_decision_ms", null);
            }
            decisionTimes.put(answerId, decisionAtNanos);
            recordPreliminaryHintLatency(answerId);
            if (preview != null) {
                preview = null;
                previewEventId = null;
                logQuietly(Json.map("type", "preliminary_hint_retracted", "reason", "final_decision_received"));
            }
            decision = event;
        }
        if ("refined".equals(type)) {
            int answerId = data.path("answerId").asInt();
            if (cancelledWhisperAnswers.contains(answerId)) {
                pendingWhisperWords.remove(answerId);
                CompletableFuture<Void> cancelled = whisperCompletion.get(answerId);
                if (cancelled != null) cancelled.complete(null);
                logQuietly(Json.map("type", "whisper_cancelled_result_ignored", "answer_id", answerId));
                return;
            }
            refined = event;
            whisperState = "получен";
            Long decisionTime = decisionTimes.get(data.path("answerId").asInt());
            if (decisionTime != null) whisperLatencies.add((System.nanoTime() - decisionTime) / 1e6);
            JsonNode words = pendingWhisperWords.remove(answerId);
            if (words != null && !cancelledWhisperAnswers.contains(answerId) && !postprocessStarted) {
                long base = answerBases.getOrDefault(answerId, 0L);
                long answerEnd = decisionAudioEnds.getOrDefault(answerId, base);
                List<Map<String, Object>> refinedWords = new ArrayList<>();
                for (JsonNode word : words) {
                    long start = base + Math.round(word.path("start").asDouble() * RATE);
                    long end = base + Math.round(word.path("end").asDouble() * RATE);
                    if (word.path("word").asText("").isBlank()) continue;
                    refinedWords.add(Json.map("word", word.path("word").asText(""),
                            "word_id", "whisper:" + answerId + ":" + refinedWords.size(),
                            "start", Json.round(start / (double) RATE, 3),
                            "end", Json.round(end / (double) RATE, 3),
                            "role", "Кандидат", "role_status", "confirmed", "source", "candidate_gated",
                            "model", "whisper", "status", "final", "answer_id", answerId,
                            "confidence", word.hasNonNull("confidence") ? word.path("confidence").asDouble() : null));
                }
                if (!refinedWords.isEmpty()) {
                    acceptedWhisperRanges.put(answerId, whisperCoverage(refinedWords));
                    finalWords.addAll(refinedWords);
                    markWhisperReplacements();
                    writeTimeline();
                }
            }
            CompletableFuture<Void> completion = whisperCompletion.get(answerId);
            if (completion != null) completion.complete(null);
        }
        if ("service_status".equals(type))
            whisperState = "configured".equals(data.path("whisper").asText()) ? "готов" : "выключен";
        if ("whisper_error".equals(type))
            whisperState = "ошибка: " + data.path("message").asText("неизвестная ошибка");
        if ("finalizing".equals(type)) whisperState = "обработка";
        if ("error".equals(type)) failure = data.path("message").asText();
        event.put("source_service", "analytics");
        event.put("received_wall_s", Json.round((System.nanoTime() - startedNanos) / 1e9, 3));
        emit(event);
        if ("decision".equals(type) && !deferredFinalReport) startWhisperRefinement(data.path("answerId").asInt());
        if ("finalization_ready".equals(type)) finalizationReady.countDown();
        if ("report".equals(type)) {
            reportTranscriptSource = data.path("transcriptSource").asText(null);
            reportScoringIncomplete = data.path("scoringIncomplete").asBoolean(false);
            reportTranscriptIncomplete = data.path("transcriptIncomplete").asBoolean(false);
            reportReferencesMissing = data.path("referencesMissing").asBoolean(false);
            Object reportObject = event.get("report");
            if (reportObject instanceof Map<?, ?> values) {
                Map<String, Object> safeReport = new LinkedHashMap<>();
                values.forEach((key, value) -> safeReport.put(String.valueOf(key), value));
                finalReport = safeReport;
            }
            Object excelObject = event.get("excel");
            if (excelObject instanceof Map<?, ?> values) {
                Map<String, Object> safeExcel = new LinkedHashMap<>();
                if (values.get("file") != null) safeExcel.put("file", values.get("file"));
                if (values.get("url") != null) safeExcel.put("url", values.get("url"));
                excelReport = safeExcel;
            }
            Object excelErrorValue = event.get("excelError");
            excelError = excelErrorValue == null ? null : String.valueOf(excelErrorValue);
            writeSummary(true);
            reportReceived.countDown();
        }
    }

    private void startWhisperRefinement(int answerId) {
        if (!whisperEnabled || answerId <= 0) return;
        long attemptId = answerAttempts.getOrDefault(answerId, currentAttemptId);
        LiveWhisperChunks attempt = liveByAttempt.get(attemptId);
        List<CompletableFuture<LiveWhisperChunks.Chunk>> parts = attempt == null ? List.of() : attempt.snapshot();
        if (parts.isEmpty()) {
            sendAnalytics(Json.map("type", "refine_done", "answerId", answerId, "attemptId", attemptId,
                    "error", "Для уточнения нет аудио ответа."));
            return;
        }
        long base = answerBases.getOrDefault(answerId, answerStartSample);
        long end = decisionAudioEnds.getOrDefault(answerId, capturedSamples);
        CompletableFuture<Void> completion = new CompletableFuture<>();
        whisperCompletion.put(answerId, completion);
        Thread thread = new Thread(() -> {
            boolean sent = false;
            try {
                List<Map<String, Object>> words = new ArrayList<>();
                for (CompletableFuture<LiveWhisperChunks.Chunk> part : parts) {
                    LiveWhisperChunks.Chunk chunk = part.get(120, TimeUnit.SECONDS);
                    if (cancelledWhisperAnswers.contains(answerId)) return;
                    for (JsonNode word : chunk.words()) {
                        if (!word.path("start").isNumber() || !word.path("end").isNumber()) continue;
                        double start = chunk.offset() / (double) RATE + word.path("start").asDouble();
                        double finish = chunk.offset() / (double) RATE + word.path("end").asDouble();
                        long absoluteStart = base + Math.round(start * RATE), absoluteEnd = base + Math.round(finish * RATE);
                        String text = word.path("word").asText("").trim();
                        if (text.isBlank() || absoluteEnd < absoluteStart || absoluteStart >= end
                                || (!testMode && !confirmedCandidateWord(absoluteStart, absoluteEnd))) continue;
                        words.add(Json.map("word", text, "start", start, "end", finish,
                                "confidence", word.hasNonNull("confidence") ? word.path("confidence").asDouble() : null));
                    }
                }
                String text = words.stream().map(word -> String.valueOf(word.get("word")))
                        .collect(java.util.stream.Collectors.joining(" ")).trim();
                if (text.isBlank()) {
                    sendAnalytics(Json.map("type", "refine_done", "answerId", answerId, "attemptId", attemptId,
                            "error", "Whisper не вернул подтверждённую речь кандидата."));
                    return;
                }
                if (cancelledWhisperAnswers.contains(answerId)) return;
                pendingWhisperWords.put(answerId, Json.MAPPER.valueToTree(words));
                sendAnalytics(Json.map("type", "refined", "answerId", answerId, "attemptId", attemptId, "text", text,
                        "words", words,
                        "audioStartSample", base, "audioEndSample", end));
                sent = true;
                sendAnalytics(Json.map("type", "refine_done", "answerId", answerId, "attemptId", attemptId));
            } catch (Throwable error) {
                if (!cancelledWhisperAnswers.contains(answerId)) {
                    try {
                        sendAnalytics(Json.map("type", "refine_done", "answerId", answerId, "attemptId", attemptId,
                                "error", "Ошибка Whisper: " + error.getMessage()));
                    } catch (RuntimeException sendError) {
                        failure = "Не удалось отправить результат Whisper: " + sendError;
                    }
                }
            } finally {
                if (!sent || cancelledWhisperAnswers.contains(answerId)) completion.complete(null);
            }
        }, "whisper-answer-" + answerId);
        thread.setDaemon(true);
        thread.start();
    }

    private synchronized boolean confirmedCandidateWord(long start, long end) {
        long candidate = 0, other = 0;
        for (VoiceSpan span : spans) {
            if (span.preliminary()) continue;
            long overlap = Math.max(0, Math.min(end, span.end()) - Math.max(start, span.start()));
            if ("Кандидат".equals(span.label())) candidate = Math.max(candidate, overlap);
            else other = Math.max(other, overlap);
        }
        return end > start && candidate * 2 >= end - start && other * 4 < candidate * 3;
    }

    /** Возвращает актуальное состояние браузерной сессии.
     * @return состояние вопроса, ответа, подсказок и отчёта
     */
    public synchronized Map<String, Object> status() {
        List<Map<String, Object>> words = currentWords();
        int from = Math.max(0, words.size() - 80);
        return Json.map("question", question, "decision", decision, "refined", refined, "preview", preview,
                "report", finalReport, "report_transcript_source", reportTranscriptSource, "report_scoring_incomplete", reportScoringIncomplete, "report_transcript_incomplete", reportTranscriptIncomplete, "report_references_missing", reportReferencesMissing, "excel", excelReport, "excel_error", excelError,
                "audio_seconds", capturedSamples / (double) RATE,
                "queue_depth_chunks", sttQueue.size(),
                "test_mode", testMode,
                "current_role", currentRole,
                "decision_retracted", decisionRetracted,
                "question_pending", questionPending,
                "question_wait_ms", Math.max(0, (questionAvailableNanos - System.nanoTime()) / 1_000_000L),
                "mixed_partial", mixedPartial, "mixed_transcript", mixedTranscript.toString(),
                "channel_partials", separateChannels ? Json.map("system", mixedPartial) : null,
                "channel_stats", channelStats(),
                "candidate_warning", candidateWarning,
                "timeline_words", new ArrayList<>(words.subList(from, words.size())),
                "whisper_state", whisperState, "bridge_error", failure,
                "transcript_progress", postprocessStarted ? Json.map("done", transcriptChunksDone, "total", transcriptChunksTotal) : null);
    }

    private Map<String, Object> channelStats() {
        return Json.map("mode", separateChannels ? "channels" : "mono",
                "system", Json.map("frames", systemFrames, "samples", capturedSamples,
                        "queue_depth", sttQueue.size(), "max_queue_depth", maxSttQueueDepth,
                        "overflow_count", sttQueueOverflowCount,
                        "partial_latency_ms", distribution(partialLatencies), "final_latency_ms", distribution(voskLatencies)),
                "microphone", separateChannels ? Json.map("frames", microphoneFrames, "samples", microphoneSamples) : null);
    }

    /** Завершает потоки распознавания и отправляет аналитику итоговые сегменты.
     * @param audioSeconds длительность принятого аудио в секундах
     * @param finalSegments финальные сегменты диаризации
     */
    public void finish(double audioSeconds, List<Map<String, Object>> finalSegments) {
        finish(audioSeconds, finalSegments, true);
    }

    /** Завершает интервью даже при недоступной финальной диаризации.
     * @param audioSeconds продолжительность записи
     * @param finalSegments окончательные интервалы голосов
     * @param diarizationComplete можно ли доверять финальным ролям
     */
    public void finish(double audioSeconds, List<Map<String, Object>> finalSegments, boolean diarizationComplete) {
        synchronized (this) {
            if (closed || finishing) return;
            finishing = true;
            journal("stop", capturedSamples, Json.map("attemptId", currentAttemptId));
            List<FinalTranscriptBuilder.SpeakerSpan> finalRoles = new ArrayList<>();
            if (!testMode && diarizationComplete) for (Map<String, Object> row : finalSegments) {
                if (row.get("start") instanceof Number start && row.get("end") instanceof Number end)
                    finalRoles.add(new FinalTranscriptBuilder.SpeakerSpan(Math.round(start.doubleValue() * RATE),
                            Math.round(end.doubleValue() * RATE), String.valueOf(row.getOrDefault("label", "Не определено")),
                            String.valueOf(row.getOrDefault("source_speaker_id", "unknown"))));
            }
            finalDiarizationSpans = List.copyOf(finalRoles);
            try {
                if (!testMode && diarizationComplete) onDiarization(finalSegments, List.of(), audioSeconds, true);
                else if (!testMode) spans.clear();
            } catch (Throwable flushError) {
                failure = "Ошибка финального сброса хронологии: " + flushError;
                logQuietly(Json.map("type", "bridge_error", "worker", "final_flush", "error", failure));
            }
        }
        try {
            signalEnd(sttQueue, sttWorker, "Vosk");
            sttWorker.join(30_000);
            if (sttWorker.isAlive()) {
                failure = "Очередь Vosk не успела завершиться за 30 секунд.";
                sttWorker.interrupt();
            }
            if (!sttWorker.isAlive()) {
                stt.sendText("eof");
                if (!stt.awaitClosed(10))
                    failure = "Vosk не подтвердил окончание одного из потоков.";
            }
        } catch (Throwable error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            failure = "Ошибка завершения очередей STT: " + error;
        }
        if (deferredFinalReport) {
            finishDeferred();
            synchronized (this) { writeSummary(reportReceived.getCount() == 0 && failure == null); }
            return;
        }
        try {
            sendAnalytics(Json.map("type", "stop", "audioEndSample", capturedSamples));
            if (failure == null && !reportReceived.await(150, TimeUnit.SECONDS))
                failure = "Отчёт analytics-service не получен за 150 секунд.";
            if (failure == null) {
                try {
                    CompletableFuture.allOf(whisperCompletion.values().toArray(new CompletableFuture<?>[0]))
                            .get(125, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException timeout) {
                    logQuietly(Json.map("type", "whisper_timeout", "message", "Whisper не подтвердил уточнение; сохраняем исходный Vosk."));
                }
            }
        }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); failure = "Ожидание отчёта analytics-service прервано."; }
        catch (Throwable error) { failure = "Не удалось завершить сессию analytics-service: " + error; }
        if (audioSpool != null && transcriptError == null) {
            try {
                audioSpool.seal(capturedSamples);
                postprocessWhisper();
            }
            catch (Throwable error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                whisperState = "ошибка повторного прохода";
                transcriptError = "Не удалось обработать полную запись Whisper: " + error.getMessage();
                log.error("Итоговая транскрипция не завершилась; аудиозапись сохранена для диагностики", error);
                logQuietly(Json.map("type", "whisper_postprocess_error", "error", error.toString(),
                        "fallback", "none"));
            }
        } else if (audioSpool == null && transcriptError == null)
            transcriptError = "Whisper выключен: полная транскрипция недоступна.";
        synchronized (this) { writeSummary(reportReceived.getCount() == 0 && failure == null); }
    }

    private void finishDeferred() {
        try {
            if (failure != null) return;
            sendAnalytics(Json.map("type", "prepare_final", "audioEndSample", capturedSamples));
            if (!finalizationReady.await(30, TimeUnit.SECONDS)) throw new java.io.IOException("Нет подтверждения завершения live-этапа");
            if (failure != null) throw new java.io.IOException(failure);
            journal("journal_complete", capturedSamples, Json.map("attemptId", currentAttemptId));
            if (failure != null) throw new java.io.IOException(failure);
            interviewJournal.close();
            List<FinalAnswerAssembler.Interval> intervals = new InterviewJournalReplay().read(
                    directory.resolve("interview-events.jsonl"), sessionId, capturedSamples);
            if (audioSpool != null) {
                try {
                    // Даже при потере канала дописываем накопленные данные для диагностики.
                    audioSpool.seal(capturedSamples);
                    if (!whisperEnabled && transcriptError == null)
                        transcriptError = "Whisper выключен: полная транскрипция недоступна.";
                    if (transcriptError == null) postprocessWhisper();
                } catch (java.io.IOException error) {
                    String detail = "Не удалось завершить запись или обработку Whisper: " + error.getMessage();
                    transcriptError = transcriptError == null ? detail : transcriptError + " " + detail;
                    log.warn("Итоговая транскрипция недоступна; используется Vosk", error);
                }
            } else if (transcriptError == null) transcriptError = "Whisper выключен: полная транскрипция недоступна.";
            boolean fallback = fullTranscriptWords == null;
            String finalSource = fallback ? "vosk" : transcriptFallback ? "mixed" : "whisper";
            // Частично распознанная запись сохраняет слова обоих распознавателей.
            List<Map<String, Object>> rows = fallback ? finalWords : fullTranscriptWords;
            List<FinalAnswerAssembler.Word> words = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                if (!(row.get("start") instanceof Number start) || !(row.get("end") instanceof Number end)) continue;
                String model = String.valueOf(row.get("model"));
                if (fallback && !"vosk".equals(model)) continue;
                String source = testMode ? "test" : String.valueOf(row.get("source"));
                words.add(new FinalAnswerAssembler.Word(String.valueOf(row.get("word")),
                        Math.round(start.doubleValue() * RATE), Math.round(end.doubleValue() * RATE),
                        String.valueOf(row.get("role")), source, model));
            }
            List<FinalAnswerAssembler.Answer> answers = new FinalAnswerAssembler().assemble(intervals, words, testMode);
            for (FinalAnswerAssembler.Answer answer : answers) {
                var interval = answer.interval();
                if (!answer.warnings().isEmpty())
                    logQuietly(Json.map("type", "final_answer_warning", "answer_id", interval.answerId(),
                            "question_id", interval.questionId(), "warnings", answer.warnings()));
                String text = answer.text();
                if (fallback && text.isBlank()) text = voskAnswerText.getOrDefault(interval.answerId(), "");
                Map<String, Object> event = Json.map("type", "final_answer", "attemptId", interval.attemptId(),
                        "answerId", interval.answerId(), "questionId", interval.questionId(),
                        "followUpId", interval.followUpId(), "text", text, "source", finalSource,
                        "transcriptIncomplete", !answer.warnings().isEmpty());
                if (Json.write(event).getBytes(StandardCharsets.UTF_8).length > 30_000)
                    throw new java.io.IOException("Итоговый ответ превышает размер сообщения аналитики");
                sendAnalytics(event);
            }
            sendAnalytics(Json.map("type", "stop", "audioEndSample", capturedSamples,
                    "transcriptSource", finalSource));
            if (!reportReceived.await(150, TimeUnit.SECONDS)) throw new java.io.IOException("Отчёт analytics-service не получен за 150 секунд");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            failure = "Финализация интервью прервана";
        } catch (Exception error) {
            failure = "Ошибка итогового анализа: " + error.getMessage();
            log.error("Ошибка итогового анализа", error);
        }
    }

    private void postprocessWhisper() throws java.io.IOException, InterruptedException {
        try { transcriptTiming = new TranscriptTiming(directory, sessionId, separateChannels, testMode); }
        catch (Exception error) { log.warn("Не удалось подготовить замеры транскрипции: {}", error.toString()); }
        List<PostInterviewWhisper.SpeechEvidence> evidence = new ArrayList<>();
        List<FinalTranscriptBuilder.SpeakerSpan> roles = new ArrayList<>();
        synchronized (this) {
            postprocessStarted = true;
            for (FinalTranscriptBuilder.SpeakerSpan span : finalDiarizationSpans) {
                evidence.add(new PostInterviewWhisper.SpeechEvidence(0, span.start(), span.end()));
                roles.add(span);
            }
            for (Map<String, Object> row : finalWords) {
                if (!"vosk".equals(row.get("model")) || !(row.get("start") instanceof Number start)
                        || !(row.get("end") instanceof Number end)) continue;
                int channel = "microphone".equals(row.get("source")) ? 1 : 0;
                long first = Math.round(start.doubleValue() * RATE), last = Math.round(end.doubleValue() * RATE);
                if (last > first) evidence.add(new PostInterviewWhisper.SpeechEvidence(channel, first, last,
                        Math.max(1, Math.min(RATE / 5L, (last - first) / 2))));
            }
        }
        PostInterviewWhisper.Result result = new PostInterviewWhisper(audioSpool).transcribe(evidence, whisperClient,
                (done, total) -> {
                    transcriptChunksDone = done;
                    transcriptChunksTotal = total;
                    try { if (transcriptTiming != null) transcriptTiming.progress(done); }
                    catch (Exception error) { log.warn("Не удалось измерить время канала: {}", error.toString()); }
                });
        if (deferredFinalReport && result.words().isEmpty() && !result.gaps().isEmpty())
            throw new java.io.IOException("Whisper не распознал ни один канал; итоговая транскрипция недоступна");
        List<Map<String, Object>> rows = new FinalTranscriptBuilder().build(audioSpool, result.words(), roles, testMode);
        for (PostInterviewWhisper.Gap gap : result.gaps()) {
            List<PostInterviewWhisper.Word> reserve = new ArrayList<>();
            synchronized (this) {
                for (Map<String, Object> word : finalWords) {
                    int channel = "microphone".equals(word.get("source")) ? 1 : 0;
                    if (!"vosk".equals(word.get("model")) || channel != gap.channel()
                            || !(word.get("start") instanceof Number start) || !(word.get("end") instanceof Number end)) continue;
                    long first = Math.round(start.doubleValue() * RATE), last = Math.round(end.doubleValue() * RATE);
                    long midpoint = first + (last - first) / 2;
                    // Повторная обработка могла восстановить часть интервала, ранее отмеченного как сбой.
                    boolean covered = result.words().stream().anyMatch(existing -> existing.channel() == channel
                            && midpoint >= existing.start() && midpoint < existing.end());
                    if (midpoint >= gap.start() && midpoint < gap.end() && !covered)
                        reserve.add(new PostInterviewWhisper.Word(channel, String.valueOf(word.get("word")), first, last, -1, null));
                }
            }
            if (reserve.isEmpty()) {
                rows.add(Json.map("word", "[Аудио этого участка не распознано]", "word_id", "gap:" + gap.channel() + ":" + gap.start(),
                        "start", gap.start() / (double) RATE, "end", gap.end() / (double) RATE,
                        "role", testMode ? "Кандидат" : gap.channel() == 1 ? "Интервьюер 1" : "Не определено",
                        "source", gap.channel() == 1 ? "microphone" : "system",
                        "speaker_id", "gap", "model", "unavailable", "status", "final"));
            } else {
                List<Map<String, Object>> fallbackRows = new FinalTranscriptBuilder().build(audioSpool, reserve, roles, testMode);
                for (Map<String, Object> row : fallbackRows) {
                    row.put("role", row.get("role") + " (резерв Vosk)");
                    row.put("model", "vosk");
                    row.put("word_id", "fallback:" + gap.channel() + ":" + gap.start() + ":" + row.get("word_id"));
                }
                rows.addAll(fallbackRows);
            }
            logQuietly(Json.map("type", "whisper_postprocess_gap", "channel", gap.channel(),
                    "start_s", Json.round(gap.start() / (double) RATE, 3),
                    "end_s", Json.round(gap.end() / (double) RATE, 3),
                    "fallback", reserve.isEmpty() ? "unavailable" : "vosk", "words", reserve.size()));
        }
        transcriptFallback = !result.gaps().isEmpty();
        synchronized (this) {
            fullTranscriptWords = List.copyOf(rows);
            for (PostInterviewWhisper.Chunk chunk : result.chunks())
                logQuietly(Json.map("type", "whisper_postprocess_chunk", "channel", chunk.channel(),
                        "start_s", Json.round(chunk.start() / (double) RATE, 3),
                        "end_s", Json.round(chunk.end() / (double) RATE, 3), "words", chunk.words()));
            logQuietly(Json.map("type", "whisper_postprocess", "chunks", result.chunks().size(),
                    "words", rows.size(), "fallback", transcriptFallback ? "vosk_or_gap" : "none"));
        }
    }

    private void signalEnd(ArrayBlockingQueue<byte[]> queue, Thread worker, String name) throws InterruptedException {
        if (!worker.isAlive()) return;
        if (!queue.offer(END, 5, TimeUnit.SECONDS)) {
            failure = "Не удалось закрыть очередь " + name + ": worker не освобождает место.";
            worker.interrupt();
        }
    }

    private void markWhisperReplacements() {
        for (Map<String, Object> word : finalWords) {
            if (!"vosk".equals(word.get("model"))) continue;
            if ("replaced_by_whisper".equals(word.get("status"))) word.put("status", "final");
            if (!"Кандидат".equals(word.get("role"))) continue;
            long start = Math.round(((Number) word.get("start")).doubleValue() * RATE);
            long end = Math.round(((Number) word.get("end")).doubleValue() * RATE);
            if (acceptedWhisperRanges.values().stream().flatMap(List::stream)
                    .anyMatch(range -> start < range.end() && end > range.start()))
                word.put("status", "replaced_by_whisper");
        }
    }

    private static List<SampleRange> whisperCoverage(List<Map<String, Object>> words) {
        List<SampleRange> coverage = new ArrayList<>();
        for (Map<String, Object> word : words) {
            long start = Math.max(0, Math.round(((Number) word.get("start")).doubleValue() * RATE) - RATE / 10L);
            long end = Math.round(((Number) word.get("end")).doubleValue() * RATE) + RATE / 10L;
            if (coverage.isEmpty() || start - coverage.get(coverage.size() - 1).end() > RATE / 2L)
                coverage.add(new SampleRange(start, end));
            else {
                int last = coverage.size() - 1;
                coverage.set(last, new SampleRange(coverage.get(last).start(), Math.max(end, coverage.get(last).end())));
            }
        }
        return coverage;
    }

    private void writeTimeline() {
        try {
            List<Map<String, Object>> rows = currentWords();
            rows.sort((a, b) -> Double.compare(((Number) a.get("start")).doubleValue(),
                    ((Number) b.get("start")).doubleValue()));
            Out.writePretty(directory.resolve("timeline.json"), rows);
        }
        catch (Exception error) { failure = error.toString(); }
    }

    private List<Map<String, Object>> currentWords() {
        List<Map<String, Object>> rows = new ArrayList<>(finalWords);
        for (List<Map<String, Object>> partial : preliminaryWords.values()) rows.addAll(partial);
        rows.sort(java.util.Comparator.comparingDouble((Map<String, Object> word) -> ((Number) word.get("start")).doubleValue())
                .thenComparing(word -> String.valueOf(word.get("source")))
                .thenComparing(word -> String.valueOf(word.get("word_id"))));
        return rows;
    }

    private void writeSummary(boolean finished) {
        try {
            unknownWords = (int) finalWords.stream().filter(w -> "Не определено".equals(w.get("role"))).count();
            Out.writePretty(directory.resolve("integration-summary.json"), Json.map(
                    "finished", finished, "audio_seconds", Json.round(capturedSamples / (double) RATE, 3),
                    "channels", channelStats(),
                    "candidate_warning", candidateWarning,
                    "vosk_events", sttEvents, "final_words", finalWords.size(), "unknown_role_words", unknownWords,
                    "late_candidate_segments", lateCandidateSegments, "bridge_error", failure,
                    "stt_queue", Json.map("capacity_chunks", 128, "max_depth_chunks", maxSttQueueDepth,
                            "overflow_count", sttQueueOverflowCount),
                    "cpu_process_load_percent", distribution(cpuLoadPercent),
                    "cpu_system_load_percent", distribution(systemCpuLoadPercent),
                    "end_to_ui_ack_ms", distribution(uiLatencies),
                    "end_to_decision_ms", distribution(decisionLatencies),
                    "end_to_diarization_ms", distribution(diarizationLatencies),
                    "end_to_candidate_gate_ms", distribution(roleWaitLatencies),
                    "end_to_vosk_first_partial_ms", distribution(partialLatencies),
                    "end_to_vosk_final_word_ms", distribution(voskLatencies),
                    "end_to_role_ms", distribution(roleLatencies),
                    "end_to_candidate_dispatch_ms", distribution(candidateDispatchLatencies),
                    "partial_to_preliminary_hint_ui_ms", distribution(previewLatencies),
                    "end_to_preliminary_hint_ms", distribution(endToPreliminaryHintLatencies),
                    "preliminary_hint_lead_ms", distribution(preliminaryHintLeadLatencies),
                    "peek_compute_ms", distribution(peekComputeLatencies),
                    "decision_to_whisper_ms", distribution(whisperLatencies),
                    "outputs", Json.map("stt_events", directory.resolve("stt-events.jsonl").toString(),
                            "timeline_events", directory.resolve("timeline-events.jsonl").toString(),
                            "timeline", directory.resolve("timeline.json").toString())));
        } catch (Exception error) { failure = error.toString(); }
    }

    private void recordPreliminaryHintLatency(int answerId) {
        if (measuredHintAnswers.contains(answerId)) return;
        Long base = answerBases.get(answerId), end = decisionAudioEnds.get(answerId);
        if (base == null || end == null || end <= base) return;
        String latestEvent = null;
        long latestSample = Long.MIN_VALUE;
        for (var entry : previewEventSamples.entrySet()) {
            Long visible = previewEventVisibleNanos.get(entry.getKey());
            long sample = entry.getValue();
            if (visible == null || sample < base || sample > end || sample <= latestSample) continue;
            latestEvent = entry.getKey();
            latestSample = sample;
        }
        if (latestEvent == null) return;
        measuredHintAnswers.add(answerId);
        long speechEndNanos = estimateCapturedAt(end);
        long visibleAt = previewEventVisibleNanos.get(latestEvent);
        double delay = Math.max(0, (visibleAt - speechEndNanos) / 1e6);
        double lead = Math.max(0, (speechEndNanos - visibleAt) / 1e6);
        endToPreliminaryHintLatencies.add(delay);
        if (lead > 0) preliminaryHintLeadLatencies.add(lead);
        logQuietly(Json.map("type", "preliminary_hint_answer_latency", "answer_id", answerId,
                "answer_end_s", Json.round(end / (double) RATE, 3),
                "event_id", latestEvent, "end_to_hint_ms", Json.round(delay, 1),
                "hint_lead_before_end_ms", Json.round(lead, 1)));
    }

    private static long parsePeekRequestOrdinal(String requestId) {
        if (requestId == null || !requestId.startsWith("peek-")) return 0;
        try { return Long.parseLong(requestId.substring(5)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    /** Проверяет, относится ли подсказка к аудио, уже закрытому решением.
     * @param previewSample позиция подсказки в сэмплах
     * @param decisionAudioEnd конечная позиция аудио в решении
     * @return {@code true}, если подсказку следует считать устаревшей для текущего решения
     */
    public static boolean isPreviewAtOrBeforeDecision(long previewSample, long decisionAudioEnd) {
        return decisionAudioEnd > 0 && previewSample <= decisionAudioEnd;
    }

    private void sampleCpuLoad() {
        java.lang.management.OperatingSystemMXBean bean = java.lang.management.ManagementFactory
                .getOperatingSystemMXBean();
        if (!(bean instanceof com.sun.management.OperatingSystemMXBean processBean)) return;
        while (!closed) {
            double load = processBean.getProcessCpuLoad();
            if (load >= 0 && Double.isFinite(load)) cpuLoadPercent.add(load * 100);
            double systemLoad = processBean.getSystemCpuLoad();
            if (systemLoad >= 0 && Double.isFinite(systemLoad)) systemCpuLoadPercent.add(systemLoad * 100);
            try { Thread.sleep(1000); }
            catch (InterruptedException done) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static Map<String, Object> distribution(List<Double> values) {
        if (values.isEmpty()) return Json.map("count", 0, "p50", null, "p95", null, "max", null);
        return Json.map("count", values.size(), "p50", Json.round(Json.percentile(values, .5), 1),
                "p95", Json.round(Json.percentile(values, .95), 1),
                "max", Json.round(values.stream().mapToDouble(Double::doubleValue).max().orElse(0), 1));
    }

    private void write(BufferedWriter out, Map<String, Object> data) throws Exception {
        String line = Json.MAPPER.writeValueAsString(data);
        out.write(line); out.newLine(); out.flush();
    }

    private synchronized void emit(Map<String, Object> data) {
        if (closed) return;
        try { write(timelineLog, data); }
        catch (Exception error) { failure = error.toString(); }
    }

    private void logQuietly(Map<String, Object> data) { emit(data); }

    private void journal(String type, long sample, Map<String, ?> fields) {
        if (interviewJournal == null) return;
        try { interviewJournal.append(type, sample, fields); }
        catch (java.io.IOException error) {
            failure = "Ошибка журнала интервью: " + error.getMessage();
            log.error("Не удалось сохранить событие интервью", error);
        }
    }

    /** Возвращает финальные слова после закрытия потоков.
     * @return независимая копия таймлайна
     */
    public synchronized List<Map<String, Object>> finalTranscriptWords() {
        if (fullTranscriptWords == null) return List.of();
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> word : fullTranscriptWords) {
            Map<String, Object> row = new LinkedHashMap<>(word);
            if ("preliminary".equals(row.get("role_status"))) {
                row.put("role", "Не определено");
                row.put("role_status", "unknown");
            }
            copy.add(row);
        }
        return copy;
    }

    /** @return можно ли считать обработку STT завершённой */
    public synchronized boolean transcriptComplete() {
        return transcriptError == null && fullTranscriptWords != null;
    }

    /** @return причина недоступности полной транскрипции или {@code null} */
    public String transcriptError() { return transcriptError; }

    /** @return использовался ли резервный текст или остался нераспознанный участок */
    public boolean transcriptFallback() { return transcriptFallback; }

    void finishTranscriptTiming(boolean ready) {
        if (transcriptTiming == null) return;
        try { transcriptTiming.finish(ready); }
        catch (Exception error) { log.warn("Не удалось измерить общее время транскрипции: {}", error.toString()); }
    }

    /** Отмечает потерю исходного канала, не останавливая подсказки.
     * @param reason описание потери */
    public void audioCaptureLost(String reason) { transcriptError = reason; }

    /** Удаляет запись после успешной публикации.
     * @throws java.io.IOException если файл удалить не удалось */
    public void deleteAudioSpool() throws java.io.IOException { if (audioSpool != null) audioSpool.delete(); }

    /** @return каталог результатов сессии */
    public Path outputDirectory() { return directory; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        cpuSampler.interrupt();
        try { stt.sendText("eof"); } catch (Exception ignored) {}
        try { stt.sendClose(1000, "done"); } catch (Exception ignored) {}
        try { interview.sendClose(1000, "done"); } catch (Exception ignored) {}
        try { sttLog.close(); } catch (Exception ignored) {}
        try { timelineLog.close(); } catch (Exception ignored) {}
        if (interviewJournal != null) try { interviewJournal.close(); }
        catch (java.io.IOException error) { failure = "Ошибка журнала интервью: " + error.getMessage(); }
        if (audioSpool != null) audioSpool.close();
        if (liveWhisperExecutor != null) liveWhisperExecutor.shutdownNow();
    }
}
