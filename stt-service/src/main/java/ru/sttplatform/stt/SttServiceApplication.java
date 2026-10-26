package ru.sttplatform.stt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Микросервис распознавания речи: потоковый Vosk и второй проход Whisper.
 *
 * @version 2.0.0
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SttServiceApplication {
    /**
     * Запускает сервис.
     *
     * @param args аргументы Spring Boot
     */
    public static void main(String[] args) {
        System.setProperty("jna.encoding", "UTF-8");
        SpringApplication.run(SttServiceApplication.class, args);
    }
}
