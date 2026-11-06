package ru.sttplatform.analytics.live;

import org.junit.jupiter.api.Test;
import ru.sttplatform.analytics.engine.*;
import ru.sttplatform.analytics.live.SttSim.VirtualJudge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static ru.sttplatform.analytics.live.LiveInterviewSlaTest.*;

/**
 * Стыки конвейера аналитики: подсказка (peek), уточнение Whisper, отчёт и экспорт, фоновая оценка FRIDA.
 * Все сценарии идут через публичный API LiveInterview — так же, как их вызывает InterviewWebSocketHandler.
 */
class LiveInterviewIntegrationsTest {

    private static SttSim single() { return SttSim.create(TestKb.single(), List.of(), null, null, 1200); }

    private static SttSim intern(AnswerJudge judge) { return SttSim.create(TestKb.get(), List.of(Level.INTERN), null, judge, 1200); }

    private static boolean await(SttSim sim, String type, int n, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (sim.count(type) >= n) return true;
            Thread.sleep(10);
        }
        return sim.count(type) >= n;
    }

    /** Ответ на единственный вопрос до решения FINISH (окно отмены ещё не закончилось). */
    private static void finishWithAnswer(SttSim sim, String answer) {
        sim.live.start();
        sim.ready();
        sim.silence(200);
        sim.speak(answer, 40);
        sim.silenceUntil("decision", 6000);
        assertEquals("FINISH", sim.last("decision").get("action"));
    }

    // ==================================================================== подсказка (peek)

    @Test
    void peek_is_ignored_when_it_makes_no_sense() {
        SttSim sim = intern(null);
        sim.live.start();
        sim.live.onPeek("текст", "peek-1");                       // IDLE: вопрос ещё не озвучен
        sim.ready();
        sim.live.onPeek(null, "peek-2");
        sim.live.onPeek("   ", "peek-3");
        assertEquals(0, sim.count("preview"));
        sim.silence(100);
        sim.live.onSkip();                                         // WAIT_READY
        sim.live.onPeek("текст", "peek-4");
        assertEquals(0, sim.count("preview"));
        sim.live.onStop();
        sim.live.onPeek("текст", "peek-5");
        assertEquals(0, sim.count("preview"));
    }

    @Test
    void peek_by_rules_is_read_only_and_does_not_touch_the_interview() {
        SttSim sim = intern(null);
        sim.live.start();
        sim.ready();
        sim.live.onPeek(PARTIAL, "peek-7");
        Map<String, Object> p = sim.last("preview");
        assertEquals("peek-7", p.get("requestId"));
        assertEquals("ASK_FOLLOW_UP", p.get("action"));
        assertEquals("rules", p.get("engine"));
        assertEquals(Boolean.TRUE, p.get("readOnly"));
        assertNotNull(p.get("suggestedFollowUp"));
        assertEquals(PARTIAL, p.get("recognized"));
        assertTrue(((Number) p.get("answerWords")).intValue() > 0);
        assertTrue(((Number) p.get("computeMs")).doubleValue() >= 0);
        assertEquals(0, sim.count("decision"), "подсказка не принимает решение");

        sim.live.onPeek(STRONG, "peek-8");
        Map<String, Object> strong = sim.last("preview");
        assertEquals("NEXT_QUESTION", strong.get("action"));
        assertNull(strong.get("suggestedFollowUp"));
        assertEquals(0, sim.count("decision"));
    }

    @Test
    void peek_by_model_uses_the_verdict_and_falls_back_on_errors_busy_model_or_unready_model() {
        VirtualJudge judge = new VirtualJudge(0, MODEL_OK, false);
        SttSim sim = intern(judge);
        sim.live.start();
        sim.ready();

        sim.live.onPeek(PARTIAL, "a");
        assertEquals("frida", sim.last("preview").get("engine"));
        assertEquals(1, judge.previewCalls);

        judge.nullPreview = true;                                  // модель занята — мгновенная подсказка по правилам
        sim.live.onPeek(PARTIAL, "b");
        assertEquals("rules", sim.last("preview").get("engine"));

        judge.nullPreview = false;
        judge.setReady(false);                                     // модель «выбита» — preview() даже не вызывается
        int calls = judge.previewCalls;
        sim.live.onPeek(PARTIAL, "c");
        assertEquals(calls, judge.previewCalls);
        assertEquals("rules", sim.last("preview").get("engine"));

        // модель ответила ошибкой: подсказка всё равно приходит, но по правилам
        AnswerJudge failing = new AnswerJudge() {
            @Override public CompletableFuture<Verdict> decide(JudgeInput i) { return new CompletableFuture<>(); }
            @Override public CompletableFuture<Verdict> preview(JudgeInput i) {
                return CompletableFuture.failedFuture(new IllegalStateException("нет связи"));
            }
            @Override public CompletableFuture<Verdict> score(JudgeInput i) { return new CompletableFuture<>(); }
            @Override public boolean ready() { return true; }
        };
        SttSim sim2 = intern(failing);
        sim2.live.start();
        sim2.ready();
        sim2.live.onPeek(PARTIAL, "d");
        assertEquals("rules", sim2.last("preview").get("engine"));
        assertEquals(1, sim2.count("preview"));
    }

    private static final Verdict MODEL_OK = new Verdict(List.of(new Verdict.Fact("факт", 0.9)), null, null,
            new int[]{0}, new double[]{1}, null, null, null, "frida", 20, false);

    // ==================================================================== конец озвучки вопроса

    @Test
    void tts_end_is_clamped_and_applied_to_the_next_answer() {
        SttSim sim = intern(null);
        sim.live.start();
        sim.ready();
        sim.live.onTtsEnd(-50);                                    // отрицательное время не бывает
        sim.live.onTtsEnd(1200);
        sim.silence(1500);
        sim.speak(STRONG, 40);
        sim.silenceUntil("decision", 6000);
        assertEquals(1, sim.count("decision"));
    }

    // ==================================================================== уточнение Whisper от STT

    @Test
    void refinement_is_applied_to_an_accepted_answer_and_the_report_waits_for_refine_done() throws Exception {
        SttSim sim = single();
        sim.live.setRefinementWait(3000);
        finishWithAnswer(sim, STRONG);
        sim.silence(GRACE + 100);
        assertEquals(1, sim.count("finalizing"));
        assertEquals("Жду уточнения распознанного текста…", sim.last("finalizing").get("message"));
        assertEquals(0, sim.count("report"), "отчёт ждёт уточнение");

        sim.live.onRefined(1, STRONG);
        Map<String, Object> refined = sim.last("refined");
        assertEquals(1, refined.get("answerId"));
        assertNotNull(refined.get("text"));
        assertEquals(0, sim.count("report"));

        sim.live.onRefineDone(1);
        assertTrue(await(sim, "report", 1, 3000), "после refine_done отчёт должен прийти");
    }

    @Test
    void report_is_sent_after_the_wait_limit_even_if_whisper_never_answers() throws Exception {
        SttSim sim = single();
        sim.live.setRefinementWait(150);
        finishWithAnswer(sim, STRONG);
        sim.silence(GRACE + 100);
        assertEquals(0, sim.count("report"));
        assertTrue(await(sim, "report", 1, 3000), "по истечении ожидания отчёт обязан уйти без уточнения");
    }

    @Test
    void refinement_wait_can_be_disabled_and_clamped() {
        SttSim sim = single();
        sim.live.setRefinementWait(-5);                            // отрицательное → 0 → ожидания нет
        finishWithAnswer(sim, STRONG);
        sim.silence(GRACE + 100);
        assertEquals(1, sim.count("report"));
        assertEquals(0, sim.count("finalizing"));
    }

    @Test
    void refinement_for_unknown_or_blank_input_is_ignored_and_errors_are_reported() {
        SttSim sim = single();
        sim.live.setRefinementWait(1000);
        sim.live.start();
        sim.live.onRefined(99, "текст");                           // такого ответа не было
        sim.ready();
        sim.silence(200);
        sim.speak(STRONG, 40);
        sim.silenceUntil("decision", 6000);
        sim.live.onRefined(1, "   ");
        sim.live.onRefined(1, null);
        assertEquals(0, sim.count("refined"));

        sim.live.onRefineDone(1, "Whisper упал");
        Map<String, Object> err = sim.last("whisper_error");
        assertEquals(1, err.get("answerId"));
        assertEquals("Whisper упал", err.get("message"));
        sim.live.onRefineDone(2, null);
        sim.live.onRefineDone(3, "  ");
        assertEquals(1, sim.count("whisper_error"));
    }

    @Test
    void retracted_answer_does_not_accept_a_late_refinement() {
        SttSim sim = single();
        sim.live.setRefinementWait(1000);
        sim.live.start();
        sim.ready();
        sim.silence(200);
        sim.speak(PARTIAL, 20);
        sim.silenceUntil("decision", 6000);
        sim.silence(200);
        sim.speak("и ещё немного", 10);                            // в окне отмены → retract
        assertEquals(1, sim.count("retract"));
        sim.live.onRefined(1, "поздний текст прежнего ответа");
        assertEquals(0, sim.count("refined"), "ссылка на откатанный ответ удалена");
    }

    // ==================================================================== отчёт и экспорт

    @Test
    void report_contains_excel_info_when_export_succeeds_and_error_when_it_fails() {
        SttSim ok = single();
        ok.live.setExport(d -> new ReportExporter.Result("r.xlsx", "/tmp/r.xlsx", "/reports/r.xlsx"), "  Имя  ", Map.of("k", "v"));
        finishWithAnswer(ok, STRONG);
        ok.silence(GRACE + 100);
        Map<?, ?> excel = (Map<?, ?>) ok.last("report").get("excel");
        assertEquals("r.xlsx", excel.get("file"));
        assertEquals("/tmp/r.xlsx", excel.get("path"));
        assertEquals("/reports/r.xlsx", excel.get("url"));
        assertNull(ok.last("report").get("excelError"));

        SttSim bad = single();
        bad.live.setExport(d -> { throw new java.io.IOException("диск полон"); }, null, null);
        finishWithAnswer(bad, STRONG);
        bad.silence(GRACE + 100);
        assertEquals("диск полон", bad.last("report").get("excelError"));
        assertNull(bad.last("report").get("excel"));
    }

    @Test
    void export_receives_candidate_and_settings() {
        List<InterviewDetails> seen = new ArrayList<>();
        SttSim sim = single();
        sim.live.setExport(d -> { seen.add(d); return new ReportExporter.Result("f", "p", null); }, " Иван ", Map.of("Режим", "тест"));
        finishWithAnswer(sim, STRONG);
        sim.silence(GRACE + 100);
        assertEquals(1, seen.size());
        assertEquals("Иван", seen.get(0).candidate());
        assertEquals("тест", seen.get(0).settings().get("Режим"));
        assertFalse(seen.get(0).finishedEarly());
    }

    // ==================================================================== фоновая оценка FRIDA

    @Test
    void background_scoring_errors_are_reported_without_blocking_the_decision() {
        AnswerJudge judge = new AnswerJudge() {
            @Override public CompletableFuture<Verdict> decide(JudgeInput i) { return new CompletableFuture<>(); }
            @Override public CompletableFuture<Verdict> preview(JudgeInput i) { return null; }
            @Override public CompletableFuture<Verdict> score(JudgeInput i) {
                return CompletableFuture.failedFuture(new IllegalStateException("перегрузка"));
            }
            @Override public boolean ready() { return true; }
        };
        SttSim sim = intern(judge);
        sim.live.start();
        sim.ready();
        sim.silence(200);
        sim.speak(STRONG, 40);
        sim.silenceUntil("decision", 6000);
        Map<String, Object> err = sim.last("judge_error");
        assertNotNull(err);
        assertEquals(1, err.get("answerId"));
        assertTrue(String.valueOf(err.get("message")).contains("перегрузка"));
    }

    @Test
    void background_scoring_that_throws_synchronously_is_reported() {
        AnswerJudge judge = new AnswerJudge() {
            @Override public CompletableFuture<Verdict> decide(JudgeInput i) { return new CompletableFuture<>(); }
            @Override public CompletableFuture<Verdict> preview(JudgeInput i) { return null; }
            @Override public CompletableFuture<Verdict> score(JudgeInput i) { throw new IllegalStateException("не готов"); }
            @Override public boolean ready() { return true; }
        };
        SttSim sim = intern(judge);
        sim.live.start();
        sim.ready();
        sim.silence(200);
        sim.speak(STRONG, 40);
        sim.silenceUntil("decision", 6000);
        assertEquals("не готов", sim.last("judge_error").get("message"));
    }

    @Test
    void scoring_result_arriving_after_a_retract_is_dropped() {
        CompletableFuture<Verdict> late = new CompletableFuture<>();
        AnswerJudge judge = new AnswerJudge() {
            @Override public CompletableFuture<Verdict> decide(JudgeInput i) { return new CompletableFuture<>(); }
            @Override public CompletableFuture<Verdict> preview(JudgeInput i) { return null; }
            @Override public CompletableFuture<Verdict> score(JudgeInput i) { return late; }
            @Override public boolean ready() { return true; }
        };
        SttSim sim = intern(judge);
        sim.live.start();
        sim.ready();
        sim.silence(200);
        sim.speak(PARTIAL, 20);
        sim.silenceUntil("decision", 6000);
        sim.silence(100);
        sim.speak("и компилятор", 10);                             // откат
        assertEquals(1, sim.count("retract"));
        assertDoesNotThrow(() -> late.complete(MODEL_OK));         // устаревшая оценка применяться не должна
        assertEquals(0, sim.count("judge_error"));
    }

    @Test
    void report_is_not_blocked_by_background_scoring_that_never_finishes() {
        AnswerJudge judge = new AnswerJudge() {
            @Override public CompletableFuture<Verdict> decide(JudgeInput i) { return new CompletableFuture<>(); }
            @Override public CompletableFuture<Verdict> preview(JudgeInput i) { return null; }
            @Override public CompletableFuture<Verdict> score(JudgeInput i) { return new CompletableFuture<>(); }
            @Override public boolean ready() { return true; }
        };
        SttSim sim = SttSim.create(TestKb.single(), List.of(), null, judge, 1200);
        sim.live.setRefinementWait(0);                             // не ждать оценку: отчёт уходит сразу
        finishWithAnswer(sim, STRONG);
        sim.silence(GRACE + 100);
        assertEquals(1, sim.count("report"), "незавершённая оценка модели не должна задерживать отчёт");
    }

    // ==================================================================== второй проход (устаревший путь через аудио)

    private static final class Audio {
        final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
        final LiveInterview live;
        Audio(Transcriber transcriber) {
            InterviewSession session = new InterviewSession(TestKb.single(), List.of(), new DecisionPolicy(2, 0.08));
            live = new LiveInterview(new LiveInterviewTest.ScriptedEngine(STRONG, 60), session,
                    new EndpointDetector(new EndpointDetector.Config()), events::add);
            if (transcriber != null) live.setTranscriber(transcriber, 2000);
        }

        void answer() {
            live.start();
            live.onReady();
            for (int i = 0; i < 8; i++) live.onAudio(Pcm.frame(false), Pcm.FRAME_SAMPLES * 2);
            for (int i = 0; i < 60; i++) live.onAudio(Pcm.frame(true), Pcm.FRAME_SAMPLES * 2);
            for (int i = 0; i < 100 && !has("decision"); i++) live.onAudio(Pcm.frame(false), Pcm.FRAME_SAMPLES * 2);
        }

        boolean has(String type) { return events.stream().anyMatch(e -> type.equals(e.get("type"))); }

        Map<String, Object> last(String type) {
            for (int i = events.size() - 1; i >= 0; i--) if (type.equals(events.get(i).get("type"))) return events.get(i);
            return null;
        }
    }

    @Test
    void plain_second_pass_replaces_the_text_and_failures_become_whisper_errors() {
        AtomicInteger bytes = new AtomicInteger();
        Audio ok = new Audio(pcm -> { bytes.set(pcm.length); return CompletableFuture.completedFuture("точный текст ответа джей ви эм"); });
        ok.answer();
        assertTrue(bytes.get() > 16_000, "второй проход получил аудио ответа");
        assertNotNull(ok.last("refined"));
        assertNull(ok.last("whisper_error"));

        Audio blank = new Audio(pcm -> CompletableFuture.completedFuture("  "));
        blank.answer();
        assertNull(blank.last("refined"));

        Audio failing = new Audio(pcm -> CompletableFuture.failedFuture(new IllegalStateException("whisper недоступен")));
        failing.answer();
        assertTrue(String.valueOf(failing.last("whisper_error").get("message")).contains("whisper недоступен"));
    }

    @Test
    void detailed_second_pass_reports_words_and_handles_empty_text_and_failures() {
        Audio ok = new Audio(new DetailedTranscriber() {
            @Override public CompletableFuture<Transcript> transcribeDetailed(byte[] pcm) {
                return CompletableFuture.completedFuture(new Transcript("точный текст", List.of(new Word("точный", 0, .4, .9))));
            }
            @Override public CompletableFuture<String> transcribe(byte[] pcm) { return CompletableFuture.completedFuture("x"); }
        });
        ok.answer();
        Map<String, Object> refined = ok.last("refined");
        assertNotNull(refined);
        assertEquals(1, refined.get("answerId"));
        assertEquals(1, ((List<?>) refined.get("words")).size());
        assertTrue(((Number) refined.get("trimStartMs")).longValue() >= 0);

        Audio empty = new Audio(new DetailedTranscriber() {
            @Override public CompletableFuture<Transcript> transcribeDetailed(byte[] pcm) {
                return CompletableFuture.completedFuture(new Transcript(" ", List.of()));
            }
            @Override public CompletableFuture<String> transcribe(byte[] pcm) { return null; }
        });
        empty.answer();
        assertEquals("Whisper вернул пустую расшифровку", empty.last("whisper_error").get("message"));

        Audio failing = new Audio(new DetailedTranscriber() {
            @Override public CompletableFuture<Transcript> transcribeDetailed(byte[] pcm) {
                return CompletableFuture.failedFuture(new IllegalStateException("таймаут"));
            }
            @Override public CompletableFuture<String> transcribe(byte[] pcm) { return null; }
        });
        failing.answer();
        assertTrue(String.valueOf(failing.last("whisper_error").get("message")).contains("таймаут"));
    }

    @Test
    void second_pass_adds_a_finalizing_message_before_the_report() {
        Audio a = new Audio(pcm -> CompletableFuture.completedFuture("текст"));
        a.answer();
        for (int i = 0; i < 40; i++) a.live.onAudio(Pcm.frame(false), Pcm.FRAME_SAMPLES * 2);
        assertTrue(a.has("finalizing"));
        assertTrue(a.has("report"));
    }

    @Test
    void audio_frames_outside_an_answer_are_ignored() {
        Audio a = new Audio(null);
        a.live.start();
        int n = a.events.size();
        a.live.onAudio(Pcm.frame(true), Pcm.FRAME_SAMPLES * 2);    // IDLE
        assertEquals(n, a.events.size());
    }
}
