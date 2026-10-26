package ru.sttplatform.analytics.live;

import com.fasterxml.jackson.databind.JsonNode;
import ru.sttplatform.common.ws.WsPeer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Потоковый Vosk как удалённый сервис: одно WebSocket-соединение {@code /ws/stt} на интервью.
 * Результаты приходят асинхронно и выдаются из {@link #accept}; {@link #finish()} отправляет команду
 * {@code flush} и ждёт подтверждения — так же «дожимается» распознавание перед решением, как раньше локально.
 *
 * @version 1.0.0
 */
public final class RemoteSpeechEngine implements SpeechEngine {
    private final WsPeer peer;
    private final long flushTimeoutMs;
    private final Consumer<String> onFailure;
    private final ConcurrentLinkedQueue<Result> inbox = new ConcurrentLinkedQueue<>();
    private volatile CountDownLatch flushLatch;
    private volatile Double rtf;
    private volatile boolean failed;
    private volatile boolean closing;

    private RemoteSpeechEngine(String sttWsUrl, long flushTimeoutMs, Consumer<String> onFailure) {
        this.flushTimeoutMs = flushTimeoutMs;
        this.onFailure = onFailure;
        this.peer = WsPeer.connect(sttWsUrl + "/ws/stt", this::onMessage, this::onClosed);
    }

    /**
     * Подключается к stt-service.
     *
     * @param sttBaseUrl адрес stt-service, {@code http://…} или {@code ws://…}
     * @param flushTimeoutMs сколько ждать подтверждения {@code flush}
     * @param onFailure вызывается один раз при потере связи со STT
     * @return готовый движок
     */
    public static RemoteSpeechEngine connect(String sttBaseUrl, long flushTimeoutMs, Consumer<String> onFailure) {
        String ws = sttBaseUrl.replaceAll("/+$", "").replaceFirst("^http", "ws");
        return new RemoteSpeechEngine(ws, flushTimeoutMs, onFailure);
    }

    private void onMessage(JsonNode message) {
        String type = message.path("type").asText();
        switch (type) {
            case "partial" -> inbox.add(new Result(false, message.path("text").asText("")));
            case "final" -> {
                boolean flush = message.path("flush").asBoolean(false);
                String text = message.path("text").asText("");
                if (flush || !text.isBlank()) inbox.add(new Result(true, text));
                if (flush) {
                    CountDownLatch latch = flushLatch;
                    if (latch != null) latch.countDown();
                }
            }
            case "stats" -> rtf = message.path("rtf").asDouble();
            default -> { }
        }
    }

    private void onClosed() {
        if (failed || closing) return;     // штатное закрытие — не авария
        failed = true;
        CountDownLatch latch = flushLatch;
        if (latch != null) latch.countDown();
        if (onFailure != null) onFailure.accept("Потеряна связь с сервисом распознавания речи (stt-service).");
    }

    @Override
    public List<Result> accept(byte[] pcm, int len) {
        if (failed || !peer.isOpen()) throw new IllegalStateException("stt-service недоступен.");
        peer.sendAudio(len == pcm.length ? pcm : Arrays.copyOf(pcm, len));
        return drain();
    }

    @Override
    public List<Result> finish() {
        if (failed || !peer.isOpen()) throw new IllegalStateException("stt-service недоступен.");
        CountDownLatch latch = new CountDownLatch(1);
        flushLatch = latch;
        peer.sendText("flush");
        try {
            if (!latch.await(flushTimeoutMs, TimeUnit.MILLISECONDS) && onFailure != null)
                onFailure.accept("stt-service не подтвердил сброс распознавателя за " + flushTimeoutMs + " мс.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            flushLatch = null;
        }
        return drain();
    }

    private List<Result> drain() {
        List<Result> out = new ArrayList<>();
        for (Result r; (r = inbox.poll()) != null; ) out.add(r);
        return out;
    }

    @Override
    public Double realtimeFactor() {
        return rtf;
    }

    @Override
    public void close() {
        closing = true;
        try {
            if (peer.isOpen()) peer.sendClose(1000, "done");
        } catch (RuntimeException ignored) {
            // соединение уже закрыто
        }
    }
}
