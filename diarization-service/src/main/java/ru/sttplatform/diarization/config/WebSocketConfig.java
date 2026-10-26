package ru.sttplatform.diarization.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import ru.sttplatform.diarization.api.DiarizationWebSocketHandler;

/**
 * Регистрирует канал живого звука и включает сторож сессии.
 *
 * @version 1.0.0
 */
@Configuration
@EnableWebSocket
@EnableScheduling
public class WebSocketConfig implements WebSocketConfigurer {
    private final DiarizationWebSocketHandler handler;

    /**
     * Создаёт конфигурацию.
     *
     * @param handler обработчик живого звука
     */
    public WebSocketConfig(DiarizationWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/diarization/*").setAllowedOrigins("*");
    }

    /**
     * Ограничивает размер WebSocket-сообщений (кадр до 20 480 байт).
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
