package ru.sttplatform.diarization;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Микросервис диаризации: Nemotron 3 (кто и когда говорит) + CAM++ (кандидат или интервьюер).
 *
 * @version 2.0.0
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DiarizationServiceApplication {
    /**
     * Запускает сервис.
     *
     * @param args аргументы Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(DiarizationServiceApplication.class, args);
    }
}
