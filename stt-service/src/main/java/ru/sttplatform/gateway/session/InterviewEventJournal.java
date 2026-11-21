package ru.sttplatform.gateway.session;

import ru.sttplatform.common.json.Json;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Последовательная асинхронная запись событий одной сессии в JSONL. */
public final class InterviewEventJournal implements AutoCloseable {
    private static final String END = new String("end");
    private static final int MAX_EVENT_BYTES = 32 * 1024;
    private final String sessionId;
    private final ArrayBlockingQueue<String> queue;
    private final Thread writer;
    private volatile IOException failure;
    private long sequence;
    private boolean closed;

    /** Создаёт новый журнал; существующий журнал не перезаписывается.
     * @param path путь нового файла JSONL
     * @param sessionId идентификатор сессии
     * @param capacity максимальное число ожидающих записи событий
     * @throws IOException если файл не удалось создать */
    public InterviewEventJournal(Path path, String sessionId, int capacity) throws IOException {
        if (sessionId == null || sessionId.isBlank() || capacity < 1) throw new IllegalArgumentException("Неверные параметры журнала");
        this.sessionId = sessionId;
        this.queue = new ArrayBlockingQueue<>(capacity);
        BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        writer = new Thread(() -> writeLoop(out), "interview-journal");
        writer.setDaemon(true);
        writer.start();
    }

    /** Фиксирует номер и снимок события в порядке вызовов; диск не блокирует отправителя.
     * @param type тип события
     * @param audioSample позиция на общей шкале сэмплов
     * @param fields дополнительные поля события
     * @throws IOException если очередь переполнена, журнал закрыт или запись не удалась */
    public synchronized void append(String type, long audioSample, Map<String, ?> fields) throws IOException {
        checkFailure();
        if (closed) throw new IOException("Журнал закрыт");
        if (type == null || type.isBlank() || audioSample < 0) throw new IllegalArgumentException("Неверное событие журнала");
        Map<String, Object> event = new LinkedHashMap<>();
        if (fields != null) event.putAll(fields);
        event.put("sessionId", sessionId);
        event.put("eventSequence", sequence);
        event.put("type", type);
        event.put("audioSample", audioSample);
        String json = Json.MAPPER.writeValueAsString(event);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_EVENT_BYTES)
            throw new IOException("Событие журнала превышает допустимый размер");
        if (!queue.offer(json)) {
            failure = new IOException("Очередь журнала переполнена");
            throw failure;
        }
        sequence++;
    }

    private void writeLoop(BufferedWriter out) {
        try (out) {
            while (true) {
                String line = queue.take();
                if (line == END) break;
                out.write(line);
                out.newLine();
                out.flush();
            }
            out.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure = new IOException("Запись журнала прервана", e);
        } catch (IOException e) {
            failure = e;
        }
    }

    private void checkFailure() throws IOException {
        if (failure != null) throw new IOException("Ошибка журнала интервью", failure);
    }

    /** Закрывает очередь, дожидается записи и сообщает об ошибке.
     * @throws IOException если запись или ожидание завершились ошибкой */
    @Override public synchronized void close() throws IOException {
        if (closed) { checkFailure(); return; }
        closed = true;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean submitted = false;
            while (writer.isAlive() && System.nanoTime() < deadline) {
                if (queue.offer(END, 100, TimeUnit.MILLISECONDS)) { submitted = true; break; }
            }
            if (!submitted && writer.isAlive()) throw new IOException("Очередь журнала не закрылась за 10 секунд");
            long remaining = deadline - System.nanoTime();
            if (remaining > 0 && writer.isAlive()) TimeUnit.NANOSECONDS.timedJoin(writer, remaining);
            if (writer.isAlive()) throw new IOException("Запись журнала не завершилась за 10 секунд");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Ожидание журнала прервано", e);
        }
        checkFailure();
    }
}
