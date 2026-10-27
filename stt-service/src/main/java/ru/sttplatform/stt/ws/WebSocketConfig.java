package ru.sttplatform.stt.ws;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Регистрирует канал {@code /ws/stt} и ограничивает размер сообщений.
 *
 * @version 1.0.0
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final SttWebSocketHandler sttHandler;

    /**
     * Создаёт конфигурацию.
     *
     * @param sttHandler обработчик STT
     */
    public WebSocketConfig(SttWebSocketHandler sttHandler) {
        this.sttHandler = sttHandler;
    }

    /** Регистрирует браузерный WebSocket для прямого распознавания STT.
     * @param registry реестр серверных WebSocket-маршрутов
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(sttHandler, "/ws/stt").setAllowedOrigins("*");
    }

    /**
     * Ограничивает размер бинарных и текстовых WebSocket-сообщений.
     *
     * @return настройки контейнера WebSocket
     */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean c = new ServletServerContainerFactoryBean();
        c.setMaxBinaryMessageBufferSize(64 * 1024);
        c.setMaxTextMessageBufferSize(8 * 1024);
        return c;
    }
}
