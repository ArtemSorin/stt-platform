package ru.sttplatform.analytics.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import ru.sttplatform.analytics.engine.DecisionPolicy;
import ru.sttplatform.analytics.engine.Glossary;
import ru.sttplatform.analytics.engine.InterviewKnowledge;
import ru.sttplatform.analytics.engine.QuestionBank;
import ru.sttplatform.analytics.engine.SpokenText;
import ru.sttplatform.analytics.judge.FridaJudge;
import ru.sttplatform.analytics.judge.JudgeProperties;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Создаёт банк знаний и политику принятия решений для интервью.
 *
 * @version 1.0.0
 */
@Configuration
public class InterviewConfig {

    /**
     * Выполняет операцию публичного контракта компонента.
     *
     * @param p параметр операции
     * @return подготовленный банк знаний
     * @throws IOException при ошибке чтения, записи или обработки потока
     */
    @Bean
    public InterviewKnowledge interviewKnowledge(InterviewProperties p) throws IOException {
        String tsv = load(p.questionsPath(), "interview/questions.tsv");
        String glossary = load(p.glossaryPath(), "interview/glossary.txt");
        InterviewKnowledge kb = InterviewKnowledge.build(QuestionBank.parse(tsv), Glossary.parse(glossary))
                .withSpoken(SpokenText.parse(load(p.spokenPath(), "interview/spoken.txt")));
        kb.warmUp(30);   // прогрев JIT: первое реальное решение не должно быть «холодным»
        return kb;
    }

    /**
     * Выполняет операцию публичного контракта компонента.
     *
     * @param p параметр операции
     * @return результат операции
     */
    @Bean
    public DecisionPolicy decisionPolicy(InterviewProperties p, JudgeProperties j) {
        return new DecisionPolicy(p.maxFollowUps(), p.weakCoverage(),
                new DecisionPolicy.Thresholds(j.ignoranceP(), j.ignoranceMaxCoverage(), j.offTopicP(),
                        j.offTopicMaxCoverage(), j.stagnationDelta(), j.sufficientShift()));
    }

    /**
     * Клиент смысловой оценки ответов моделью FRIDA-Decisions.
     *
     * @param j настройки модели
     * @return клиент; при {@code interview.judge.enabled=false} он всегда «не готов», и работают правила
     */
    @Bean
    public FridaJudge fridaJudge(JudgeProperties j) {
        return new FridaJudge(j);
    }

    private static String load(String external, String classpath) throws IOException {
        if (external != null && !external.isBlank()) {
            return Files.readString(Path.of(external), StandardCharsets.UTF_8);
        }
        try (InputStream in = new ClassPathResource(classpath).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
