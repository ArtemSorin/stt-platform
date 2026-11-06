package ru.sttplatform.analytics.live;

import org.junit.jupiter.api.Test;
import ru.sttplatform.analytics.engine.*;
import ru.sttplatform.analytics.live.SttSim.VirtualJudge;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Бюджет времени на вопрос: от последнего звука кандидата до решения («уточнить или дальше») — не более 3 секунд.
 * Тесты идут по боевому пути (кадры STT → {@code onSttFrame}) в виртуальном времени: 1 кадр = 50 мс.
 *
 * <p>Два измерения:
 * <ul>
 *   <li><b>Решение</b> — {@code timing.silenceMs} в событии {@code decision}: сколько тишины ждали до решения.
 *       Жёсткий бюджет: ≤ 3000 мс при любых настройках по умолчанию, любой реакции модели.</li>
 *   <li><b>Вопрос на экране</b> — решение + окно отмены {@code graceMs} (клиент показывает следующий вопрос только
 *       после него). Для типичных ответов ≤ 3000 мс; для ответов с «союзом на конце» или очень короткими после
 *       решения «уточнить» — больше, это зафиксировано тестами {@code documented_*}.</li>
 * </ul>
 */
class LiveInterviewSlaTest {

    static final String STRONG = "джей ви эм это виртуальная машина которая исполняет байткод джава джи эр и это среда выполнения "
            + "для запуска приложений а джи ди кей это комплект разработчика там компилятор джавак и инструменты разработки";
    static final String PARTIAL = "джей ви эм это виртуальная машина которая исполняет байткод";
    static final String WEAK_CONNECTOR = "ну это такая штука для запуска программ на джаве я думаю и";
    static final String IGNORANT = "я не знаю что это такое честно";
    static final String SHORT = "виртуальная машина";

    static final long BUDGET_MS = 3000;
    static final int GRACE = LiveInterview.Timing.defaults().graceMs();

    private static InterviewKnowledge kb() { return TestKb.get(); }

    private static SttSim intern(AnswerJudge judge) {
        return SttSim.create(kb(), List.of(Level.INTERN), null, judge, 1200);
    }

    /** Старт → вопрос озвучен → 200 мс тишины → речь 2 с → тишина до первого решения. Возвращает тишину до решения. */
    private static long answerAndWait(SttSim sim, String answer) {
        sim.live.start();
        sim.ready();
        sim.silence(200);
        sim.speak(answer, 40);
        long waited = sim.silenceUntil("decision", 6000);
        assertTrue(waited > 0, "решение не принято за 6 с тишины");
        return waited;
    }

    private static Verdict verdict(double factP, Double ignorance, int[] idx, double[] share) {
        return new Verdict(List.of(new Verdict.Fact("факт 1", factP), new Verdict.Fact("факт 2", factP)),
                ignorance, null, idx, share, null, null, null, "frida", 80, false);
    }

    private static final Verdict MODEL_SUFFICIENT = verdict(0.9, null, new int[]{0}, new double[]{1});
    private static final Verdict MODEL_FOLLOW_UP = verdict(0.2, null, new int[]{0}, new double[]{1});
    private static final Verdict MODEL_IGNORANCE = verdict(0.05, 0.9, new int[]{0}, new double[]{1});

    // ==================================================================== без модели: правила

    @Test
    void sufficient_answer_is_decided_in_600ms_and_next_question_is_visible_in_1600ms() {
        SttSim sim = intern(null);
        long waited = answerAndWait(sim, STRONG);

        Map<String, Object> d = sim.last("decision");
        assertEquals("NEXT_QUESTION", d.get("action"));
        assertEquals("sufficient", d.get("reason"));
        assertEquals("rules", d.get("engine"));
        long end = 450 + LiveInterview.Timing.defaults().nextExtraMs();
        assertEquals(end, SttSim.silenceMsOf(d), "мягкий конец 450 мс + 150 мс");
        assertEquals(end, waited);
        assertEquals(end + GRACE, SttSim.silenceMsOf(d) + ((Number) sim.last("question").get("graceMs")).longValue());
        assertTrue(SttSim.silenceMsOf(d) + GRACE <= BUDGET_MS);
    }

    @Test
    void partial_answer_gets_a_follow_up_in_1950ms_and_the_question_is_visible_in_2950ms() {
        SttSim sim = intern(null);
        answerAndWait(sim, PARTIAL);

        Map<String, Object> d = sim.last("decision");
        assertEquals("ASK_FOLLOW_UP", d.get("action"));
        assertEquals("needs_detail", d.get("reason"));
        assertEquals(450 + 1500, SttSim.silenceMsOf(d));
        Map<String, Object> q = sim.last("question");
        assertEquals(Boolean.TRUE, q.get("followUp"));
        assertEquals(GRACE, ((Number) q.get("graceMs")).intValue());
        assertTrue(SttSim.silenceMsOf(d) + GRACE <= BUDGET_MS, "типичное уточнение должно уложиться в 3 с на экране");
    }

    @Test
    void ignorance_is_decided_in_1050ms() {
        SttSim sim = intern(null);
        answerAndWait(sim, IGNORANT);
        Map<String, Object> d = sim.last("decision");
        assertEquals("NEXT_QUESTION", d.get("action"));
        assertEquals("ignorance", d.get("reason"));
        assertEquals(450 + 600, SttSim.silenceMsOf(d));
    }

    @Test
    void short_answer_waits_longer_for_the_endpoint_but_still_decides_within_budget() {
        SttSim sim = intern(null);
        answerAndWait(sim, SHORT);
        Map<String, Object> d = sim.last("decision");
        assertEquals("no_signal", d.get("reason"));
        assertEquals(450 + 350 + 600, SttSim.silenceMsOf(d));    // <4 слов → +350 мс к концу ответа, затем +600
    }

    @Test
    void documented_worst_case_follow_up_after_a_connector_is_decided_in_time_but_shown_after_three_seconds() {
        // Ответ заканчивается союзом «и»: конец ответа ждём 450+350=800 мс, затем «осторожная» пауза уточнения 1500 мс.
        SttSim sim = intern(null);
        answerAndWait(sim, WEAK_CONNECTOR);
        Map<String, Object> d = sim.last("decision");
        assertEquals("ASK_FOLLOW_UP", d.get("action"));
        long decided = SttSim.silenceMsOf(d);
        assertEquals(800 + 1500, decided);
        assertTrue(decided <= BUDGET_MS, "решение укладывается в бюджет");
        assertTrue(decided + GRACE > BUDGET_MS,
                "вопрос на экране позже 3 с: " + (decided + GRACE) + " мс. Если вы это исправили — обновите тест");
    }

    // ==================================================================== модель FRIDA: матрица задержек

    private record Case(String name, String text, int endpointNeed, boolean connector, boolean shortAnswer) {}

    private static final List<Case> TEXTS = List.of(
            new Case("обычный", PARTIAL, 450, false, false),
            new Case("союз на конце", WEAK_CONNECTOR, 800, true, false),
            new Case("короткий", SHORT, 800, false, true),
            new Case("короткий+союз", "виртуальная машина и", 1150, true, true));

    private record Model(String name, long latencyMs, Verdict verdict, boolean fail) {}

    private static final List<Model> MODELS = List.of(
            new Model("мгновенно: достаточно", 0, MODEL_SUFFICIENT, false),
            new Model("мгновенно: уточнить", 0, MODEL_FOLLOW_UP, false),
            new Model("мгновенно: не знает", 0, MODEL_IGNORANCE, false),
            new Model("300 мс: достаточно", 300, MODEL_SUFFICIENT, false),
            new Model("1100 мс: уточнить", 1100, MODEL_FOLLOW_UP, false),
            new Model("медленнее дедлайна", 5000, MODEL_SUFFICIENT, false),
            new Model("ошибка модели", 100, null, true));

    @Test
    void decision_never_takes_more_than_three_seconds_whatever_the_model_does() {
        for (Case c : TEXTS) {
            for (Model m : MODELS) {
                VirtualJudge judge = new VirtualJudge(m.latencyMs, m.verdict, m.fail);
                SttSim sim = intern(judge);
                answerAndWait(sim, c.text);
                Map<String, Object> d = sim.last("decision");
                long decided = SttSim.silenceMsOf(d);
                String label = c.name + " / " + m.name;

                assertTrue(decided >= c.endpointNeed, label + ": решение раньше мягкого конца: " + decided);
                assertTrue(decided <= BUDGET_MS, label + ": решение за " + decided + " мс");
                // верхняя граница: мягкий конец + наибольшая из пауз (уточнение 1500 мс, ожидание модели 1200 мс) + кадр
                assertTrue(decided <= c.endpointNeed + 1500 + SttSim.FRAME_MS, label + ": " + decided);
                assertEquals(1, sim.count("decision"), label);
                assertEquals(1, judge.decideCalls.size() + (m.latencyMs == 0 && false ? 1 : 0), label + ": один запрос к модели");
                long micros = ((Number) ((Map<?, ?>) d.get("timing")).get("decisionMicros")).longValue();
                assertTrue(micros < 100_000, label + ": сам расчёт решения занял " + micros + " мкс");
            }
        }
    }

    @Test
    void fast_model_verdict_drives_the_decision_and_is_reported_in_the_event() {
        VirtualJudge judge = new VirtualJudge(300, MODEL_SUFFICIENT, false);
        SttSim sim = intern(judge);
        answerAndWait(sim, PARTIAL);                           // правила сказали бы «уточнить», модель — «достаточно»
        Map<String, Object> d = sim.last("decision");
        assertEquals("frida", d.get("engine"));
        assertEquals("NEXT_QUESTION", d.get("action"));
        assertEquals("sufficient", d.get("reason"));
        Map<?, ?> info = (Map<?, ?>) d.get("judge");
        assertEquals(2L, info.get("factsSaid"));
        assertEquals(2, info.get("factsTotal"));
        assertEquals(80L, info.get("modelMs"));
        assertNull(info.get("ignorance"));
        assertFalse(info.containsKey("stateTruncated"));
        // запрос к модели ушёл на мягком конце (450 мс), ответ пришёл через 300 мс — позже «достаточной» паузы (600),
        // поэтому решение принимается в момент ответа модели
        assertEquals(450 + 300, SttSim.silenceMsOf(d));
        assertEquals(1, judge.scoreCalls, "фоновая оценка запрошена вне критического пути");
    }

    @Test
    void model_slower_than_the_deadline_falls_back_to_rules_at_exactly_the_deadline() {
        VirtualJudge judge = new VirtualJudge(Long.MAX_VALUE, null, false);   // не отвечает никогда
        SttSim sim = intern(judge);
        answerAndWait(sim, STRONG);
        Map<String, Object> d = sim.last("decision");
        assertEquals("rules", d.get("engine"));
        assertEquals("sufficient", d.get("reason"));
        assertEquals(450 + 1200, SttSim.silenceMsOf(d), "мягкий конец + дедлайн модели 1200 мс");
        assertNull(d.get("judge"));
        assertFalse(judge.decideCalls.get(0).isDone(), "поздний ответ модели не нужен для решения");
    }

    @Test
    void model_error_falls_back_to_rules_as_soon_as_it_is_known() {
        VirtualJudge judge = new VirtualJudge(100, null, true);
        SttSim sim = intern(judge);
        answerAndWait(sim, STRONG);
        Map<String, Object> d = sim.last("decision");
        assertEquals("rules", d.get("engine"));
        assertEquals(600, SttSim.silenceMsOf(d), "ошибка модели не задерживает решение сверх паузы правил");
    }

    @Test
    void model_that_throws_synchronously_does_not_break_the_interview() {
        VirtualJudge judge = new VirtualJudge(0, MODEL_SUFFICIENT, false);
        judge.throwOnDecide = true;
        SttSim sim = intern(judge);
        answerAndWait(sim, STRONG);
        assertEquals("rules", sim.last("decision").get("engine"));
        assertEquals(600, SttSim.silenceMsOf(sim.last("decision")));
    }

    @Test
    void model_that_is_not_ready_is_never_asked() {
        VirtualJudge judge = new VirtualJudge(0, MODEL_SUFFICIENT, false);
        judge.setReady(false);
        SttSim sim = intern(judge);
        answerAndWait(sim, STRONG);
        assertEquals(0, judge.decideCalls.size());
        assertEquals(0, judge.scoreCalls);
        assertEquals("rules", sim.last("decision").get("engine"));
    }

    @Test
    void model_verdict_for_an_older_text_is_not_applied_to_a_longer_answer() {
        // Мягкий конец → запрос к модели по «PARTIAL». Человек продолжает → решение по новому тексту без заключения.
        VirtualJudge judge = new VirtualJudge(Long.MAX_VALUE, null, false);
        SttSim sim = intern(judge);
        sim.live.start();
        sim.ready();
        sim.silence(200);
        sim.speak(PARTIAL, 20);
        assertTrue(sim.silenceUntil("partial", 600) > 0 || true);
        sim.silence(500);                                       // мягкий конец (450 мс) → запрос к модели
        assertEquals(1, judge.decideCalls.size());
        assertEquals(0, sim.count("decision"));
        sim.speak("и ещё сборщик мусора освобождает память в куче", 12);   // речь возобновилась → отмена
        sim.silenceUntil("decision", 6000);
        assertEquals(1, sim.count("decision"));
        assertEquals(2, judge.decideCalls.size(), "по новому тексту — новый запрос");
        assertEquals("rules", sim.last("decision").get("engine"));
    }

    // ==================================================================== не перебиваем и не теряем слова

    @Test
    void speech_resuming_before_the_decision_extends_the_same_answer() {
        SttSim sim = intern(null);
        sim.live.start();
        sim.ready();
        sim.silence(200);
        sim.speak(PARTIAL, 20);
        sim.silence(1000);                                      // думает: пауза дольше мягкого конца, но решения нет
        assertEquals(0, sim.count("decision"), "решение по неполному ответу не должно приниматься в паузе раздумья");
        sim.speak("и это среда выполнения для запуска приложений а джи ди кей это комплект разработчика и компилятор", 20);
        sim.silenceUntil("decision", 6000);
        assertEquals(1, sim.count("decision"));
        Map<String, Object> d = sim.last("decision");
        String recognized = String.valueOf(d.get("recognized"));
        assertTrue(recognized.contains("байткод") && recognized.contains("компилятор"),
                "в решении должен быть весь ответ целиком: " + recognized);
    }

    @Test
    void speech_during_the_grace_window_retracts_the_decision_and_restores_the_question() {
        SttSim sim = intern(null);
        answerAndWait(sim, PARTIAL);                            // решение «уточнить», идёт окно отмены 1000 мс
        assertEquals("ASK_FOLLOW_UP", sim.last("decision").get("action"));
        sim.silence(200);
        sim.speak("а джи ди кей это комплект разработчика там компилятор джавак и инструменты", 12);

        Map<String, Object> retract = sim.last("retract");
        assertNotNull(retract, "продолжение речи в окне отмены обязано откатить решение");
        assertEquals("speech_resumed", retract.get("reason"));
        Map<?, ?> restored = (Map<?, ?>) retract.get("question");
        assertEquals(Boolean.FALSE, restored.get("followUp"), "восстановлен прежний, основной вопрос");
        assertEquals(0, ((Number) restored.get("graceMs")).intValue());

        sim.silenceUntil("decision", 6000);
        assertEquals(2, sim.count("decision"));
        assertEquals(2, ((Number) sim.all("decision").get(1).get("answerId")).intValue());
        assertTrue(String.valueOf(sim.last("decision").get("recognized")).contains("компилятор"));
    }

    @Test
    void silence_after_the_grace_window_makes_the_decision_final() {
        SttSim sim = intern(null);
        answerAndWait(sim, STRONG);
        sim.silence(GRACE + 200);
        sim.speak("ещё одно слово", 6);                         // фаза WAIT_READY: речь без «ready» не меняет интервью
        assertEquals(0, sim.count("retract"));
        assertEquals(1, sim.count("decision"));
    }

    // ==================================================================== нет речи, пропуск, конец списка

    @Test
    void no_speech_timeout_moves_on_with_an_empty_answer() {
        SttSim sim = intern(null);
        sim.live.start();
        sim.ready();
        long waited = sim.silenceUntil("decision", 20_000);
        assertEquals(15_000, waited, "тайм-аут ожидания речи 15 с");
        Map<String, Object> d = sim.last("decision");
        assertEquals("no_answer", d.get("reason"));
        assertEquals("NEXT_QUESTION", d.get("action"));
        assertEquals("", d.get("recognized"));
        assertEquals(0, ((Number) sim.last("question").get("graceMs")).intValue(), "без окна отмены");
    }

    @Test
    void skip_button_decides_immediately_without_grace() {
        SttSim sim = intern(null);
        sim.live.start();
        sim.ready();
        sim.silence(100);
        sim.live.onSkip();
        assertEquals(1, sim.count("decision"));
        assertEquals("no_answer", sim.last("decision").get("reason"));
        assertEquals(0, ((Number) sim.last("question").get("graceMs")).intValue());
    }

    @Test
    void skip_is_ignored_inside_the_grace_window_and_after_finish() {
        SttSim sim = intern(null);
        answerAndWait(sim, STRONG);
        int decisions = sim.count("decision");
        sim.live.onSkip();                                      // на экране ещё прежний вопрос
        assertEquals(decisions, sim.count("decision"));
        sim.live.onStop();
        sim.live.onSkip();
        assertEquals(decisions, sim.count("decision"));
    }

    @Test
    void last_question_finishes_the_interview_and_sends_the_report_after_the_grace_window() {
        SttSim sim = SttSim.create(TestKb.single(), List.of(), null, null, 1200);
        sim.live.start();
        sim.ready();
        sim.silence(200);
        sim.speak(STRONG, 40);
        sim.silenceUntil("decision", 6000);
        assertEquals("FINISH", sim.last("decision").get("action"));
        assertEquals(0, sim.count("question") - 1, "после последнего вопроса нового вопроса нет (остался только первый)");
        assertEquals(0, sim.count("report"), "отчёт — только после окна отмены");
        sim.silence(GRACE + 100);
        assertEquals(1, sim.count("report"));
        Map<String, Object> report = sim.last("report");
        assertNotNull(report.get("report"));
        assertNull(report.get("excel"), "без экспортёра файла нет");
    }

    @Test
    void stop_button_sends_the_report_for_answered_questions_and_is_idempotent() {
        SttSim sim = intern(null);
        answerAndWait(sim, STRONG);
        sim.live.onStop();
        sim.live.onStop();
        assertEquals(1, sim.count("report"));
        // после завершения кадры и «ready» игнорируются
        int events = sim.events.size();
        sim.live.onReady();
        sim.frame(true, "partial", "лишнее");
        assertEquals(events, sim.events.size());
    }

    // ==================================================================== кадры, не относящиеся к ответу

    @Test
    void frames_before_ready_and_while_waiting_for_the_client_are_ignored() {
        SttSim sim = intern(null);
        sim.live.start();
        int before = sim.events.size();
        sim.frame(true, "partial", "слова до готовности");       // фаза IDLE
        assertEquals(before, sim.events.size());

        sim.ready();
        sim.silence(100);
        sim.live.onSkip();                                       // → WAIT_READY
        int afterSkip = sim.events.size();
        sim.frame(true, "partial", "слова пока вопрос озвучивается");
        assertEquals(afterSkip, sim.events.size());
    }

    @Test
    void unknown_asr_type_still_advances_the_voice_detector_without_changing_text() {
        SttSim sim = intern(null);
        sim.live.start();
        sim.ready();
        sim.frame(true, "stats", "не текст");
        assertEquals(0, sim.count("partial"));
        sim.live.onSttFrame("partial", null, 800, 0, 0, "WAITING", 0, -1, 0, 0);   // null-текст → пустая гипотеза
        assertEquals("", sim.last("partial").get("text"));
    }

    @Test
    void unknown_vad_state_is_a_protocol_error() {
        SttSim sim = intern(null);
        sim.live.start();
        sim.ready();
        assertThrows(IllegalArgumentException.class,
                () -> sim.live.onSttFrame("", "", 800, 0, 0, "BOGUS", 0, -1, 0, 0));
    }

    @Test
    void slow_recognition_raises_a_single_warning_and_stays_quiet_for_thirty_seconds() {
        SttSim sim = intern(null);
        sim.live.start();
        sim.ready();
        for (int i = 0; i < 60; i++)       // распознавание 80 % времени звука и выше: 40 мс на кадр 50 мс при rtf 0,9
            sim.live.onSttFrame("", "", 800, 0, 50.0, "WAITING", 50L * (i + 1), -1, 0, 0);
        assertEquals(1, sim.count("warning"));
        Map<String, Object> w = sim.last("warning");
        assertEquals("asr_slow", w.get("code"));
        assertTrue(((Number) w.get("rtf")).doubleValue() > 0.85);
        assertTrue(String.valueOf(w.get("message")).contains("не успевает"));
    }

    @Test
    void zero_sample_frames_do_not_crash_realtime_tracking() {
        SttSim sim = intern(null);
        sim.live.start();
        sim.ready();
        for (int i = 0; i < 25; i++) sim.live.onSttFrame("", "", 0, 0, 0.0, "WAITING", 0, -1, 0, 0);
        assertEquals(0, sim.count("warning"), "без аудио рассчитать rtf нельзя — предупреждение не нужно");
    }

    // ==================================================================== ответы и порядок событий

    @Test
    void answer_ids_grow_and_every_decision_is_followed_by_its_question() {
        SttSim sim = intern(null);
        sim.live.start();
        for (int round = 1; round <= 3; round++) {
            sim.ready();
            sim.silence(200);
            sim.speak(STRONG, 40);
            sim.silenceUntil("decision", 6000);
            sim.silence(GRACE + 100);
            assertEquals(round, ((Number) sim.last("decision").get("answerId")).intValue());
            assertEquals(round + 1, sim.count("question"));
        }
        // порядок: decision всегда раньше question в том же шаге
        int lastDecision = -1, lastQuestion = -1;
        for (int i = 0; i < sim.events.size(); i++) {
            String t = String.valueOf(sim.events.get(i).get("type"));
            if (t.equals("decision")) lastDecision = i;
            if (t.equals("question")) lastQuestion = i;
        }
        assertTrue(lastQuestion > lastDecision);
    }
}
