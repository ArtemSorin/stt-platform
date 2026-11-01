package ru.sttplatform.common.ws;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WsPeerTest {
    @Test
    void abort_does_not_wait_for_a_stalled_audio_send() throws Exception {
        CompletableFuture<WebSocket> pending = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger aborts = new AtomicInteger();
        WebSocket socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
                new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
                    if (method.getName().equals("sendBinary")) { started.countDown(); return pending; }
                    if (method.getName().equals("abort")) {
                        aborts.incrementAndGet();
                        pending.completeExceptionally(new IllegalStateException("aborted"));
                    }
                    return null;
                });
        WsPeer peer = new WsPeer(ignored -> {});
        peer.attach(socket);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            var send = worker.submit(() -> peer.sendAudio(new byte[]{1, 2}));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            peer.abort();
            assertEquals(1, aborts.get());
            org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> send.get(1, TimeUnit.SECONDS));
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void notifies_owner_when_remote_websocket_closes() {
        AtomicInteger closed = new AtomicInteger();
        WebSocket socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
                new Class<?>[]{WebSocket.class}, (proxy, method, args) -> null);
        WsPeer peer = new WsPeer(ignored -> {}, closed::incrementAndGet);
        peer.attach(socket);

        peer.onClose(socket, 1001, "service stopped");

        assertEquals(1, closed.get());
    }

    @Test
    void serializes_concurrent_audio_and_text_sends_on_one_websocket() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        AtomicInteger sends = new AtomicInteger();
        AtomicReference<WebSocket> socketRef = new AtomicReference<>();
        ScheduledExecutorService completions = Executors.newSingleThreadScheduledExecutor();
        ExecutorService callers = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(40);
        try {
            WebSocket socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
                    new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
                        if (method.getName().equals("sendText") || method.getName().equals("sendBinary")) {
                            int active = inFlight.incrementAndGet();
                            maxInFlight.accumulateAndGet(active, Math::max);
                            sends.incrementAndGet();
                            CompletableFuture<WebSocket> sent = new CompletableFuture<>();
                            if (active > 1) {
                                inFlight.decrementAndGet();
                                sent.completeExceptionally(new IllegalStateException("Send pending"));
                            } else {
                                completions.schedule(() -> {
                                    inFlight.decrementAndGet();
                                    sent.complete(socketRef.get());
                                    ready.countDown();
                                }, 3, TimeUnit.MILLISECONDS);
                            }
                            return sent;
                        }
                        return null;
                    });
            socketRef.set(socket);
            WsPeer peer = new WsPeer(ignored -> {});
            peer.attach(socket);

            for (int i = 0; i < 40; i++) {
                final int index = i;
                callers.submit(() -> {
                    if ((index & 1) == 0) peer.sendAudio(new byte[]{1, 2});
                    else peer.sendText("peek-" + index);
                });
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS), "All queued WebSocket writes should complete");
            assertEquals(40, sends.get());
            assertEquals(1, maxInFlight.get(), "A WebSocket must have at most one pending send");
        } finally {
            callers.shutdownNow();
            completions.shutdownNow();
        }
    }
}
