package ru.sttplatform.analytics.live;

import ru.sttplatform.analytics.engine.AnswerJudge;
import ru.sttplatform.analytics.engine.AnswerTiming;
import ru.sttplatform.analytics.engine.EndpointDetector;
import ru.sttplatform.analytics.engine.InterviewDetails;
import ru.sttplatform.analytics.engine.InterviewSession;
import ru.sttplatform.analytics.engine.SpokenText;
import ru.sttplatform.analytics.engine.TermNormalizer;
import ru.sttplatform.analytics.engine.Verdict;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Склейка конвейера для одного подключения: звук → Vosk → конец ответа → решение → следующий вопрос.
 *
 * <h3>Синхронизация: не перебивать и не терять слова</h3>
 * Конец ответа проходит три стадии, и на каждой человек может продолжить говорить:
 * <ol>
 *   <li><b>Мягкий конец</b> (тишина ≈ 0,45 с): распознавание «дожимается» (flush), так что текст полный;
 *       по нему сразу считается <i>предварительное</i> решение — но не принимается.</li>
 *   <li><b>Жёсткий конец</b>: решение принимается после дополнительной паузы, зависящей от решения.
 *       Законченный ответ → почти сразу (+0,15 с, итого ≈ 0,6 с). Неполный ответ, после которого нужно уточнение, —
 *       намного дольше (+1,5 с): именно тут человек чаще всего просто думает, и перебивать его нельзя.
 *       Если речь возобновилась — предварительное решение отбрасывается, текст продолжает копиться.</li>
 *   <li><b>Окно отмены</b> (grace, ≈ 1 с после решения): микрофон остаётся открытым, а следующий вопрос
 *       клиент показывает и озвучивает только после окна. Если человек всё-таки продолжил — решение
 *       откатывается (retract), и он дозаканчивает ответ на прежний вопрос.</li>
 * </ol>
 * Решение считается за микросекунды; тяжёлое (flush Vosk, второй проход Whisper, пересчёт отчёта) идёт вне критического пути.
 * @version 1.0.0
 */
public final class LiveInterview {

    /**
     * Передаёт события интервью WebSocket-клиенту.
     *
     * @version 1.0.0
     */
    public interface Sender {
        void send(Map<String, Object> message);
    }

    /**
     * Паузы синхронизации, мс (сверх «мягкого конца» детектора).
     *
     * @param nextExtraMs      ответ достаточный → к следующему вопросу
     * @param otherNextExtraMs «не знаю», нет ответа, не по теме и т.п. → к следующему вопросу
     * @param followUpExtraMs  ответ неполный → уточняющий вопрос (самая осторожная пауза)
     * @param graceMs          окно отмены решения; следующий вопрос клиент показывает после него
     * @version 1.0.0
     */
    public record Timing(int nextExtraMs, int otherNextExtraMs, int followUpExtraMs, int graceMs) {
        /**
         * Выполняет операцию публичного контракта компонента.
         *
         * @return результат операции
         */
        public static Timing defaults() {
            return new Timing(150, 600, 1500, 1000);
        }
    }

    /** Состояния автомата обработки ответа и перехода к следующему вопросу.
     * @version 1.0.0
     */
    private enum Phase { IDLE, LISTENING, TENTATIVE, GRACE, WAIT_READY }

    private static final int BYTES_PER_MS = 32;                 // 16 кГц * 2 байта
    private static final int MAX_AUDIO_BYTES = 180_000 * BYTES_PER_MS;

    private final SpeechEngine speech;
    private final InterviewSession interview;
    private final EndpointDetector detector;
    private final Sender out;
    private final TermNormalizer normalizer;
    private final SpokenText spoken;

    private Timing timing = Timing.defaults();
    private Transcriber transcriber;
    private long pendingWaitMs = 25_000;
    private boolean awaitExternalRefinement;
    private final Map<Integer, InterviewSession.AttemptRef> answerRefs = new ConcurrentHashMap<>();
    private final Set<Integer> pendingRefinements = ConcurrentHashMap.newKeySet();
    private final Object refinementLock = new Object();
    private ReportExporter exporter;
    private String candidate = "";
    private java.time.Instant startedAt = java.time.Instant.now();
    private Map<String, String> settings = Map.of();
    private final List<CompletableFuture<?>> pending = new CopyOnWriteArrayList<>();

    private final StringBuilder committed = new StringBuilder();
    private final ByteArrayOutputStream answerAudio = new ByteArrayOutputStream();
    private String partial = "";
    private String current = "";          // для анализа
    private String currentDisplay = "";   // для экрана
    private int words;
    private boolean connector;

    private Phase phase = Phase.IDLE;
    private InterviewSession.Peek tentative;
    // смысловая оценка модели на мягком конце ответа: решение ждёт заключение, но не дольше judgeDeadlineMs
    private AnswerJudge judge;
    private long judgeDeadlineMs = 1200;
    private CompletableFuture<Verdict> tentativeFuture;
    private String tentativeText;
    private Verdict tentativeVerdict;
    private boolean tentativeResolved;
    private long tentativeStartSilenceMs;
    private long commitAtSilenceMs;
    private long graceStartAudioMs;
    private boolean pendingFinish;
    private long ttsEndMs;               // когда закончилась озвучка вопроса (режим «в наушниках»): это время не считается раздумьем
    private volatile int answerVersion;   // растёт при откате: устаревшие результаты Whisper игнорируются
    private int answerId;
    private boolean finished;

    // контроль «успевает ли распознавание за речью»
    private double rtf;
    private int frames;
    private long lastRtfWarn;

    /**
     * Создаёт объект с указанными зависимостями и настройками.
     *
     * @param speech речевой движок
     * @param interview состояние интервью
     * @param detector детектор конца ответа
     * @param out выходной поток
     */
    public LiveInterview(SpeechEngine speech, InterviewSession interview, EndpointDetector detector, Sender out) {
        this.speech = speech;
        this.interview = interview;
        this.detector = detector;
        this.out = out;
        this.normalizer = interview.knowledge().normalizer();
        this.spoken = interview.knowledge().spoken();
    }

    /**
     * Настраивает представление отчёта перед записью XLSX.
     *
     * @param timing паузы между состояниями интервью
     */
    public void setTiming(Timing timing) {
        this.timing = timing;
    }

    /**
     * Подключает смысловую оценку ответов моделью. Без неё (или пока модель недоступна) решения принимают правила
     * на ключевых словах.
     *
     * @param judge модель оценки или {@code null}
     * @param decisionDeadlineMs сколько ждать заключение после конца ответа, прежде чем решить по правилам
     */
    public void setJudge(AnswerJudge judge, long decisionDeadlineMs) {
        this.judge = judge;
        this.judgeDeadlineMs = decisionDeadlineMs;
    }

    /**
     * Подключает второй проход распознавания.
     *
     * @param transcriber второй проход
     * @param waitMs сколько ждать его перед отчётом
     */
    public void setTranscriber(Transcriber transcriber, long waitMs) {
        this.transcriber = transcriber;
        this.pendingWaitMs = waitMs;
    }

    /** Ожидает уточнение текста, которое STT пришлёт по внутреннему каналу.
     * @param waitMs предельное время ожидания в миллисекундах; ноль отключает ожидание
     */
    public void setRefinementWait(long waitMs) {
        this.pendingWaitMs = Math.max(0, waitMs);
        this.awaitExternalRefinement = waitMs > 0;
    }

    /** Настраивает место сохранения отчёта и сведения о кандидате.
     * @param exporter средство экспорта отчёта
     * @param candidate имя кандидата для отчёта
     * @param settings настройки, которые сохраняются в отчёте
     */
    public void setExport(ReportExporter exporter, String candidate, Map<String, String> settings) {
        this.exporter = exporter;
        this.candidate = candidate == null ? "" : candidate.trim();
        this.settings = settings == null ? Map.of() : settings;
    }

    /**
     * Отправляет первый вопрос и начинает состояние интервью.
     */
    public void start() {
        startedAt = java.time.Instant.now();
        out.send(question(interview.start(), 0));
    }

    /** Клиент показал/озвучил вопрос и готов слушать ответ: с этого момента идёт отсчёт «скорости ответа». */
    public void onReady() {
        if (finished) return;
        committed.setLength(0);
        answerAudio.reset();
        partial = "";
        current = "";
        currentDisplay = "";
        words = 0;
        connector = false;
        tentative = null;
        pendingFinish = false;
        ttsEndMs = 0;
        detector.reset();
        phase = Phase.LISTENING;
    }

    /** Формирует предварительное решение для демо без изменения состояния интервью.
     * @param displayText текущий распознанный текст ответа
     * @param requestId идентификатор запроса подсказки
     */
    public void onPeek(String displayText, String requestId) {
        if (finished || phase == Phase.IDLE || phase == Phase.WAIT_READY || displayText == null || displayText.isBlank())
            return;
        long started = System.nanoTime();
        if (judge != null && judge.ready()) {
            CompletableFuture<Verdict> future = judge.preview(interview.judgeInput(displayText));
            if (future != null) {                  // модель свободна — подсказка по смыслу; иначе ниже мгновенная по правилам
                future.whenComplete((verdict, error) -> {
                    InterviewSession.Peek peek = interview.peek(normalizer.normalize(displayText, false), error == null ? verdict : null);
                    out.send(previewEvent(peek, displayText, requestId, started));
                });
                return;
            }
        }
        out.send(previewEvent(interview.peek(normalizer.normalize(displayText, false)), displayText, requestId, started));
    }

    private Map<String, Object> previewEvent(InterviewSession.Peek peek, String displayText, String requestId, long started) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "preview");
        event.put("requestId", requestId);
        event.put("action", peek.followUp() ? "ASK_FOLLOW_UP" : "NEXT_QUESTION");
        event.put("reason", peek.reason());
        event.put("engine", peek.engine());
        event.put("coveragePercent", (int) Math.round(peek.coverage() * 100));
        event.put("answerWords", peek.words());
        event.put("recognized", displayText);
        event.put("suggestedFollowUp", peek.followUpQuestion());
        event.put("readOnly", true);
        event.put("computeMs", Math.round((System.nanoTime() - started) / 100_000.0) / 10.0);
        return event;
    }

    /**
     * Режим «в наушниках»: микрофон открыт уже во время озвучки вопроса, и клиент сообщает, когда она закончилась
     * (мс после «готов»). Время озвучки не считается задержкой ответа: скорость оценивается от конца вопроса.
     * @param afterReadyMs параметр операции
     */
    public void onTtsEnd(long afterReadyMs) {
        ttsEndMs = Math.max(0, afterReadyMs);
    }

    /**
     * Обрабатывает PCM-кадр ответа и переходы после паузы.
     *
     * @param pcm байты PCM16 без WAV-заголовка
     * @param len число значимых байтов
     */
    public void onAudio(byte[] pcm, int len) {
        if (finished || phase == Phase.IDLE || phase == Phase.WAIT_READY) return;
        if (transcriber != null && answerAudio.size() < MAX_AUDIO_BYTES) answerAudio.write(pcm, 0, len);

        long t0 = System.nanoTime();
        List<SpeechEngine.Result> results = speech.accept(pcm, len);
        trackRealtime(System.nanoTime() - t0, len);
        if (!results.isEmpty()) {
            foldResults(results);
            send("partial", "text", currentDisplay);
        }

        EndpointDetector.State st = detector.onFrame(pcm, len, words, connector);
        advance(st);
    }

    /** Передаёт уже распознанный кадр; аналитика не получает PCM-аудио.
     * @param type тип события Vosk: {@code partial} или {@code final}
     * @param text распознанный текст
     * @param samples число сэмплов обработанного аудио
     * @param rms среднеквадратичная энергия кадра
     * @param asrMs время распознавания кадра в миллисекундах
     * @param vadState состояние детектора ответа на стороне STT
     * @param audioMs позиция в аудиопотоке в миллисекундах
     * @param speechStartMs позиция начала речи в миллисекундах
     * @param speechDurationMs длительность речи в миллисекундах
     * @param silenceMs длительность тишины в миллисекундах
     */
    public void onSttFrame(String type, String text, int samples, double rms, double asrMs,
                           String vadState, long audioMs, long speechStartMs,
                           long speechDurationMs, long silenceMs) {
        if (finished || phase == Phase.IDLE || phase == Phase.WAIT_READY) return;
        SpeechEngine.Result result = null;
        if ("partial".equals(type) || "final".equals(type)) {
            result = new SpeechEngine.Result("final".equals(type), text == null ? "" : text);
            foldResults(List.of(result));
            send("partial", "text", currentDisplay);
        }
        if (speech instanceof EventSpeechEngine eventSpeech) eventSpeech.observe(result);
        trackRealtime((long) (asrMs * 1_000_000), samples * 2);
        detector.observeExternal(EndpointDetector.State.valueOf(vadState), audioMs,
                speechStartMs, speechDurationMs, silenceMs);
        advance(detector.state());
    }

    private void advance(EndpointDetector.State st) {
        switch (phase) {
            case LISTENING -> {
                if (st == EndpointDetector.State.ENDPOINT) enterTentative();
                else if (st == EndpointDetector.State.NO_SPEECH) commit("", "", new AnswerTiming(detector.audioMs(), 0), false);
            }
            case TENTATIVE -> {
                if (st != EndpointDetector.State.ENDPOINT) cancelTentative();                 // человек продолжил
                else if (resolveTentative() && detector.silenceMs() >= commitAtSilenceMs) commitCurrent(true);   // жёсткий конец
            }
            case GRACE -> {
                if (st == EndpointDetector.State.SPEAKING) retract();                          // продолжил в окне отмены
                else if (detector.audioMs() - graceStartAudioMs >= timing.graceMs()) endGrace();
            }
            default -> { }
        }
    }

    /** Кнопка «Пропустить вопрос». */
    public void onSkip() {
        if (finished || phase == Phase.GRACE) return;   // в окне отмены на экране ещё прежний вопрос
        commit("", "", new AnswerTiming(0, 0), false);
    }

    /** Кнопка «Завершить»: отчёт по уже отвеченным вопросам. */
    public void onStop() {
        if (finished) return;
        finishInterview();
    }

    /** Применяет позднюю расшифровку Whisper к тому же ответу, который уже оценён.
     * @param id идентификатор попытки ответа
     * @param text уточнённый текст распознавания
     */
    public void onRefined(int id, String text) {
        InterviewSession.AttemptRef ref = answerRefs.get(id);
        if (ref == null || text == null || text.isBlank()) return;
        interview.refine(ref, normalizer.normalize(text, false), normalizer.normalize(text, true), 2);
        startScoring(ref, id);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "refined");
        event.put("answerId", id);
        event.put("text", normalizer.normalize(text, true));
        out.send(event);
    }

    /** Завершает ожидание уточнения, включая отключённый или завершившийся ошибкой Whisper.
     * @param id идентификатор попытки ответа
     */
    public void onRefineDone(int id) {
        onRefineDone(id, "");
    }

    /** Закрывает ожидание уточнения и показывает ошибку Whisper, если она пришла.
     * @param id идентификатор попытки ответа
     * @param error текст ошибки или пустая строка при успешном завершении
     */
    public void onRefineDone(int id, String error) {
        pendingRefinements.remove(id);
        synchronized (refinementLock) { refinementLock.notifyAll(); }
        if (error != null && !error.isBlank())
            out.send(Map.of("type", "whisper_error", "answerId", id, "message", error));
    }

    // ------------------------------------------------------------------ стадии конца ответа

    /** Мягкий конец: дожимаем распознавание, чтобы текст был полным, и готовим (не принимаем!) решение. */
    private void enterTentative() {
        foldResults(speech.finish());
        partial = "";
        refreshCurrent();
        send("partial", "text", currentDisplay);          // человек видит полный текст

        tentativeFuture = null;
        tentativeVerdict = null;
        tentativeResolved = true;
        tentativeText = current;
        if (judge != null && judge.ready() && words > 0) {
            // решение по смыслу: просим модель сразу, а паузу «на раздумье» отсчитываем от этого же момента
            try {
                tentativeFuture = judge.decide(interview.judgeInput(currentDisplay));
                tentativeResolved = false;
            } catch (RuntimeException e) {
                tentativeFuture = null;
            }
        }
        tentativeStartSilenceMs = detector.silenceMs();
        if (!tentativeResolved) {
            commitAtSilenceMs = Long.MAX_VALUE;     // решение ждёт заключения модели или конца бюджета времени
            phase = Phase.TENTATIVE;
            return;
        }
        planCommit(interview.peek(current));
        phase = Phase.TENTATIVE;
        if (commitAtSilenceMs <= detector.silenceMs()) commitCurrent(true);
    }

    /** Паузу до принятия решения задаёт само решение: достаточный ответ — коротко, уточнение — дольше. */
    private void planCommit(InterviewSession.Peek peek) {
        tentative = peek;
        int extra = peek.followUp() ? timing.followUpExtraMs()
                : "sufficient".equals(peek.reason()) ? timing.nextExtraMs() : timing.otherNextExtraMs();
        commitAtSilenceMs = tentativeStartSilenceMs + extra;
    }

    /**
     * Готово ли решение к принятию: заключение модели получено, либо вышло время и работают правила.
     * Вызывается из потока аудио, поэтому будущее только опрашивается, а не ждётся.
     */
    private boolean resolveTentative() {
        if (tentativeResolved) return true;
        if (tentativeFuture.isDone()) {
            tentativeVerdict = tentativeFuture.isCompletedExceptionally() ? null : tentativeFuture.getNow(null);
        } else if (detector.silenceMs() - tentativeStartSilenceMs < judgeDeadlineMs) {
            return false;
        }
        tentativeResolved = true;                   // время вышло или модель ответила (в том числе сбоем) — решаем
        planCommit(interview.peek(current, tentativeVerdict));
        return true;
    }

    private void cancelTentative() {
        tentative = null;
        tentativeFuture = null;
        tentativeVerdict = null;
        tentativeResolved = true;
        phase = Phase.LISTENING;
    }

    private void commitCurrent(boolean grace) {
        commit(current, currentDisplay,
                new AnswerTiming(Math.max(0, detector.speechStartMs() - ttsEndMs), detector.speechDurationMs()), grace);
    }

    /** Жёсткий конец: решение принято и отправлено. Дальше — окно отмены. */
    private void commit(String text, String display, AnswerTiming at, boolean grace) {
        long t0 = System.nanoTime();
        // заключение модели годится только для того самого текста, по которому его запрашивали
        Verdict verdict = tentativeResolved && tentativeVerdict != null && text.equals(tentativeText) ? tentativeVerdict : null;
        InterviewSession.Step step = interview.submit(text, display, at, verdict);   // ← горячий путь
        long micros = (System.nanoTime() - t0) / 1000;
        InterviewSession.AttemptRef ref = interview.lastAttempt();
        int committedAnswerId = ++answerId;
        if (ref != null) answerRefs.put(committedAnswerId, ref);
        if (awaitExternalRefinement && !text.isBlank()) pendingRefinements.add(committedAnswerId);
        tentative = null;
        tentativeFuture = null;
        tentativeVerdict = null;

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("type", "decision");
        decision.put("action", step.action().name());
        decision.put("reason", step.reason());
        decision.put("engine", step.engine());
        if (verdict != null) decision.put("judge", judgeInfo(verdict));
        decision.put("coveragePercent", (int) Math.round(step.coverage() * 100));
        decision.put("answerWords", step.analysis().words());
        decision.put("recognized", display);
        decision.put("answerId", committedAnswerId);
        Map<String, Object> timingInfo = new LinkedHashMap<>();
        timingInfo.put("silenceMs", detector.silenceMs());           // сколько ждали тишины до принятия решения
        timingInfo.put("decisionMicros", micros);                    // сколько заняло само решение
        timingInfo.put("serverMs", Math.round((detector.silenceMs() + micros / 1000.0) * 10) / 10.0);
        timingInfo.put("asrRealtimeFactor", Math.round(rtf * 100) / 100.0);
        timingInfo.put("speechStartMs", detector.speechStartMs());
        timingInfo.put("audioMs", detector.audioMs());
        decision.put("timing", timingInfo);
        out.send(decision);

        boolean finish = step.action() == InterviewSession.Action.FINISH;
        boolean withGrace = grace && timing.graceMs() > 0;
        if (finish) pendingFinish = true;
        else out.send(question(step.next(), withGrace ? timing.graceMs() : 0));

        // ---- вне критического пути ----
        // текст получен после flush Vosk, то есть уже финальный: фиксируем как «Vosk», остальное доточит Whisper
        interview.refine(ref, text, display, 1);
        if (!text.isBlank()) startScoring(ref, answerId);
        if (transcriber != null && !text.isBlank())
            startSecondPass(ref, trimAnswerAudio(detector.speechStartMs()),
                    Math.max(0, detector.speechStartMs() - 300), answerId);

        if (withGrace) {
            phase = Phase.GRACE;
            graceStartAudioMs = detector.audioMs();
        } else {
            phase = Phase.WAIT_READY;
            if (finish) finishInterview();
        }
    }

    private static Map<String, Object> judgeInfo(Verdict v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("factsSaid", v.facts().stream().filter(f -> f.p() >= 0.5).count());
        m.put("factsTotal", v.facts().size());
        m.put("ignorance", v.ignorance() == null ? null : Math.round(v.ignorance() * 100) / 100.0);
        m.put("offTopic", v.offTopic() == null ? null : Math.round(v.offTopic() * 100) / 100.0);
        m.put("modelMs", v.latencyMs());
        if (v.stateTruncated()) m.put("stateTruncated", true);
        return m;
    }

    /** Фоновая оценка по критериям для отчёта: вне критического пути, с низким приоритетом у модели. */
    private void startScoring(InterviewSession.AttemptRef ref, int answerId) {
        if (judge == null || !judge.ready()) return;
        final int version = answerVersion;
        try {
            CompletableFuture<Void> f = judge.score(interview.judgeInput(ref)).thenAccept(v -> {
                if (version == answerVersion) interview.applyScoring(ref, v);
            }).exceptionally(e -> {
                out.send(Map.of("type", "judge_error", "answerId", answerId,
                        "message", String.valueOf(e.getCause() == null ? e : e.getCause())));
                return null;
            });
            pending.add(f);
        } catch (RuntimeException e) {
            out.send(Map.of("type", "judge_error", "answerId", answerId, "message", String.valueOf(e.getMessage())));
        }
    }

    private void endGrace() {
        phase = Phase.WAIT_READY;
        if (pendingFinish) finishInterview();
    }

    /** Человек продолжил отвечать в окне отмены: откатываем решение, он договаривает на прежний вопрос. */
    private void retract() {
        answerVersion++;                                   // устаревший результат Whisper игнорируем
        answerRefs.remove(answerId);
        onRefineDone(answerId);
        InterviewSession.Prompt p = interview.undoLast();
        pendingFinish = false;
        phase = Phase.LISTENING;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "retract");
        m.put("reason", "speech_resumed");
        if (p != null) m.put("question", question(p, 0));
        out.send(m);
    }

    private void finishInterview() {
        finished = true;
        phase = Phase.IDLE;
        sendReport();
    }

    // ------------------------------------------------------------------ вспомогательное

    private void appendCommitted(String text) {
        if (text == null || text.isBlank()) return;
        if (committed.length() > 0) committed.append(' ');
        committed.append(text);
    }

    /** Следим, успевает ли распознавание за речью: если нет, текст отстаёт, и решения принимаются по неполному тексту. */
    private void trackRealtime(long asrNanos, int len) {
        double frameMs = len / (double) BYTES_PER_MS;
        Double remote = speech.realtimeFactor();      // удалённый STT сам сообщает, успевает ли он за речью
        double ratio = remote != null ? remote : (asrNanos / 1e6) / frameMs;
        rtf = frames == 0 ? ratio : 0.9 * rtf + 0.1 * ratio;
        frames++;
        long now = System.currentTimeMillis();
        if (frames >= 20 && rtf > 0.85 && now - lastRtfWarn > 30_000) {
            lastRtfWarn = now;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "warning");
            m.put("code", "asr_slow");
            m.put("rtf", Math.round(rtf * 100) / 100.0);
            m.put("message", "Распознавание не успевает за речью (обработка занимает " + Math.round(rtf * 100)
                    + " % времени звука): слова будут теряться или опаздывать. Возьмите лёгкую модель "
                    + "(vosk-model-small-ru-0.22), закройте тяжёлые программы или уменьшите нагрузку на процессор.");
            out.send(m);
        }
    }

    /** Берём аудио ответа: от начала речи (с запасом 300 мс) до конца. */
    private byte[] trimAnswerAudio(long speechStartMs) {
        byte[] all = answerAudio.toByteArray();
        int from = (int) Math.max(0, (speechStartMs - 300) * BYTES_PER_MS);
        from -= from % 2;
        if (from >= all.length || all.length - from < 1000 * BYTES_PER_MS / 2) return null;   // короче 0.5 с — не стоит
        return Arrays.copyOfRange(all, from, all.length);
    }

    private void startSecondPass(InterviewSession.AttemptRef ref, byte[] audio, long trimStartMs, int answerId) {
        if (audio == null) return;
        final int version = answerVersion;
        CompletableFuture<Void> f;
        if (transcriber instanceof DetailedTranscriber whisper) {
            f = whisper.transcribeDetailed(audio).thenAccept(result -> {
                if (version != answerVersion) return;
                if (result.text().isBlank()) {
                    out.send(Map.of("type", "whisper_error", "answerId", answerId,
                            "message", "Whisper вернул пустую расшифровку"));
                    return;
                }
                interview.refine(ref, normalizer.normalize(result.text(), false),
                        normalizer.normalize(result.text(), true), 2);
                startScoring(ref, answerId);       // текст стал точнее — оценки по критериям считаем заново
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("type", "refined");
                event.put("text", normalizer.normalize(result.text(), true));
                event.put("words", result.words());
                event.put("trimStartMs", trimStartMs);
                event.put("answerId", answerId);
                out.send(event);
            }).exceptionally(e -> {
                out.send(Map.of("type", "whisper_error", "answerId", answerId,
                        "message", String.valueOf(e.getCause() == null ? e : e.getCause())));
                return null;
            });
        } else {
            f = transcriber.transcribe(audio).thenAccept(text -> {
                if (text == null || text.isBlank() || version != answerVersion) return;
                interview.refine(ref, normalizer.normalize(text, false), normalizer.normalize(text, true), 2);
                startScoring(ref, answerId);
                send("refined", "text", normalizer.normalize(text, true));
            }).exceptionally(e -> {
                out.send(Map.of("type", "whisper_error", "answerId", answerId,
                        "message", String.valueOf(e.getCause() == null ? e : e.getCause())));
                return null;
            });
        }
        pending.add(f);
    }

    /** Применяет результаты распознавателя строго в порядке поступления: фраза фиксируется, гипотеза заменяется. */
    private void foldResults(List<SpeechEngine.Result> results) {
        for (SpeechEngine.Result r : results) {
            if (r.isFinal()) {
                appendCommitted(r.text());
                partial = "";
            } else {
                partial = r.text();
            }
        }
        refreshCurrent();
    }

    private void refreshCurrent() {
        String raw;
        if (committed.length() == 0) {
            raw = partial;
        } else {
            raw = partial.isBlank() ? committed.toString() : committed + " " + partial;
        }
        current = normalizer.normalize(raw, false);
        currentDisplay = normalizer.normalize(raw, true);
        words = Connectors.countWords(current);
        connector = Connectors.endsWithConnector(current);
    }

    private void sendReport() {
        if (transcriber != null && pendingRefinements.isEmpty())
            send("finalizing", "message", "Собираю итоговый отчёт после уточнения распознавания.");
        if (!pendingRefinements.isEmpty()) {
            send("finalizing", "message", "Жду уточнения распознанного текста…");
            CompletableFuture.runAsync(() -> {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(pendingWaitMs);
                synchronized (refinementLock) {
                    while (!pendingRefinements.isEmpty() && System.nanoTime() < deadline) {
                        long remaining = deadline - System.nanoTime();
                        try { TimeUnit.NANOSECONDS.timedWait(refinementLock, remaining); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                    }
                }
                sendReportNow();
            });
            return;
        }
        sendReportNow();
    }

    private void sendReportNow() {
        if (!pending.isEmpty()) {
            try {
                CompletableFuture.allOf(pending.toArray(new CompletableFuture<?>[0])).get(pendingWaitMs, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // Фоновая оценка не должна блокировать выдачу отчёта.
            }
        }
        InterviewDetails details = interview.details(candidate, startedAt, settings);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "report");
        m.put("report", details.report());      // эталонные ответы в браузер не уходят — только оценки
        if (exporter != null) {
            try {
                ReportExporter.Result r = exporter.export(details);
                Map<String, Object> excel = new LinkedHashMap<>();
                excel.put("file", r.fileName());
                excel.put("path", r.path());
                excel.put("url", r.downloadUrl());
                m.put("excel", excel);
            } catch (Exception e) {
                m.put("excelError", String.valueOf(e.getMessage()));
            }
        }
        out.send(m);
    }

    private void send(String type, String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put(key, value);
        out.send(m);
    }

    private Map<String, Object> question(InterviewSession.Prompt p, int graceMs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "question");
        m.put("number", p.number());
        m.put("total", p.total());
        m.put("level", p.level());
        m.put("topic", p.topic());
        m.put("text", p.text());
        m.put("spoken", spoken.speak(p.text()));      // что реально читать вслух: «HashMap» → «хэшмэп»
        m.put("followUp", p.followUp());
        m.put("followUpNumber", p.followUpNumber());
        m.put("graceMs", graceMs);                    // клиент показывает и озвучивает вопрос только после этого окна
        return m;
    }
}
