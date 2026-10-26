package ru.sttplatform.analytics.judge;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.sttplatform.analytics.engine.AnswerAnalysis;
import ru.sttplatform.analytics.engine.CompiledQuestion;
import ru.sttplatform.analytics.engine.DecisionPolicy;
import ru.sttplatform.analytics.engine.Facts;
import ru.sttplatform.analytics.engine.InterviewKnowledge;
import ru.sttplatform.analytics.engine.JudgeInput;
import ru.sttplatform.analytics.engine.Verdict;
import ru.sttplatform.common.json.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Проверка и калибровка модели оценки на ваших данных и вашем железе.
 * <ul>
 *   <li>{@code GET /api/judge/status} — состояние клиента и сервера модели, задержки;</li>
 *   <li>{@code GET /api/judge/selfcheck?questions=6} — прогоняет через настоящую политику решений четыре
 *       синтетических ответа на каждый из выбранных вопросов (эталон дословно, половина эталона, «не знаю»,
 *       ответ не по теме) и показывает, выдерживаются ли ожидаемые соотношения и укладывается ли модель в бюджет.</li>
 * </ul>
 *
 * @version 1.0.0
 */
@RestController
@RequestMapping("/api/judge")
public class JudgeController {
    private static final String UNKNOWN = "Честно говоря, я не знаю, не готовился к этому вопросу и не помню.";
    private static final String OFF_TOPIC = "Вчера ходил в магазин, купил хлеб и молоко, а потом смотрел футбол с друзьями.";

    private final FridaJudge judge;
    private final InterviewKnowledge kb;
    private final DecisionPolicy policy;
    private final JudgeProperties props;

    /**
     * Создаёт контроллер.
     *
     * @param judge клиент модели
     * @param kb банк знаний
     * @param policy политика решений с порогами модели
     * @param props настройки модели
     */
    public JudgeController(FridaJudge judge, InterviewKnowledge kb, DecisionPolicy policy, JudgeProperties props) {
        this.judge = judge;
        this.kb = kb;
        this.policy = policy;
        this.props = props;
    }

    /**
     * Состояние модели.
     *
     * @return статистика клиента и ответ сервера модели
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Json.map("client", judge.stats(), "server", judge.serverStatus());
    }

    /**
     * Самопроверка на синтетических ответах.
     *
     * @param questions сколько вопросов банка взять (равномерно по всему банку, 1–20)
     * @return таблица результатов и итог
     */
    @GetMapping("/selfcheck")
    public Map<String, Object> selfcheck(@RequestParam(value = "questions", defaultValue = "6") int questions) {
        if (!props.enabled()) return Json.map("error", "Оценка моделью выключена (interview.judge.enabled=false).");
        CompiledQuestion[] all = kb.questions();
        int n = Math.max(1, Math.min(20, questions));
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Double> latencies = new ArrayList<>();
        int checks = 0, passed = 0;
        for (int i = 0; i < n; i++) {
            CompiledQuestion cq = all[(int) ((long) i * all.length / n)];
            List<String> facts = Facts.split(cq.question().reference());
            String half = String.join(" ", facts.subList(0, Math.max(1, (facts.size() + 1) / 2)));
            Map<String, Map<String, Object>> cases = new java.util.LinkedHashMap<>();
            cases.put("эталон", run(cq, cq.question().reference(), latencies));
            cases.put("половина эталона", run(cq, half, latencies));
            cases.put("не знаю", run(cq, UNKNOWN, latencies));
            cases.put("не по теме", run(cq, OFF_TOPIC, latencies));

            List<String> problems = new ArrayList<>();
            if (cases.values().stream().anyMatch(c -> c.containsKey("error"))) {
                problems.add("модель не ответила");
            } else {
                double ref = (double) cases.get("эталон").get("coverage");
                double mid = (double) cases.get("половина эталона").get("coverage");
                double off = (double) cases.get("не по теме").get("coverage");
                checks += 4;
                passed += check(problems, "эталон должен быть «достаточным»", "sufficient".equals(cases.get("эталон").get("reason")));
                passed += check(problems, "покрытие: эталон > половина > не по теме", ref > mid && mid > off);
                passed += check(problems, "«не знаю» распознаётся", "ignorance".equals(cases.get("не знаю").get("reason")));
                passed += check(problems, "«не по теме» распознаётся", "off_topic".equals(cases.get("не по теме").get("reason")));
            }
            rows.add(Json.map("question", cq.question().text(), "level", cq.level().title, "cases", cases, "problems", problems));
        }
        return Json.map("passed", passed, "checks", checks,
                "latencyMs", Json.distribution(latencies), "decisionDeadlineMs", props.decisionDeadlineMs(),
                "withinDeadline", latencies.isEmpty() ? null
                        : Json.percentile(latencies, .95) <= props.decisionDeadlineMs(),
                "hint", "Если проверки не проходят — меняйте interview.judge.sufficient-shift и пороги; если p95 выше бюджета — "
                        + "нужен GPU или vLLM, а на CPU — модель ONNX int8 (см. README).",
                "rows", rows);
    }

    private static int check(List<String> problems, String what, boolean ok) {
        if (!ok) problems.add(what);
        return ok ? 1 : 0;
    }

    private Map<String, Object> run(CompiledQuestion cq, String answer, List<Double> latencies) {
        long started = System.nanoTime();
        try {
            Verdict v = judge.decide(JudgeInput.single(cq, answer)).get(30, TimeUnit.SECONDS);
            latencies.add((System.nanoTime() - started) / 1e6);
            AnswerAnalysis lex = kb.analyzer().analyze(cq, answer, new boolean[cq.poolSize()]);
            AnswerAnalysis a = lex.withVerdict(v);
            DecisionPolicy.Decision d = policy.decide(cq, cq.level(), a, 0, 0, new boolean[cq.followUps().length],
                    new boolean[cq.poolSize()], v);
            return Json.map("coverage", Json.round(a.coverage(), 3),
                    "factsSaid", v.facts().stream().filter(f -> f.p() >= 0.5).count() + "/" + v.facts().size(),
                    "ignorance", v.ignorance() == null ? null : Json.round(v.ignorance(), 3),
                    "offTopic", v.offTopic() == null ? null : Json.round(v.offTopic(), 3),
                    "reason", d.reason(), "followUp", d.followUp() ? cq.followUps()[d.followUpIndex()].text() : null,
                    "ms", Math.round((System.nanoTime() - started) / 1e6), "stateTruncated", v.stateTruncated());
        } catch (Exception e) {
            return Json.map("error", String.valueOf(e.getCause() == null ? e : e.getCause()));
        }
    }
}
