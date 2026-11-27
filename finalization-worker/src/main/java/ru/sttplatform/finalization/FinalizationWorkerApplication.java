package ru.sttplatform.finalization;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Точка запуска фонового обработчика; HTTP используется только для внутренней проверки процесса. */
@SpringBootApplication
@RestController
public class FinalizationWorkerApplication {
    public static void main(String[] args) { SpringApplication.run(FinalizationWorkerApplication.class, args); }

    @GetMapping("/health")
    public String health() { return "ok"; }
}
