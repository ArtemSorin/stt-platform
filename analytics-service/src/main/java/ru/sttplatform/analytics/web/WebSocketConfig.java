package ru.sttplatform.analytics.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Регистрирует закрытый канал событий STT и ограничивает размер сообщений.
 *
 * @version 1.0.0
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final InterviewWebSocketHandler interviewHandler;
    private final InternalEventsHandshakeInterceptor authentication;

    /**
     * Создаёт конфигурацию.
     *
     * @param interviewHandler обработчик интервью
     */
    public WebSocketConfig(InterviewWebSocketHandler interviewHandler, InternalEventsHandshakeInterceptor authentication) {
        this.interviewHandler = interviewHandler;
        this.authentication = authentication;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(interviewHandler, "/ws/interview-events").addInterceptors(authentication);
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
