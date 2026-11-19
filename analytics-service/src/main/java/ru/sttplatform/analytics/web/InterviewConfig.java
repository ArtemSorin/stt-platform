package ru.sttplatform.analytics.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import ru.sttplatform.analytics.engine.DecisionPolicy;
import ru.sttplatform.analytics.engine.Glossary;
import ru.sttplatform.analytics.engine.InterviewKnowledge;
import ru.sttplatform.analytics.engine.QuestionBank;
import ru.sttplatform.analytics.engine.QuestionCatalog;
import org.springframework.beans.factory.annotation.Value;
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
    @Value("${interview.followups-path:}")
    private String followupsPath = "";

    /**
     * Выполняет операцию публичного контракта компонента.
     *
     * @param p параметр операции
     * @return подготовленный банк знаний
     * @throws IOException при ошибке чтения, записи или обработки потока
     */
    @Bean
    public InterviewKnowledge interviewKnowledge(InterviewProperties p) throws IOException {
        boolean bundled = (p.questionsPath() == null || p.questionsPath().isBlank()) && followupsPath.isBlank();
        String tsv = load(p.questionsPath(), bundled ? "interview/team-questions.tsv" : "interview/questions.tsv");
        String glossary = load(p.glossaryPath(), "interview/glossary.txt");
        var questions = bundled
                ? QuestionCatalog.toQuestions(QuestionCatalog.parse(tsv,
                    load("", "interview/team-followups.tsv")))
                : followupsPath.isBlank() ? QuestionBank.parse(tsv)
                : QuestionCatalog.toQuestions(QuestionCatalog.parse(tsv,
                    Files.readString(Path.of(followupsPath), StandardCharsets.UTF_8)));
        InterviewKnowledge kb = InterviewKnowledge.build(questions, Glossary.parse(glossary))
                .withSpoken(SpokenText.parse(load(p.spokenPath(), "interview/spoken.txt")));
        kb.warmUp(30);   // прогрев JIT: первое реальное решение не должно быть «холодным»
        return kb;
    }

    /**
     * Выполняет операцию публичного контракта компонента.
     *
     * @param p настройки ограничений интервью
     * @param j настройки порогов FRIDA-Decisions
     * @return правила принятия решений по ответам
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
