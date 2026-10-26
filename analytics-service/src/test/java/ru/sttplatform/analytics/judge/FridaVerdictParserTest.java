package ru.sttplatform.analytics.judge;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import ru.sttplatform.analytics.engine.JudgeInput;
import ru.sttplatform.analytics.engine.Verdict;
import ru.sttplatform.common.json.Json;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Ответы в фикстурах собраны функцией aggregate из самой библиотеки модели, то есть имеют настоящую форму. */
class FridaVerdictParserTest {

    @Test
    void decision_response_gives_facts_ignorance_offtopic_and_follow_up_shares() throws Exception {
        JudgeInput in = FridaPromptBuilderTest.inputOfFirstQuestion();
        Verdict v = FridaVerdictParser.parse(FridaPromptBuilderTest.fixture("decision-response.json"), in);

        assertEquals(3, v.facts().size());
        assertTrue(v.facts().get(0).p() > 0.95);
        assertTrue(v.facts().get(1).p() < 0.1);
        assertEquals(in.facts().get(1), v.facts().get(1).text());
        assertTrue(v.ignorance() < 0.05 && v.offTopic() < 0.05);
        assertNull(v.completeness());
        assertEquals(5, v.followUpIndex().length);
        int best = 0;
        for (int i = 1; i < v.followUpShare().length; i++) if (v.followUpShare()[i] > v.followUpShare()[best]) best = i;
        assertEquals(1, v.followUpIndex()[best]);
        assertEquals(1.0, java.util.Arrays.stream(v.followUpShare()).sum(), 1e-9);
        assertEquals(42, v.latencyMs());
        assertFalse(v.stateTruncated());
        assertEquals("frida", v.source());
    }

    @Test
    void scoring_response_gives_scores_normalised_to_zero_one() throws Exception {
        Verdict v = FridaVerdictParser.parse(FridaPromptBuilderTest.fixture("scoring-response.json"),
                FridaPromptBuilderTest.inputOfFirstQuestion());
        assertEquals(2.7308810391811384 / 4, v.completeness(), 1e-9);
        assertTrue(v.correctness() > 0.6 && v.correctness() < 0.9);
        assertTrue(v.terminology() > 0.4 && v.terminology() < 0.7);
        assertNull(v.ignorance());
        assertNull(v.followUpIndex(), "в фоновом запросе выбора уточнения нет");
    }

    @Test
    void a_single_remaining_follow_up_is_chosen_without_asking_the_model() throws Exception {
        JudgeInput in = new JudgeInput("Стажёр", "t", "q", "r", "", List.of("Факт."),
                List.of(new JudgeInput.Turn("q", "a")), List.of(new JudgeInput.FollowUpOption(3, "Уточнение?")));
        JsonNode response = Json.tree("{\"answers\":{\"f0\":{\"type\":\"noul\",\"noul\":0.2}},\"usage\":{\"milliseconds\":5}}");
        Verdict v = FridaVerdictParser.parse(response, in);
        assertArrayEquals(new int[]{3}, v.followUpIndex());
        assertArrayEquals(new double[]{1.0}, v.followUpShare());
    }

    @Test
    void response_without_answers_is_rejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> FridaVerdictParser.parse(Json.tree("{\"error\":{}}"),
                FridaPromptBuilderTest.inputOfFirstQuestion()));
    }

    @Test
    void truncated_state_flag_is_passed_on() throws Exception {
        JsonNode response = Json.tree("{\"answers\":{\"f0\":{\"type\":\"noul\",\"noul\":0.5}},\"usage\":{\"state_truncated\":true}}");
        JudgeInput in = new JudgeInput("Стажёр", "t", "q", "r", "", List.of("Факт."), List.of(new JudgeInput.Turn("q", "a")), List.of());
        assertTrue(FridaVerdictParser.parse(response, in).stateTruncated());
    }
}
