package ru.sttplatform.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Шлюз и оркестратор: страница интервью, API браузера и маршрутизация звука между микросервисами.
 *
 * @version 1.0.0
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class GatewayApplication {
    /**
     * Запускает сервис.
     *
     * @param args аргументы Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
