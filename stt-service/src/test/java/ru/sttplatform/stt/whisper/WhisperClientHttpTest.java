package ru.sttplatform.stt.whisper;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.sttplatform.stt.config.WhisperProperties;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** HTTP-клиент Whisper против настоящего локального сервера: адреса, коды ответов, доступность, большие записи. */
class WhisperClientHttpTest {
    HttpServer server;
    final AtomicReference<byte[]> lastBody = new AtomicReference<>();
    final AtomicReference<String> lastType = new AtomicReference<>();
    volatile int rootStatus = 200;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                exchange.sendResponseHeaders(rootStatus, -1);
            } else {
                lastBody.set(exchange.getRequestBody().readAllBytes());
                lastType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                byte[] answer = ("{\"path\":\"" + exchange.getRequestURI().getPath() + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(exchange.getRequestURI().getPath().equals("/fail") ? 500 : 200, answer.length);
                exchange.getResponseBody().write(answer);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private WhisperClient client(String url) {
        return new WhisperClient(new WhisperProperties(url, false, null, null, null, null, null, null, null));
    }

    private String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    @Test
    void base_url_is_normalised_and_configuration_is_reported() {
        assertEquals(base(), client(base() + "///").baseUrl());
        assertTrue(client(base()).configured());
        assertFalse(client("").configured());
    }

    @Test
    void reachability_follows_the_server_status() {
        assertTrue(client(base()).reachable());
        rootStatus = 404;
        assertTrue(client(base()).reachable(), "4xx — сервер жив");
        rootStatus = 503;
        assertFalse(client(base()).reachable(), "5xx — не готов");
        assertFalse(client("").reachable(), "не настроен — недоступен");
        assertFalse(client("http://127.0.0.1:1").reachable(), "порт закрыт");
    }

    @Test
    void reachability_check_keeps_the_interrupt_flag() {
        Thread.currentThread().interrupt();
        try {
            assertFalse(client(base()).reachable());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void post_sends_raw_pcm_and_returns_status_and_body() throws Exception {
        byte[] pcm = new byte[64_000];
        pcm[0] = 7;
        WhisperClient.Reply ok = client(base()).post("/transcribe-json", pcm);
        assertEquals(200, ok.status());
        assertEquals("{\"path\":\"/transcribe-json\"}", ok.body());
        assertEquals("application/octet-stream", lastType.get());
        assertArrayEquals(pcm, lastBody.get());
        WhisperClient.Reply failed = client(base()).post("/fail", new byte[2]);
        assertEquals(500, failed.status());
    }
}
