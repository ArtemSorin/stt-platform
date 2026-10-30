package ru.sttplatform.diarization.session;

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.sttplatform.common.audio.Pcm;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.diarization.audio.AudioChunk;
import ru.sttplatform.diarization.audio.AudioFrames;
import ru.sttplatform.diarization.config.DiarizationProperties;
import ru.sttplatform.diarization.nemotron.NemotronStream;
import ru.sttplatform.diarization.roles.Embeddings;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Одно интервью в сервисе диаризации: эталон голоса кандидата → непрерывная сессия Nemotron + роли CAM++.
 * Фазы: {@code enrolling → preparing → (retry | live) → stopping → finished | error}.
 * Результаты по мере обработки уходят подписчику событиями {@code diarization} и {@code finished}.
 *
 * @version 1.0.0
 */
public final class DiarizationSession {
    private static final Logger log = LoggerFactory.getLogger(DiarizationSession.class);
    /** Максимум сэмплов в одном запросе аудио (0,64 с). */
    private static final int CHUNK_SAMPLES = 10240;
    /** Сколько 160-мс кадров склеивается в блок Nemotron. */
    private static final int FRAMES_PER_NEMOTRON_BLOCK = 4;

    private final DiarizationProperties props;
    private final String id = UUID.randomUUID().toString();
    private final SpeakerEmbeddingExtractor extractor;
    private final NemotronStream diarizer;
    private final long startupAt = System.nanoTime();
    private final ByteArrayOutputStream reference = new ByteArrayOutputStream();
    private final ArrayBlockingQueue<AudioChunk> queue;
    private final Path outputDir;
    private volatile Consumer<Map<String, Object>> listener;
    private volatile String phase = "enrolling", role = "Ожидаем речь", error;
    private volatile double audioSeconds;
    private volatile long lastContact = System.nanoTime();
    private long enrollmentSequence, liveSequence, acceptedSamples, droppedSamples, droppedChunks, overflowCount, maxDepth;
    private String stopReason = "running";
    private LiveSession session;
    private Thread worker;
    private boolean stopRequested, resourcesClosed;

    /**
     * Загружает модели Nemotron и CAM++ (один раз на сессию) и создаёт каталог результатов.
     *
     * @param props настройки сервиса
     * @throws Exception если модели не найдены или не загрузились
     */
    public DiarizationSession(DiarizationProperties props) throws Exception {
        this.props = props;
        this.queue = new ArrayBlockingQueue<>(props.queueChunks());
        Path root = DiarizationProperties.path(props.outputDir(), "output-dir");
        outputDir = root.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-" + id.substring(0, 8));
        Files.createDirectories(root);
        diarizer = new NemotronStream(DiarizationProperties.path(props.nemoLib(), "nemo-lib"),
                DiarizationProperties.path(props.nemoModel(), "nemo-model"), props.preset());
        try {
            extractor = Embeddings.extractor(DiarizationProperties.path(props.speakerModel(), "speaker-model"),
                    props.numThreads());
        } catch (Throwable failure) {
            diarizer.close();
            throw failure;
        }
        log.info("Новая сессия диаризации {}; модели загружены один раз.", id);
    }

    /**
     * Идентификатор сессии.
     *
     * @return UUID
     */
    public String id() {
        return id;
    }

    /**
     * Подписывает получателя событий {@code diarization}/{@code finished}.
     *
     * @param consumer получатель или {@code null}
     */
    public void setListener(Consumer<Map<String, Object>> consumer) {
        this.listener = consumer;
    }

    /**
     * Завершена ли сессия (успешно или с ошибкой).
     *
     * @return {@code true} для фаз finished и error
     */
    public synchronized boolean terminal() {
        return phase.equals("finished") || phase.equals("error");
    }

    /**
     * Можно ли записать эталон заново.
     *
     * @return {@code true} в фазе retry
     */
    public synchronized boolean retryable() {
        return phase.equals("retry");
    }

    /**
     * Текущая фаза.
     *
     * @return имя фазы
     */
    public String phase() {
        return phase;
    }

    /**
     * Состояние сессии.
     *
     * @param token идентификатор, присланный клиентом; совпадение продлевает «жизнь» сессии
     * @return карта для JSON
     */
    public synchronized Map<String, Object> status(String token) {
        if (id.equals(token)) lastContact = System.nanoTime();
        boolean done = phase.equals("finished") || phase.equals("error");
        return Json.map("session_id", id, "phase", phase, "role", role,
                "audio_seconds", Json.round(audioSeconds, 3),
                "enrollment_seconds", Json.round(reference.size() / (2.0 * Pcm.RATE), 3),
                "queue_depth_chunks", queue.size(), "error", error,
                "output_dir", done ? outputDir.toString() : null,
                "capture", Json.map("capacity_chunks", props.queueChunks(), "max_depth_chunks", maxDepth,
                        "overflow_count", overflowCount, "dropped_chunks", droppedChunks,
                        "dropped_audio_seconds", Json.round(droppedSamples / (double) Pcm.RATE, 3),
                        "stop_reason", stopReason));
    }

    /**
     * Все подтверждённые сегменты ролей.
     *
     * @return копия списка сегментов
     */
    public synchronized List<Map<String, Object>> segments() {
        return session == null ? List.of() : session.allSegments();
    }

    /**
     * Принимает кусок аудио эталона.
     *
     * @param sequence ожидаемый порядковый номер блока
     * @param bytes PCM16 16 кГц моно
     */
    public synchronized void enrollAudio(long sequence, byte[] bytes) {
        validate(bytes);
        if (!phase.equals("enrolling")) throw new SessionException(409, "Запись эталона сейчас недоступна.");
        if (sequence != enrollmentSequence) throw new SessionException(409, "Нарушен порядок чанков эталона.");
        if (reference.size() + bytes.length > 30L * Pcm.RATE * 2) throw new SessionException(413, "Эталон длиннее 30 секунд.");
        reference.writeBytes(bytes);
        enrollmentSequence++;
        lastContact = System.nanoTime();
    }

    /**
     * Строит голосовой эталон и, если он получился, запускает обработку интервью.
     *
     * @param separateChannels в диаризацию поступает только системный канал
     * @throws Exception если не удалось создать файлы результата
     */
    public synchronized void finishEnrollment(boolean separateChannels) throws Exception {
        if (!phase.equals("enrolling")) throw new SessionException(409, "Эталон уже завершён.");
        phase = "preparing";
        float[] voice = Pcm.toFloats(reference.toByteArray());
        double seconds = voice.length / (double) Pcm.RATE;
        float[] candidate = Embeddings.embedding(extractor, voice, 0, seconds, 0);
        if (candidate == null) {
            phase = "retry";
            error = "Не удалось получить голосовой эталон. Говорите без длинных пауз около 10 секунд и повторите запись.";
            return;
        }
        try {
            session = new LiveSession(diarizer, extractor, candidate, props.threshold(), outputDir,
                    separateChannels ? "browser_system_audio" : "browser_mixed_microphone_system_audio",
                    separateChannels ? "browser_separate_channels" : "browser_mixed_stream",
                    "browser_system_audio", separateChannels ? "browser system audio" : "browser microphone + system audio",
                    Pcm.RATE, 1, seconds, .64, props.settleSeconds(), (System.nanoTime() - startupAt) / 1e9,
                    props.preset(), metrics());
            error = null;
            phase = "live";
            worker = new Thread(this::consume, "nemotron-" + id.substring(0, 8));
            worker.start();
        } catch (Exception failure) {
            phase = "error";
            error = failure.toString();
            dispose();
            throw failure;
        }
    }

    /**
     * Принимает кадр интервью: системный канал или прежний смешанный сигнал.
     *
     * @param bytes PCM16 16 кГц моно
     */
    public synchronized void liveAudio(byte[] bytes) {
        validate(bytes);
        if (!phase.equals("live")) throw new SessionException(409, "Анализ аудио сейчас недоступен.");
        float[] chunk = Pcm.toFloats(bytes);
        AudioChunk item = new AudioChunk(liveSequence, acceptedSamples, chunk, System.nanoTime());
        liveSequence++;
        lastContact = System.nanoTime();
        // Пропуск кадра нарушил бы временную шкалу одной сессии Nemotron — поэтому при переполнении сессия останавливается.
        if (!queue.offer(item)) {
            overflowCount++;
            droppedChunks++;
            droppedSamples += chunk.length;
            error = "Очередь аудио переполнена; интервью остановлено. Результаты сохранены.";
            stopReason = "queue_overflow";
            stopRequested = true;
            phase = "stopping";
            notifyAll();
            throw new SessionException(409, error);
        }
        acceptedSamples += chunk.length;
        maxDepth = Math.max(maxDepth, queue.size());
    }

    /**
     * Запрашивает остановку: уже принятые кадры будут обработаны, результаты сохранены.
     *
     * @param reason requested | system_audio_lost | microphone_lost | capture_error | client_disconnected | …
     */
    public synchronized void requestStop(String reason) {
        if (phase.equals("live")) phase = "stopping";
        if (phase.equals("enrolling") || phase.equals("retry")) {
            phase = "finished";
            dispose();
            return;
        }
        if (phase.equals("preparing")) return;
        if (reason.equals("system_audio_lost") || reason.equals("microphone_lost") || reason.equals("capture_error"))
            error = "Источник аудио прервался; обработанная часть интервью сохранена.";
        if (!stopRequested) {
            stopRequested = true;
            stopReason = reason;
        }
        notifyAll();
    }

    /** Сторож: если клиент 15 секунд не выходил на связь, интервью останавливается. */
    public synchronized void watchdog() {
        if (terminal()) return;
        if ((System.nanoTime() - lastContact) / 1e9 < 15) return;
        if (phase.equals("live")) {
            error = "Вкладка или микрофон перестали передавать звук; интервью остановлено.";
            requestStop("client_disconnected");
        } else if (phase.equals("enrolling") || phase.equals("retry")) {
            phase = "finished";
            dispose();
        }
    }

    /** Освобождает модели и файлы; безопасно вызывать повторно. */
    public synchronized void dispose() {
        if (resourcesClosed) return;
        if (worker != null && worker.isAlive() && Thread.currentThread() != worker) return;
        resourcesClosed = true;
        try {
            if (session != null) session.close();
        } catch (Exception failure) {
            log.warn("Закрытие сессии: {}", failure.toString());
        }
        extractor.release();
        diarizer.close();
    }

    private static void validate(byte[] bytes) {
        if (bytes.length < 2 || bytes.length > 2 * CHUNK_SAMPLES || bytes.length % 2 != 0)
            throw new SessionException(400, "Ожидается PCM16 16 кГц, до 0,64 с на источник.");
    }

    private synchronized LiveSession.Capture metrics() {
        return new LiveSession.Capture(acceptedSamples + droppedSamples, props.queueChunks(), maxDepth,
                overflowCount, droppedChunks, droppedSamples, stopReason, error);
    }

    private void emit(Map<String, Object> event) {
        Consumer<Map<String, Object>> target = listener;
        if (target == null) return;
        try {
            target.accept(event);
        } catch (RuntimeException failure) {
            log.debug("Подписчик событий недоступен: {}", failure.toString());
        }
    }

    private void consume() {
        try {
            List<AudioChunk> batch = new ArrayList<>(FRAMES_PER_NEMOTRON_BLOCK);
            long modelChunkIndex = 0;
            while (true) {
                AudioChunk chunk = queue.poll(200, TimeUnit.MILLISECONDS);
                if (chunk != null) {
                    batch.add(chunk);
                    if (batch.size() >= FRAMES_PER_NEMOTRON_BLOCK) {
                        processBlock(AudioFrames.mergeForNemotron(batch, modelChunkIndex++));
                        batch.clear();
                    }
                }
                boolean shouldFinish;
                synchronized (this) {
                    shouldFinish = stopRequested && queue.isEmpty();
                }
                if (shouldFinish) {
                    if (!batch.isEmpty()) {
                        processBlock(AudioFrames.mergeForNemotron(batch, modelChunkIndex++));
                        batch.clear();
                    }
                    break;
                }
            }
            session.finish(metrics(), overflowCount == 0 && error == null);
            synchronized (this) {
                phase = error == null ? "finished" : "error";
            }
            log.info("Файлы результата: {}", outputDir);
        } catch (Throwable failure) {
            error = failure.toString();
            phase = "error";
            log.error("Ошибка обработки интервью", failure);
            try {
                session.finish(metrics(), false);
            } catch (Exception finalError) {
                log.warn("Не удалось сохранить итог: {}", finalError.toString());
            }
        } finally {
            emit(Json.map("type", "finished", "phase", phase, "error", error, "audio_seconds", Json.round(audioSeconds, 3)));
            dispose();
        }
    }

    private void processBlock(AudioChunk merged) throws Exception {
        LiveSession.Update update = session.process(merged, queue.size(), metrics());
        role = update.role();
        audioSeconds = update.audioSeconds();
        emit(Json.map("type", "diarization", "role", role, "audio_seconds", update.audioSeconds(),
                "segments", update.segments(), "active_segments", update.activeSegments()));
    }
}
