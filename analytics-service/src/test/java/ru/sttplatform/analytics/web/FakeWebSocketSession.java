package ru.sttplatform.analytics.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * WebSocket-сессия для тестов: запоминает отправленные события и код закрытия.
 * Сделана через динамический прокси, поэтому не зависит от набора методов интерфейса в конкретной версии Spring.
 */
final class FakeWebSocketSession {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    final String id;
    final List<JsonNode> sent = Collections.synchronizedList(new ArrayList<>());
    volatile boolean open = true;
    volatile CloseStatus closedWith;
    volatile IOException failOnSend;
    final WebSocketSession session;

    FakeWebSocketSession(String id) {
        this.id = id;
        this.session = (WebSocketSession) Proxy.newProxyInstance(WebSocketSession.class.getClassLoader(),
                new Class<?>[]{WebSocketSession.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "isOpen" -> open;
                    case "sendMessage" -> {
                        if (failOnSend != null) throw failOnSend;
                        sent.add(MAPPER.readTree(((TextMessage) (WebSocketMessage<?>) args[0]).getPayload()));
                        yield null;
                    }
                    case "close" -> {
                        open = false;
                        closedWith = (CloseStatus) args[0];
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "FakeWebSocketSession[" + id + "]";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    JsonNode last(String type) {
        synchronized (sent) {
            for (int i = sent.size() - 1; i >= 0; i--) if (type.equals(sent.get(i).path("type").asText())) return sent.get(i);
        }
        return null;
    }

    int count(String type) {
        synchronized (sent) {
            return (int) sent.stream().filter(n -> type.equals(n.path("type").asText())).count();
        }
    }
}
