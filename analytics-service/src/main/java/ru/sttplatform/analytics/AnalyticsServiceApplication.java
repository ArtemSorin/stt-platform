package ru.sttplatform.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Микросервис аналитики ответов: банк вопросов, решения «уточнить / дальше», оценки и Excel-отчёт.
 *
 * @version 2.0.0
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AnalyticsServiceApplication {
    /**
     * Запускает сервис.
     *
     * @param args аргументы Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(AnalyticsServiceApplication.class, args);
    }
}
