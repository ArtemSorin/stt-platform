package ru.sttplatform.analytics.judge;

import ru.sttplatform.analytics.engine.Facts;
import ru.sttplatform.analytics.engine.JudgeInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Превращает вопрос интервью и ответ кандидата в запрос к FRIDA-Decisions.
 * <p>Текст запроса ({@code state}) собран так, чтобы обрезка с конца (модель режет по токенам справа) задевала
 * эталон, а не ответ: сначала вопрос и ответы кандидата, потом эталон и пометка проверяющего. У инструкций к
 * вопросам лимит 96 токенов вместе с суффиксом, который добавляет сама модель, поэтому они короткие.</p>
 * <p>Два запроса: <b>решение</b> (факты эталона, «не знаю», «не по теме», выбор уточнения) — быстрый, идёт в
 * критическом пути; <b>оценка</b> (полнота, точность, терминология, факты) — фоновый, для отчёта.</p>
 *
 * @version 1.0.0
 */
public final class FridaPromptBuilder {
    /** Идентификаторы вопросов в запросе и ответе. */
    public static final String IGNORANCE = "ignorance", OFF_TOPIC = "offtopic", FOLLOW_UP = "follow",
            COMPLETENESS = "completeness", CORRECTNESS = "correctness", TERMINOLOGY = "terminology";
    /** Приставка идентификатора факта: {@code f0}, {@code f1}… */
    public static final String FACT_PREFIX = "f";
    /** Приставка идентификатора уточнения: {@code u3} — уточнение с индексом 3 в банке. */
    public static final String FOLLOW_UP_PREFIX = "u";

    private static final List<String> COMPLETENESS_LEVELS = List.of(
            "Ответа по существу нет: ключевые мысли эталона не названы.",
            "Минимальный ответ: названа одна мысль эталона, без объяснения.",
            "Частичный ответ: главные идеи названы поверхностно, без деталей.",
            "Хороший ответ: большая часть мыслей эталона раскрыта.",
            "Полный ответ: все ключевые мысли эталона раскрыты с пояснениями.");
    private static final List<String> CORRECTNESS_LEVELS = List.of(
            "Ответ ошибочен или не содержит технических утверждений.",
            "Много неточностей и путаницы в понятиях.",
            "Есть заметные неточности, но основа верна.",
            "В целом верно, встречаются мелкие неточности.",
            "Технически точный и верный ответ без ошибок.");
    private static final List<String> TERMINOLOGY_LEVELS = List.of(
            "Терминов нет: всё объяснено бытовыми словами.",
            "Единичные термины, часто неуместные.",
            "Базовые термины используются уместно.",
            "Уверенно использует профессиональную терминологию.",
            "Свободно владеет терминами и профессиональным сленгом разработчиков.");

    private final int maxStateChars;

    /**
     * Создаёт построитель.
     *
     * @param maxStateChars бюджет текста запроса в символах
     */
    public FridaPromptBuilder(int maxStateChars) {
        this.maxStateChars = maxStateChars;
    }

    /**
     * Быстрый запрос для решения «уточнить или дальше».
     *
     * @param in вопрос, эталон и ответы
     * @return запрос в формате FRIDA-Decisions
     */
    public Map<String, Object> decisionRequest(JudgeInput in) {
        Map<String, Object> questions = new LinkedHashMap<>();
        addFacts(questions, in);
        questions.put(IGNORANCE, noul("Кандидат говорит, что не знает ответа или отказывается отвечать?"));
        questions.put(OFF_TOPIC, noul("Ответ кандидата не относится к заданному вопросу?"));
        List<JudgeInput.FollowUpOption> options = in.followUps();
        if (options.size() >= 2) {
            Map<String, Object> criteria = new LinkedHashMap<>();
            for (JudgeInput.FollowUpOption o : options) criteria.put(FOLLOW_UP_PREFIX + o.index(), o.text());
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("type", "ranking");
            q.put("instructions", "Какой уточняющий вопрос лучше восполнит то, чего не хватает в ответе кандидата?");
            q.put("criteria", criteria);
            questions.put(FOLLOW_UP, q);
        }
        return request(in, questions);
    }

    /**
     * Фоновый запрос для оценок по критериям.
     *
     * @param in вопрос, эталон и ответы
     * @return запрос в формате FRIDA-Decisions
     */
    public Map<String, Object> scoringRequest(JudgeInput in) {
        Map<String, Object> questions = new LinkedHashMap<>();
        addFacts(questions, in);
        questions.put(COMPLETENESS, score("Оцени полноту ответа кандидата относительно эталона.", COMPLETENESS_LEVELS));
        questions.put(CORRECTNESS, score("Оцени техническую точность ответа кандидата.", CORRECTNESS_LEVELS));
        questions.put(TERMINOLOGY, score("Оцени владение профессиональной терминологией в ответе кандидата.", TERMINOLOGY_LEVELS));
        return request(in, questions);
    }

    private Map<String, Object> request(JudgeInput in, Map<String, Object> questions) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("state", state(in));
        request.put("questions", questions);
        return request;
    }

    private static void addFacts(Map<String, Object> questions, JudgeInput in) {
        for (int i = 0; i < in.facts().size(); i++) {
            String fact = in.facts().get(i).replace('«', '"').replace('»', '"').replaceAll("[.;!?\\s]+$", "");
            questions.put(FACT_PREFIX + i, noul("Кандидат сообщает: «" + fact + "»?"));
        }
    }

    private static Map<String, Object> noul(String instructions) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("type", "noul");
        q.put("instructions", instructions);
        return q;
    }

    private static Map<String, Object> score(String instructions, List<String> levels) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("type", "score");
        q.put("instructions", instructions);
        q.put("criteria", levels);
        return q;
    }

    /**
     * Собирает текст запроса: вопрос, ответы кандидата, эталон, пометка проверяющего.
     *
     * @param in вопрос, эталон и ответы
     * @return текст не длиннее бюджета (кроме случая, когда вопрос и ответ сами длиннее)
     */
    public String state(JudgeInput in) {
        String head = "Вопрос (" + in.level() + ", " + in.topic() + "): " + Facts.clip(in.question(), 260);
        String notes = in.notes() == null || in.notes().isBlank() ? "" : "\nПометка проверяющего: " + Facts.clip(in.notes(), 150);
        String reference = "\nЭталонный ответ: " + Facts.clip(in.reference(), 450);

        List<JudgeInput.Turn> turns = in.turns();
        StringBuilder earlier = new StringBuilder();
        for (int i = 0; i < turns.size() - 1; i++) {
            JudgeInput.Turn t = turns.get(i);
            earlier.append(i == 0 ? "\nОтвет кандидата: " : "\nОтвет кандидата на уточнение: ").append(clipAnswer(t.answer(), 320));
            JudgeInput.Turn next = turns.get(i + 1);
            earlier.append("\nУточняющий вопрос: ").append(Facts.clip(next.prompt(), 160));
        }
        JudgeInput.Turn last = turns.get(turns.size() - 1);
        String lastLabel = turns.size() == 1 ? "\nОтвет кандидата: " : "\nОтвет кандидата на уточнение: ";

        int fixed = head.length() + earlier.length() + lastLabel.length() + notes.length();
        int room = maxStateChars - fixed;
        // ответ важнее эталона: при нехватке места сначала сжимаем эталон (но не короче 150 символов)
        int answerBudget = Math.max(300, Math.min(1100, room - Math.min(reference.length(), 450)));
        String answer = clipAnswer(last.answer(), answerBudget);
        int refBudget = Math.max(150, room - answer.length() - 20);
        if (reference.length() > refBudget) reference = "\nЭталонный ответ: " + Facts.clip(in.reference(), refBudget);
        return head + earlier + lastLabel + answer + reference + notes;
    }

    /** Длинный ответ: сохраняем начало и конец, середину заменяем многоточием. */
    static String clipAnswer(String text, int max) {
        String t = text == null ? "" : text.strip();
        if (t.length() <= max) return t;
        int head = (int) (max * 0.62), tail = max - head - 3;
        return t.substring(0, head).stripTrailing() + " … " + t.substring(t.length() - tail).stripLeading();
    }

    /**
     * Идентификаторы всех вопросов запроса: для проверок и тестов.
     *
     * @param request запрос, собранный этим построителем
     * @return идентификаторы в порядке запроса
     */
    @SuppressWarnings("unchecked")
    public static List<String> questionIds(Map<String, Object> request) {
        return new ArrayList<>(((Map<String, Object>) request.get("questions")).keySet());
    }
}
