package ru.sttplatform.analytics.live;

import ru.sttplatform.analytics.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Имитация потока кадров, который stt-service (Orchestrator.candidateWorker) отправляет аналитике, — в ВИРТУАЛЬНОМ времени.
 * Каждый кадр — 50 мс аудио. Детектор конца ответа тот же, что в STT, поэтому {@code vadState}, {@code silenceMs} и
 * {@code audioMs} совпадают с боевыми. Реальные часы нигде не используются: тест детерминирован и быстр.
 */
final class SttSim {
    static final int FRAME_MS = 50;
    static final int FRAME_SAMPLES = 16_000 * FRAME_MS / 1000;

    final List<Map<String, Object>> events;
    final LiveInterview live;
    final EndpointDetector detector = new EndpointDetector(new EndpointDetector.Config());
    final AtomicInteger frames = new AtomicInteger();
    /** Виртуальные часы клиента: сколько мс аудио прошло с начала сеанса. */
    long nowMs;
    VirtualJudge virtualJudge;
    private String committedText = "";
    private String partialText = "";

    private SttSim(LiveInterview live, List<Map<String, Object>> events) {
        this.live = live;
        this.events = events;
    }

    static SttSim create(InterviewKnowledge kb, List<Level> levels, LiveInterview.Timing timing, AnswerJudge judge,
                         long deadlineMs) {
        List<Map<String, Object>> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        InterviewSession session = new InterviewSession(kb, levels, new DecisionPolicy(2, 0.08));
        LiveInterview live = new LiveInterview(new EventSpeechEngine(), session,
                new EndpointDetector(new EndpointDetector.Config()), events::add);
        if (timing != null) live.setTiming(timing);
        if (judge != null) live.setJudge(judge, deadlineMs);
        SttSim sim = new SttSim(live, events);
        if (judge instanceof VirtualJudge vj) { sim.virtualJudge = vj; vj.bind(sim); }
        return sim;
    }

    // ---------------------------------------------------------------- сценарные шаги

    /** Клиент озвучил вопрос и готов слушать: детектор и текст ответа сбрасываются, как в Orchestrator на «ready». */
    void ready() {
        live.onReady();
        detector.reset();
        committedText = "";
        partialText = "";
    }

    /** Один кадр. {@code type}: "", "partial" или "final"; {@code text} — текст события Vosk. */
    String frame(boolean voiced, String type, String text) {
        byte[] pcm = voiced ? voicedPcm() : silentPcm();
        if ("final".equals(type)) {
            committedText = (committedText.isEmpty() ? "" : committedText + " ") + text;
            partialText = "";
        } else if ("partial".equals(type)) {
            partialText = text;
        }
        String current = (committedText + (partialText.isBlank() ? "" : " " + partialText)).trim();
        EndpointDetector.State prior = detector.state();
        EndpointDetector.State vad = detector.onFrame(pcm, pcm.length, wordCount(current), endsWithConnector(current));
        String sendType = type;
        String sendText = text;
        if (vad == EndpointDetector.State.ENDPOINT && prior != EndpointDetector.State.ENDPOINT && !"final".equals(type)) {
            // как Orchestrator: на мягком конце распознаватель «дожимается» и отдаёт остаток как final
            sendType = "final";
            sendText = partialText;
            if (!partialText.isBlank()) {
                committedText = (committedText.isEmpty() ? "" : committedText + " ") + partialText;
                partialText = "";
            }
        }
        nowMs += FRAME_MS;
        frames.incrementAndGet();
        if (virtualJudge != null) virtualJudge.tick();
        live.onSttFrame(sendType == null ? "" : sendType, sendText == null ? "" : sendText, FRAME_SAMPLES, rms(pcm), 1.0,
                detector.state().name(), detector.audioMs(), detector.speechStartMs(), detector.speechDurationMs(),
                detector.silenceMs());
        return detector.state().name();
    }

    /** Кандидат говорит: слова открываются равномерно по кадрам, как у настоящего Vosk. */
    void speak(String answer, int speechFrames) {
        String[] words = answer.isBlank() ? new String[0] : answer.split(" ");
        for (int i = 1; i <= speechFrames; i++) {
            int shown = Math.min(words.length, Math.max(1, words.length * i / speechFrames));
            frame(true, "partial", String.join(" ", java.util.Arrays.copyOfRange(words, 0, shown)));
        }
    }

    /** Тишина до события нужного типа; возвращает число мс тишины или -1, если событие не пришло за {@code maxMs}. */
    long silenceUntil(String type, long maxMs) {
        int before = count(type);
        for (long t = 0; t < maxMs; t += FRAME_MS) {
            frame(false, "", "");
            if (count(type) > before) return t + FRAME_MS;
        }
        return -1;
    }

    void silence(long ms) {
        for (long t = 0; t < ms; t += FRAME_MS) frame(false, "", "");
    }

    // ---------------------------------------------------------------- запросы к журналу событий

    int count(String type) {
        int n = 0;
        for (Map<String, Object> e : events) if (type.equals(e.get("type"))) n++;
        return n;
    }

    Map<String, Object> last(String type) {
        for (int i = events.size() - 1; i >= 0; i--) if (type.equals(events.get(i).get("type"))) return events.get(i);
        return null;
    }

    List<Map<String, Object>> all(String type) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : events) if (type.equals(e.get("type"))) out.add(e);
        return out;
    }

    static long silenceMsOf(Map<String, Object> decision) {
        return ((Number) ((Map<?, ?>) decision.get("timing")).get("silenceMs")).longValue();
    }

    // ---------------------------------------------------------------- звук и текст

    private static byte[] voicedPcm() {
        byte[] b = new byte[FRAME_SAMPLES * 2];
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            short s = (short) Math.round(6000 * Math.sin(2 * Math.PI * 220 * i / 16_000.0));
            b[2 * i] = (byte) (s & 0xff);
            b[2 * i + 1] = (byte) ((s >> 8) & 0xff);
        }
        return b;
    }

    private static byte[] silentPcm() {
        return new byte[FRAME_SAMPLES * 2];
    }

    private static double rms(byte[] pcm) {
        double sum = 0;
        for (int i = 0; i < pcm.length / 2; i++) {
            int s = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
            sum += (double) s * s;
        }
        return Math.sqrt(sum / (pcm.length / 2));
    }

    static int wordCount(String text) {
        return text == null || text.isBlank() ? 0 : text.trim().split("\\s+").length;
    }

    static boolean endsWithConnector(String text) {
        if (text == null || text.isBlank()) return false;
        String[] words = text.toLowerCase(java.util.Locale.ROOT).trim().split("\\s+");
        return java.util.Set.of("и", "или", "но", "что", "потому", "например", "для", "при", "с", "в", "на", "к")
                .contains(words[words.length - 1]);
    }

    // ---------------------------------------------------------------- управляемая модель

    /** Модель, ответ которой приходит через заданное число МС ВИРТУАЛЬНОГО времени после запроса. */
    static final class VirtualJudge implements AnswerJudge {
        private final SttSim[] sim = new SttSim[1];
        private final long latencyMs;
        private final Verdict verdict;
        private final boolean fail;
        private boolean ready = true;
        final List<CompletableFuture<Verdict>> decideCalls = new ArrayList<>();
        final List<Long> requestedAt = new ArrayList<>();
        int scoreCalls, previewCalls;
        boolean throwOnDecide;
        boolean nullPreview;

        VirtualJudge(long latencyMs, Verdict verdict, boolean fail) {
            this.latencyMs = latencyMs;
            this.verdict = verdict;
            this.fail = fail;
        }

        void bind(SttSim s) { sim[0] = s; }
        void setReady(boolean ready) { this.ready = ready; }

        @Override public CompletableFuture<Verdict> decide(JudgeInput input) {
            if (throwOnDecide) throw new IllegalStateException("модель недоступна");
            CompletableFuture<Verdict> f = new CompletableFuture<>();
            decideCalls.add(f);
            requestedAt.add(sim[0] == null ? 0L : sim[0].nowMs);
            if (latencyMs == 0) complete(f);
            return f;
        }

        /** Вызывается тестом каждый кадр: завершает запросы, у которых вышло виртуальное время. */
        void tick() {
            for (int i = 0; i < decideCalls.size(); i++) {
                CompletableFuture<Verdict> f = decideCalls.get(i);
                if (!f.isDone() && sim[0].nowMs - requestedAt.get(i) >= latencyMs && latencyMs < Long.MAX_VALUE) complete(f);
            }
        }

        private void complete(CompletableFuture<Verdict> f) {
            if (fail) f.completeExceptionally(new IllegalStateException("модель вернула ошибку"));
            else f.complete(verdict);
        }

        @Override public CompletableFuture<Verdict> preview(JudgeInput input) {
            previewCalls++;
            if (nullPreview) return null;
            return CompletableFuture.completedFuture(verdict);
        }

        @Override public CompletableFuture<Verdict> score(JudgeInput input) {
            scoreCalls++;
            return CompletableFuture.completedFuture(verdict);
        }

        @Override public boolean ready() { return ready; }
    }
}
