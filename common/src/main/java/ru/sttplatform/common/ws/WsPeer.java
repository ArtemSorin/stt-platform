package ru.sttplatform.common.ws;

import com.fasterxml.jackson.databind.JsonNode;
import ru.sttplatform.common.json.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.Map;

/**
 * Клиентская сторона WebSocket между микросервисами. Принимает JSON-сообщения по частям,
 * а отправку сериализует: JDK WebSocket допускает только одну незавершённую отправку.
 *
 * @version 1.0.0
 */
public final class WsPeer implements WebSocket.Listener {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final Consumer<JsonNode> handler;
    private final Runnable onClosed;
    private final StringBuilder text = new StringBuilder();
    private final CountDownLatch closed = new CountDownLatch(1);
    private final Object sendLock = new Object();
    private volatile WebSocket socket;
    private volatile Throwable failure;

    /**
     * Создаёт слушателя без подключения (сокет присоединяется через {@link #attach}).
     *
     * @param handler обработчик входящих JSON-сообщений
     */
    public WsPeer(Consumer<JsonNode> handler) {
        this(handler, null);
    }

    /**
     * Создаёт слушателя без подключения.
     *
     * @param handler обработчик входящих JSON-сообщений
     * @param onClosed вызывается при закрытии или ошибке соединения (может быть {@code null})
     */
    public WsPeer(Consumer<JsonNode> handler, Runnable onClosed) {
        this.handler = handler;
        this.onClosed = onClosed;
    }

    /**
     * Подключается к WebSocket-серверу другого сервиса.
     *
     * @param url адрес вида {@code ws://host:port/path}
     * @param handler обработчик входящих JSON-сообщений
     * @param onClosed вызывается при закрытии или ошибке соединения (может быть {@code null})
     * @return подключённый клиент
     */
    public static WsPeer connect(String url, Consumer<JsonNode> handler, Runnable onClosed) {
        return connect(url, Map.of(), handler, onClosed);
    }

    /** Подключается к WebSocket с заголовками HTTP handshake.
     * @param url адрес WebSocket-сервера
     * @param headers заголовки соединения, например токен внутреннего канала
     * @param handler обработчик входящих JSON-сообщений
     * @param onClosed вызывается при закрытии или ошибке соединения; может быть {@code null}
     * @return подключённый клиент
     */
    public static WsPeer connect(String url, Map<String, String> headers,
                                 Consumer<JsonNode> handler, Runnable onClosed) {
        WsPeer peer = new WsPeer(handler, onClosed);
        var builder = CLIENT.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
        headers.forEach(builder::header);
        peer.attach(builder.buildAsync(URI.create(url), peer).join());
        return peer;
    }

    /**
     * Привязывает открытый сокет.
     *
     * @param socket сокет JDK
     */
    public void attach(WebSocket socket) {
        this.socket = socket;
    }

    /**
     * Отправляет бинарный блок и ждёт завершения отправки.
     *
     * @param bytes PCM-данные
     */
    public void sendAudio(byte[] bytes) {
        synchronized (sendLock) {
            socket.sendBinary(ByteBuffer.wrap(bytes), true).join();
        }
    }

    /**
     * Отправляет текстовое сообщение и ждёт завершения отправки.
     *
     * @param message текст
     */
    public void sendText(String message) {
        synchronized (sendLock) {
            socket.sendText(message, true).join();
        }
    }

    /**
     * Отправляет JSON-объект как текстовое сообщение.
     *
     * @param value сериализуемый объект
     */
    public void sendJson(Object value) {
        sendText(Json.write(value));
    }

    /**
     * Закрывает соединение штатным кодом.
     *
     * @param status код закрытия
     * @param reason причина
     */
    public void sendClose(int status, String reason) {
        synchronized (sendLock) {
            socket.sendClose(status, reason).join();
        }
    }

    /** Прерывает соединение, не ожидая зависшую отправку.
     * Используется, когда отставание необязательного потока нельзя задерживать.
     */
    public void abort() {
        WebSocket current = socket;
        if (current != null) current.abort();
    }

    /**
     * Ждёт закрытия соединения.
     *
     * @param seconds максимум секунд ожидания
     * @return {@code true}, если соединение закрылось
     * @throws InterruptedException если поток прерван
     */
    public boolean awaitClosed(int seconds) throws InterruptedException {
        return closed.await(seconds, TimeUnit.SECONDS);
    }

    /**
     * Причина обрыва соединения.
     *
     * @return ошибка транспорта или {@code null}
     */
    public Throwable failure() {
        return failure;
    }

    /**
     * Открыто ли соединение для отправки.
     *
     * @return {@code true}, пока сокет не закрыт и не сломан
     */
    public boolean isOpen() {
        WebSocket current = socket;
        return current != null && closed.getCount() > 0 && !current.isOutputClosed();
    }

    @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }

    @Override public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        closed.countDown();
        if (onClosed != null) onClosed.run();
        return null;
    }

    @Override public void onError(WebSocket webSocket, Throwable error) {
        failure = error;
        closed.countDown();
        if (onClosed != null) onClosed.run();
    }

    @Override public CompletableFuture<?> onText(WebSocket webSocket, CharSequence part, boolean last) {
        text.append(part);
        if (last) {
            try {
                handler.accept(Json.tree(text.toString()));
            } catch (Exception error) {
                System.err.println("Ошибка обработки сообщения WebSocket: " + error);
            }
            text.setLength(0);
        }
        webSocket.request(1);
        return null;
    }
}
