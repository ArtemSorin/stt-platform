package ru.sttplatform.common.ws;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.sttplatform.testsupport.MiniWsServer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static ru.sttplatform.testsupport.MiniWsServer.await;

/** WsPeer по настоящему сетевому соединению: рукопожатие, обмен сообщениями, закрытие и обрыв. */
class WsPeerNetworkTest {
    MiniWsServer server;
    final List<JsonNode> received = new CopyOnWriteArrayList<>();
    final AtomicInteger closedCalls = new AtomicInteger();

    @BeforeEach void up() throws Exception { server = new MiniWsServer(); }
    @AfterEach void down() { server.close(); }

    private WsPeer connect(Map<String, String> headers) {
        return WsPeer.connect("ws://127.0.0.1:" + server.port() + "/ws/test", headers, received::add, closedCalls::incrementAndGet);
    }

    @Test
    void handshake_carries_the_path_and_custom_headers() throws Exception {
        WsPeer peer = connect(Map.of("X-Internal-Token", "secret-token-0123456789", "X-Audio-Source", "microphone"));
        MiniWsServer.Conn conn = server.awaitConnection(0, 3000);
        assertEquals("/ws/test", conn.path);
        assertEquals("secret-token-0123456789", conn.headers.get("X-Internal-Token"));
        assertEquals("microphone", conn.headers.get("X-Audio-Source"));
        assertTrue(peer.isOpen());
        peer.sendClose(1000, "конец");
    }

    @Test
    void simple_connect_overload_works_without_headers() throws Exception {
        WsPeer peer = WsPeer.connect("ws://127.0.0.1:" + server.port() + "/x", received::add, null);
        server.awaitConnection(0, 3000);
        assertTrue(peer.isOpen());
        peer.abort();
    }

    @Test
    void text_binary_and_json_messages_reach_the_server_in_order() throws Exception {
        WsPeer peer = connect(Map.of());
        MiniWsServer.Conn conn = server.awaitConnection(0, 3000);
        peer.sendText("eof");
        peer.sendJson(ru.sttplatform.common.json.Json.map("type", "start", "n", 1));
        peer.sendAudio(new byte[]{1, 2, 3, 4});
        assertTrue(await(() -> conn.texts.size() == 2 && conn.binaries.size() == 1, 3000));
        assertEquals("eof", conn.texts.get(0));
        assertEquals("{\"type\":\"start\",\"n\":1}", conn.texts.get(1));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, conn.binaries.get(0));
    }

    @Test
    void server_messages_are_parsed_as_json_even_when_split_into_frames_and_bad_ones_are_survived() throws Exception {
        WsPeer peer = connect(Map.of());
        MiniWsServer.Conn conn = server.awaitConnection(0, 3000);
        conn.sendText("{\"type\":\"a\"}");
        conn.sendText("не json");                                  // ошибка разбора логируется, соединение живёт
        conn.sendText("{\"type\":\"b\",\"long\":\"" + "я".repeat(70_000) + "\"}");   // длиннее 64 КБ: приходит частями
        assertTrue(await(() -> received.size() == 2, 5000));
        assertEquals("a", received.get(0).path("type").asText());
        assertEquals("b", received.get(1).path("type").asText());
        assertEquals(70_000, received.get(1).path("long").asText().length());
        assertTrue(peer.isOpen());
        assertNull(peer.failure());
    }

    @Test
    void handler_exception_does_not_break_the_stream() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        WsPeer.connect("ws://127.0.0.1:" + server.port() + "/x", node -> {
            seen.add(node.path("type").asText());
            if ("boom".equals(node.path("type").asText())) throw new IllegalStateException("сбой обработчика");
        }, null);
        MiniWsServer.Conn conn = server.awaitConnection(0, 3000);
        conn.sendText("{\"type\":\"boom\"}");
        conn.sendText("{\"type\":\"after\"}");
        assertTrue(await(() -> seen.size() == 2, 3000));
        assertEquals(List.of("boom", "after"), seen);
    }

    @Test
    void graceful_close_by_the_server_is_reported_once() throws Exception {
        WsPeer peer = connect(Map.of());
        server.awaitConnection(0, 3000).close();
        assertTrue(peer.awaitClosed(3));
        assertEquals(1, closedCalls.get());
        assertFalse(peer.isOpen());
    }

    @Test
    void abrupt_connection_loss_is_reported_as_a_failure() throws Exception {
        WsPeer peer = connect(Map.of());
        server.awaitConnection(0, 3000).drop();
        assertTrue(peer.awaitClosed(5));
        assertTrue(await(() -> closedCalls.get() >= 1, 3000));
        assertFalse(peer.isOpen());
    }

    @Test
    void transport_error_is_remembered_and_closes_the_peer() throws Exception {
        WsPeer peer = connect(Map.of());
        server.awaitConnection(0, 3000);
        IllegalStateException error = new IllegalStateException("ошибка транспорта");
        peer.onError(null, error);
        assertSame(error, peer.failure());
        assertTrue(peer.awaitClosed(1));
        assertEquals(1, closedCalls.get());
        // ошибка без обработчика закрытия тоже безопасна
        WsPeer quiet = new WsPeer(node -> { });
        quiet.onError(null, error);
        assertSame(error, quiet.failure());
        assertNull(quiet.onClose(null, 1000, ""));
    }

    @Test
    void client_close_is_acknowledged() throws Exception {
        WsPeer peer = connect(Map.of());
        MiniWsServer.Conn conn = server.awaitConnection(0, 3000);
        peer.sendClose(1000, "готово");
        assertTrue(await(() -> conn.closedByClient, 3000));
        assertTrue(peer.awaitClosed(3));
    }

    @Test
    void abort_on_an_unattached_peer_is_safe_and_unattached_peer_is_not_open() {
        WsPeer peer = new WsPeer(node -> { });
        assertDoesNotThrow(peer::abort);
        assertFalse(peer.isOpen());
        assertNull(peer.failure());
    }

    @Test
    void connecting_to_a_dead_port_fails_fast() {
        assertThrows(RuntimeException.class, () -> WsPeer.connect("ws://127.0.0.1:1/none", node -> { }, null));
    }
}
