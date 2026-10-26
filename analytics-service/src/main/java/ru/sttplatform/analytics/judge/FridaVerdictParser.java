package ru.sttplatform.analytics.judge;

import com.fasterxml.jackson.databind.JsonNode;
import ru.sttplatform.analytics.engine.JudgeInput;
import ru.sttplatform.analytics.engine.Verdict;

import java.util.ArrayList;
import java.util.List;

/**
 * Разбирает ответ FRIDA-Decisions ({@code answers}, {@code usage}) в {@link Verdict}.
 * Формат: {@code noul} — вероятность «да»; {@code score} — ожидаемый уровень 0…N−1; {@code ranking} — доли по кандидатам.
 *
 * @version 1.0.0
 */
public final class FridaVerdictParser {
    private static final double TOP_LEVEL = 4.0;

    private FridaVerdictParser() {}

    /**
     * Разбирает ответ модели.
     *
     * @param response корень ответа ({@code answers} и {@code usage}); для vLLM — его поле {@code data}
     * @param in запрос, на который это ответ (нужны число фактов и индексы уточнений)
     * @return заключение; незаданные вопросы дают {@code null}-поля
     * @throws IllegalArgumentException если в ответе нет блока {@code answers} или значение не число
     */
    public static Verdict parse(JsonNode response, JudgeInput in) {
        JsonNode answers = response.path("answers");
        if (!answers.isObject()) throw new IllegalArgumentException("В ответе модели нет блока answers");

        List<Verdict.Fact> facts = new ArrayList<>();
        for (int i = 0; i < in.facts().size(); i++) {
            JsonNode a = answers.path(FridaPromptBuilder.FACT_PREFIX + i);
            if (a.isMissingNode()) continue;
            facts.add(new Verdict.Fact(in.facts().get(i), probability(a, "noul")));
        }

        int[] followIdx = null;
        double[] followShare = null;
        JsonNode rank = answers.path(FridaPromptBuilder.FOLLOW_UP);
        if (rank.isObject()) {
            JsonNode probabilities = rank.path("probabilities");
            List<Integer> idx = new ArrayList<>();
            List<Double> share = new ArrayList<>();
            for (JudgeInput.FollowUpOption o : in.followUps()) {
                JsonNode p = probabilities.path(FridaPromptBuilder.FOLLOW_UP_PREFIX + o.index());
                if (p.isNumber()) {
                    idx.add(o.index());
                    share.add(p.asDouble());
                }
            }
            followIdx = idx.stream().mapToInt(Integer::intValue).toArray();
            followShare = share.stream().mapToDouble(Double::doubleValue).toArray();
        } else if (in.followUps().size() == 1) {
            followIdx = new int[]{in.followUps().get(0).index()};   // выбирать не из чего — единственное оставшееся
            followShare = new double[]{1.0};
        } else if (in.followUps().isEmpty()) {
            followIdx = new int[0];
            followShare = new double[0];
        }

        JsonNode usage = response.path("usage");
        return new Verdict(facts.isEmpty() ? null : facts,
                optionalProbability(answers, FridaPromptBuilder.IGNORANCE),
                optionalProbability(answers, FridaPromptBuilder.OFF_TOPIC),
                followIdx, followShare,
                optionalScore(answers, FridaPromptBuilder.COMPLETENESS),
                optionalScore(answers, FridaPromptBuilder.CORRECTNESS),
                optionalScore(answers, FridaPromptBuilder.TERMINOLOGY),
                "frida", Math.round(usage.path("milliseconds").asDouble(0)), usage.path("state_truncated").asBoolean(false));
    }

    private static double probability(JsonNode answer, String key) {
        JsonNode v = answer.path(key);
        if (!v.isNumber()) throw new IllegalArgumentException("Ожидалось число в поле " + key);
        return Math.max(0, Math.min(1, v.asDouble()));
    }

    private static Double optionalProbability(JsonNode answers, String id) {
        JsonNode a = answers.path(id);
        return a.isMissingNode() ? null : probability(a, "noul");
    }

    private static Double optionalScore(JsonNode answers, String id) {
        JsonNode a = answers.path(id);
        if (a.isMissingNode()) return null;
        JsonNode v = a.path("score");
        if (!v.isNumber()) throw new IllegalArgumentException("Ожидалось число в поле score вопроса " + id);
        return Math.max(0, Math.min(1, v.asDouble() / TOP_LEVEL));
    }
}
