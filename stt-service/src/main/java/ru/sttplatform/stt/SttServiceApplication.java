package ru.sttplatform.stt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Микросервис распознавания речи: потоковый Vosk и второй проход Whisper.
 *
 * @version 1.0.0
 */
@SpringBootApplication(scanBasePackages = {"ru.sttplatform.stt", "ru.sttplatform.diarization", "ru.sttplatform.gateway"})
@ConfigurationPropertiesScan(basePackages = {"ru.sttplatform.stt", "ru.sttplatform.diarization", "ru.sttplatform.gateway"})
@EnableScheduling
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
