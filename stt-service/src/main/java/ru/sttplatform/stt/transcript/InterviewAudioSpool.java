package ru.sttplatform.stt.transcript;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Временная синхронная запись каналов PCM16, не блокирующая поток захвата диском. */
public final class InterviewAudioSpool implements AutoCloseable {
    private record Frame(long first, byte[] system, byte[] microphone) {}
    private static final Frame END = new Frame(-1, new byte[0], null);
    private final ArrayBlockingQueue<Frame> queue;
    private final Path path;
    private final boolean stereo;
    @FunctionalInterface interface OutputFactory { OutputStream open(Path path) throws IOException; }
    private final OutputFactory outputFactory;
    private final Thread writer;
    private volatile IOException failure;
    private volatile long writtenSamples;
    private long acceptedSamples;
    private boolean sealed;

    /** Создаёт запись одного или двух каналов.
     * @param directory каталог временных записей
     * @param sessionId идентификатор сессии для имени файла
     * @param stereo записывать пару системного и микрофонного каналов
     * @throws IOException если временный файл создать нельзя
     */
    public InterviewAudioSpool(Path directory, String sessionId, boolean stereo) throws IOException {
        this(directory, sessionId, stereo, 128);
    }

    InterviewAudioSpool(Path directory, String sessionId, boolean stereo, int capacity) throws IOException {
        this(directory, sessionId, stereo, capacity, Files::newOutputStream);
    }

    InterviewAudioSpool(Path directory, String sessionId, boolean stereo, int capacity, OutputFactory outputFactory) throws IOException {
        Files.createDirectories(directory);
        path = Files.createTempFile(directory, "interview-" + sessionId + "-", ".pcm.tmp");
        this.stereo = stereo;
        this.outputFactory = outputFactory;
        queue = new ArrayBlockingQueue<>(capacity);
        writer = new Thread(this::writeFrames, "audio-spool-" + sessionId.substring(0, Math.min(8, sessionId.length())));
        writer.setDaemon(true);
        writer.start();
    }

    /** Ставит последовательный кадр в ограниченную очередь.
     * @param firstSample позиция первого сэмпла каждого канала
     * @param system моно PCM16 системного канала или тестового микрофона
     * @param microphone моно PCM16 микрофона при двух каналах
     */
    public synchronized void append(long firstSample, byte[] system, byte[] microphone) {
        if (sealed || failure != null) return;
        if (firstSample != acceptedSamples || system.length % 2 != 0
                || (stereo && (microphone == null || microphone.length != system.length))
                || (!stereo && microphone != null)) {
            failure = new IOException("Разрыв или неверный формат записи аудио");
            return;
        }
        if (!queue.offer(new Frame(firstSample, system, microphone))) {
            failure = new IOException("Очередь записи аудио переполнена");
            return;
        }
        acceptedSamples += system.length / 2L;
    }

    private void writeFrames() {
        try (OutputStream output = new BufferedOutputStream(outputFactory.open(path))) {
            while (true) {
                Frame frame = queue.take();
                if (frame == END) break;
                if (frame.first() != writtenSamples) throw new IOException("Разрыв записи аудио");
                if (!stereo) output.write(frame.system());
                else {
                    byte[] pair = new byte[frame.system().length * 2];
                    for (int from = 0, to = 0; from < frame.system().length; from += 2, to += 4) {
                        pair[to] = frame.system()[from]; pair[to + 1] = frame.system()[from + 1];
                        pair[to + 2] = frame.microphone()[from]; pair[to + 3] = frame.microphone()[from + 1];
                    }
                    output.write(pair);
                }
                writtenSamples += frame.system().length / 2L;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (failure == null) failure = new IOException("Запись аудио прервана", e);
        } catch (IOException e) {
            failure = e;
        }
    }

    /** Дожидается записи всех принятых кадров и проверяет общую шкалу.
     * @param expectedSamples количество принятых сэмплов канала
     * @throws IOException если запись неполная
     * @throws InterruptedException если ожидание прервано
     */
    public synchronized void seal(long expectedSamples) throws IOException, InterruptedException {
        if (!sealed) {
            sealed = true;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (writer.isAlive() && !queue.offer(END, 1, TimeUnit.SECONDS)) {
                if (System.nanoTime() > deadline) { writer.interrupt(); throw new IOException("Очередь записи не завершилась"); }
            }
            writer.join(30_000);
            if (writer.isAlive()) { writer.interrupt(); throw new IOException("Запись аудио не завершилась"); }
        }
        if (failure != null) throw failure;
        if (acceptedSamples != expectedSamples || writtenSamples != expectedSamples)
            throw new IOException("Исходная запись аудио неполная");
    }

    /** Читает выбранный канал после остановки записи.
     * @param channel 0 — система (или тестовый микрофон), 1 — микрофон
     * @param start первый сэмпл включительно
     * @param end последний сэмпл исключительно
     * @return PCM16 одного канала
     * @throws IOException при ошибке чтения или неверном интервале
     */
    public byte[] read(int channel, long start, long end) throws IOException {
        if (!sealed || failure != null || channel < 0 || channel >= (stereo ? 2 : 1)
                || start < 0 || end < start || end > writtenSamples)
            throw new IOException("Неверный интервал исходного аудио");
        int samples = Math.toIntExact(end - start);
        byte[] result = new byte[samples * 2];
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            file.seek(start * (stereo ? 4 : 2));
            if (!stereo) file.readFully(result);
            else {
                byte[] pair = new byte[samples * 4];
                file.readFully(pair);
                for (int i = 0; i < samples; i++) {
                    result[i * 2] = pair[i * 4 + channel * 2];
                    result[i * 2 + 1] = pair[i * 4 + channel * 2 + 1];
                }
            }
        }
        return result;
    }

    /** @return число сэмплов в каждом канале после закрытия */
    public long samples() { return writtenSamples; }

    /** @return число записанных каналов */
    public int channels() { return stereo ? 2 : 1; }

    /** @return путь временной записи */
    public Path path() { return path; }

    /** Удаляет временную запись после публикации.
     * @throws IOException если файл удалить не удалось
     */
    public void delete() throws IOException { Files.deleteIfExists(path); }

    @Override public void close() {
        if (writer.isAlive()) { writer.interrupt(); try { writer.join(2_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    }
}
