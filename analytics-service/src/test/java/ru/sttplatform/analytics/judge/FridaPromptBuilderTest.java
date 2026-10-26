package ru.sttplatform.analytics.judge;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import ru.sttplatform.analytics.engine.*;
import ru.sttplatform.common.json.Json;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FridaPromptBuilderTest {
    static final String ANSWER = "JVM это виртуальная машина, она исполняет байткод. JDK включает компилятор javac и инструменты разработки.";
    private final FridaPromptBuilder prompts = new FridaPromptBuilder(1800);

    static JsonNode fixture(String name) throws IOException {
        try (var in = FridaPromptBuilderTest.class.getResourceAsStream("/frida/" + name)) {
            return Json.tree(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    static JudgeInput inputOfFirstQuestion() {
        return JudgeInput.single(TestKb.get().byId(1), ANSWER);
    }

    /** Фикстуры проверены настоящей библиотекой frida_decisions.parse_request; тест защищает их от расхождения с кодом. */
    @Test
    void requests_match_the_fixtures_validated_by_the_models_own_library() throws IOException {
        JudgeInput in = inputOfFirstQuestion();
        assertEquals(fixture("decision-request.json"), Json.MAPPER.valueToTree(prompts.decisionRequest(in)));
        assertEquals(fixture("scoring-request.json"), Json.MAPPER.valueToTree(prompts.scoringRequest(in)));
    }

    @Test
    void decision_request_asks_facts_ignorance_offtopic_and_follow_up_choice() {
        List<String> ids = FridaPromptBuilder.questionIds(prompts.decisionRequest(inputOfFirstQuestion()));
        assertEquals(List.of("f0", "f1", "f2", "ignorance", "offtopic", "follow"), ids);
    }

    @Test
    void scoring_request_asks_three_scales_but_no_follow_up() {
        List<String> ids = FridaPromptBuilder.questionIds(prompts.scoringRequest(inputOfFirstQuestion()));
        assertEquals(List.of("f0", "f1", "f2", "completeness", "correctness", "terminology"), ids);
    }

    @SuppressWarnings("unchecked")
    @Test
    void fact_instructions_stay_short_enough_for_the_96_token_limit() {
        Map<String, Object> qs = (Map<String, Object>) prompts.decisionRequest(inputOfFirstQuestion()).get("questions");
        for (CompiledQuestion cq : TestKb.get().questions()) {
            Map<String, Object> all = (Map<String, Object>) prompts.decisionRequest(JudgeInput.single(cq, ANSWER)).get("questions");
            for (var e : all.entrySet()) {
                if (!e.getKey().startsWith("f") || e.getKey().equals("follow")) continue;
                String text = (String) ((Map<String, Object>) e.getValue()).get("instructions");
                assertTrue(text.length() <= 125, text.length() + ": " + text);
            }
        }
        assertNotNull(qs);
    }

    @Test
    void follow_up_ranking_is_skipped_when_fewer_than_two_options_remain() {
        CompiledQuestion cq = TestKb.get().byId(1);
        JudgeInput one = new JudgeInput("Стажёр", "t", "q", "Эталон один. Эталон два длиннее.", "", List.of("Эталон один."),
                List.of(new JudgeInput.Turn("q", "ответ")), List.of(new JudgeInput.FollowUpOption(2, "Уточнение?")));
        assertFalse(FridaPromptBuilder.questionIds(prompts.decisionRequest(one)).contains("follow"));
        assertNotNull(cq);
    }

    @Test
    void long_answer_never_pushes_the_reference_ahead_of_it_and_total_stays_in_budget() {
        String longAnswer = "слово ".repeat(1200);
        JudgeInput in = JudgeInput.single(TestKb.get().byId(1), longAnswer);
        String state = prompts.state(in);
        assertTrue(state.length() <= 1900, "state=" + state.length());
        assertTrue(state.indexOf("Ответ кандидата:") < state.indexOf("Эталонный ответ:"), "ответ должен стоять раньше эталона");
        assertTrue(state.contains(" … "), "длинный ответ сокращается из середины");
    }

    @Test
    void follow_up_turns_are_included_in_order() {
        JudgeInput in = new JudgeInput("Junior", "Многопоточность", "Что такое поток?", "Эталон.", "", List.of("Эталон."),
                List.of(new JudgeInput.Turn("Что такое поток?", "Ну это когда параллельно."),
                        new JudgeInput.Turn("Чем поток отличается от процесса?", "Поток внутри процесса.")), List.of());
        String state = prompts.state(in);
        int a = state.indexOf("Ответ кандидата: Ну это"), q = state.indexOf("Уточняющий вопрос: Чем поток"),
                b = state.indexOf("Ответ кандидата на уточнение: Поток внутри");
        assertTrue(a >= 0 && a < q && q < b, state);
    }
}
