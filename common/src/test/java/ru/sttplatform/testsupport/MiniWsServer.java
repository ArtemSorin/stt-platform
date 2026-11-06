package ru.sttplatform.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Минимальный WebSocket-сервер (RFC 6455) для интеграционных тестов: без Spring и без внешних библиотек.
 * Принимает соединения на свободном порту, запоминает заголовки рукопожатия, текстовые и бинарные сообщения
 * и умеет отправлять сообщения клиенту. Нужен, чтобы проверять настоящие сетевые мосты (WsPeer, Orchestrator).
 */
public final class MiniWsServer implements AutoCloseable {

    /** Одно подключение клиента. */
    public final class Conn {
        private final Socket socket;
        private final OutputStream out;
        public final String path;
        public final Map<String, String> headers;
        public final List<String> texts = new CopyOnWriteArrayList<>();
        public final List<byte[]> binaries = new CopyOnWriteArrayList<>();
        public volatile boolean closedByClient;

        Conn(Socket socket, String path, Map<String, String> headers) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
            this.path = path;
            this.headers = headers;
        }

        public synchronized void sendText(String text) throws IOException {
            writeFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
        }

        public synchronized void sendBinary(byte[] data) throws IOException {
            writeFrame(0x2, data);
        }

        /** Отправляет кадр закрытия и закрывает сокет. */
        public synchronized void close() {
            try { writeFrame(0x8, new byte[]{0x03, (byte) 0xE8}); } catch (IOException ignored) { }
            try { socket.close(); } catch (IOException ignored) { }
        }

        /** Обрывает TCP без кадра закрытия — как падение сервиса. */
        public void drop() {
            try { socket.close(); } catch (IOException ignored) { }
        }

        private void writeFrame(int opcode, byte[] payload) throws IOException {
            ByteArrayOutputStream f = new ByteArrayOutputStream();
            f.write(0x80 | opcode);
            if (payload.length < 126) f.write(payload.length);
            else if (payload.length < 65536) { f.write(126); f.write(payload.length >> 8); f.write(payload.length & 0xff); }
            else {
                f.write(127);
                for (int i = 7; i >= 0; i--) f.write((int) ((long) payload.length >> (8 * i)) & 0xff);
            }
            f.write(payload);
            out.write(f.toByteArray());
            out.flush();
        }

        void readLoop(InputStream in) throws IOException {
            DataInputStream data = new DataInputStream(in);
            ByteArrayOutputStream message = new ByteArrayOutputStream();
            int messageOpcode = 0;
            while (true) {
                int b0 = data.read();
                if (b0 < 0) break;
                int b1 = data.readUnsignedByte();
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0f;
                long len = b1 & 0x7f;
                if (len == 126) len = data.readUnsignedShort();
                else if (len == 127) len = data.readLong();
                byte[] mask = new byte[4];
                if ((b1 & 0x80) != 0) data.readFully(mask);
                byte[] payload = new byte[(int) len];
                data.readFully(payload);
                if ((b1 & 0x80) != 0) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                if (opcode == 0x8) { closedByClient = true; synchronized (this) { try { writeFrame(0x8, payload); } catch (IOException ignored) { } } break; }
                if (opcode == 0x9) { synchronized (this) { writeFrame(0xA, payload); } continue; }
                if (opcode == 0xA) continue;
                if (opcode != 0x0) { messageOpcode = opcode; message.reset(); }
                message.write(payload);
                if (!fin) continue;
                byte[] whole = message.toByteArray();
                message.reset();
                if (messageOpcode == 0x1) {
                    String text = new String(whole, StandardCharsets.UTF_8);
                    texts.add(text);
                    if (onText != null) onText.accept(this, text);
                } else if (messageOpcode == 0x2) {
                    binaries.add(whole);
                    if (onBinary != null) onBinary.accept(this, whole);
                }
            }
            closedByClient = true;
        }
    }

    private final ServerSocket server;
    private final Thread acceptor;
    public final List<Conn> connections = new CopyOnWriteArrayList<>();
    public volatile BiConsumer<Conn, String> onText;
    public volatile BiConsumer<Conn, byte[]> onBinary;
    public volatile java.util.function.Consumer<Conn> onConnect;
    private volatile boolean closed;

    public MiniWsServer() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::acceptLoop, "mini-ws-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() { return server.getLocalPort(); }

    public String httpUrl() { return "http://127.0.0.1:" + port(); }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = server.accept();
                Thread t = new Thread(() -> serve(socket), "mini-ws-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int prev = 0, count = 0;
            while (count < 4) {
                int c = in.read();
                if (c < 0) return;
                head.write(c);
                if ((c == '\r' && (count == 0 || count == 2)) || (c == '\n' && (count == 1 || count == 3))) count++;
                else count = c == '\r' ? 1 : 0;
                prev = c;
            }
            String[] lines = head.toString(StandardCharsets.ISO_8859_1).split("\r\n");
            String path = lines[0].split(" ")[1];
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (int i = 1; i < lines.length; i++) {
                int idx = lines[i].indexOf(':');
                if (idx > 0) headers.put(lines[i].substring(0, idx).trim(), lines[i].substring(idx + 1).trim());
            }
            String key = headers.get("Sec-WebSocket-Key");
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
            socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                    + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            Conn conn = new Conn(socket, path, headers);
            connections.add(conn);
            if (onConnect != null) onConnect.accept(conn);
            conn.readLoop(in);
        } catch (Exception ignored) {
            // соединение оборвано клиентом или тестом
        }
    }

    /** Ждёт, пока подключится n клиентов. */
    public Conn awaitConnection(int index, long timeoutMs) throws InterruptedException {
        long end = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < end) {
            if (connections.size() > index) return connections.get(index);
            Thread.sleep(5);
        }
        throw new AssertionError("клиент " + index + " не подключился за " + timeoutMs + " мс");
    }

    /** Ждёт, пока предикат станет истинным. */
    public static boolean await(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long end = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(5);
        }
        return condition.getAsBoolean();
    }

    public List<Conn> snapshot() { return new ArrayList<>(connections); }

    @Override public void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) { }
        for (Conn c : connections) c.drop();
    }
}
