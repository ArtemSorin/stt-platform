package ru.sttplatform.analytics.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import ru.sttplatform.analytics.engine.InterviewDetails;
import ru.sttplatform.analytics.engine.InterviewKnowledge;
import ru.sttplatform.analytics.engine.DecisionPolicy;
import ru.sttplatform.analytics.judge.FridaJudge;
import ru.sttplatform.analytics.judge.JudgeProperties;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InterviewWebLayerTest {

    // ---------------------------------------------------------------- токен внутреннего канала

    private static ServerHttpRequest request(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.add("X-Internal-Token", token);
        return (ServerHttpRequest) Proxy.newProxyInstance(ServerHttpRequest.class.getClassLoader(), new Class<?>[]{ServerHttpRequest.class},
                (p, m, a) -> m.getName().equals("getHeaders") ? headers : null);
    }

    private static final class Response {
        HttpStatusCode status;
        final ServerHttpResponse proxy = (ServerHttpResponse) Proxy.newProxyInstance(ServerHttpResponse.class.getClassLoader(),
                new Class<?>[]{ServerHttpResponse.class}, (p, m, a) -> { if (m.getName().equals("setStatusCode")) status = (HttpStatusCode) a[0]; return null; });
    }

    private static final String TOKEN = "0123456789abcdef0123456789abcdef0123";

    @Test
    void handshake_accepts_only_the_exact_shared_secret() {
        var interceptor = new InternalEventsHandshakeInterceptor(TOKEN);
        Response ok = new Response();
        assertTrue(interceptor.beforeHandshake(request(TOKEN), ok.proxy, null, new HashMap<>()));
        assertNull(ok.status);
        for (String bad : new String[]{null, "", "другой", TOKEN + "x", TOKEN.substring(1), TOKEN.toUpperCase()}) {
            Response denied = new Response();
            assertFalse(interceptor.beforeHandshake(request(bad), denied.proxy, null, new HashMap<>()), "токен: " + bad);
            assertEquals(HttpStatus.UNAUTHORIZED, denied.status);
        }
    }

    @Test
    void handshake_is_closed_when_the_server_secret_is_missing_or_too_short() {
        for (String weak : new String[]{null, "", "короткий"}) {
            var interceptor = new InternalEventsHandshakeInterceptor(weak);
            Response r = new Response();
            assertFalse(interceptor.beforeHandshake(request(weak == null ? "" : weak), r.proxy, null, new HashMap<>()),
                    "без секрета длиной ≥ 32 байт канал не открывается: " + weak);
            assertEquals(HttpStatus.UNAUTHORIZED, r.status);
        }
        assertDoesNotThrow(() -> new InternalEventsHandshakeInterceptor(TOKEN).afterHandshake(null, null, null, null));
    }

    // ---------------------------------------------------------------- настройки

    @Test
    void interview_properties_have_documented_defaults() {
        var p = new InterviewProperties(null, null, null, null, null, null, null, null, null, " ", null, null, null, null, null);
        assertEquals(2, p.maxFollowUps());
        assertEquals(450, p.endSilenceMs());
        assertEquals(15_000, p.noSpeechTimeoutMs());
        assertEquals(0.08, p.weakCoverage());
        assertTrue(p.whisperEnabled());
        assertEquals(25_000, p.whisperWaitMs());
        assertEquals("./reports", p.reportsDir());
        assertTrue(p.reportDownload());
        assertEquals(150, p.nextExtraMs());
        assertEquals(600, p.otherNextExtraMs());
        assertEquals(1500, p.followUpExtraMs());
        assertEquals(1000, p.graceMs());
        var custom = new InterviewProperties("q", "g", "s", 5, 1, 2, 0.5, false, 3, "/r", false, 4, 5, 6, 7);
        assertEquals(5, custom.maxFollowUps());
        assertFalse(custom.reportDownload());
        assertEquals("/r", custom.reportsDir());
    }

    // ---------------------------------------------------------------- загрузка банка вопросов

    @Test
    void knowledge_is_loaded_from_the_classpath_by_default_and_from_files_when_configured(@TempDir Path dir) throws Exception {
        var config = new InterviewConfig();
        var defaults = new InterviewProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        InterviewKnowledge bundled = config.interviewKnowledge(defaults);
        assertTrue(bundled.questions().length > 10);

        Path questions = dir.resolve("q.tsv");
        Files.writeString(questions, "Стажёр\tJava\tЧто такое JVM?\tВиртуальная машина Java\t\t\n", StandardCharsets.UTF_8);
        Path glossary = dir.resolve("g.txt");
        Files.writeString(glossary, Files.readString(Path.of(InterviewWebLayerTest.class.getResource("/interview/glossary.txt").toURI()), StandardCharsets.UTF_8));
        Path spoken = dir.resolve("s.txt");
        Files.writeString(spoken, "", StandardCharsets.UTF_8);
        var external = new InterviewProperties(questions.toString(), glossary.toString(), spoken.toString(), null, null, null, null,
                null, null, null, null, null, null, null, null);
        assertEquals(1, config.interviewKnowledge(external).questions().length);
        assertThrows(java.io.IOException.class, () -> config.interviewKnowledge(new InterviewProperties(
                dir.resolve("missing.tsv").toString(), null, null, null, null, null, null, null, null, null, null, null, null, null, null)));
    }

    @Test
    void policy_and_judge_beans_use_the_configured_thresholds() {
        var config = new InterviewConfig();
        var props = new InterviewProperties(null, null, null, 4, null, null, 0.2, null, null, null, null, null, null, null, null);
        JudgeProperties judge = new JudgeProperties(false, null, null, null, null, null, null, null, null, null, null, null,
                0.7, 0.3, 0.8, 0.1, 0.02, 0.2);
        assertNotNull(config.decisionPolicy(props, judge));
        FridaJudge frida = config.fridaJudge(judge);
        assertFalse(frida.ready(), "выключенная модель недоступна");
    }

    // ---------------------------------------------------------------- Excel-отчёты

    private static InterviewDetails details(String candidate) {
        var session = new ru.sttplatform.analytics.engine.InterviewSession(ru.sttplatform.analytics.engine.TestKb.get(),
                List.of(ru.sttplatform.analytics.engine.Level.INTERN), new DecisionPolicy(2, 0.08));
        var prompt = session.start();
        String answer = ru.sttplatform.analytics.engine.TestKb.get().byId(prompt.questionId()).question().reference();
        session.submit(answer, answer, new ru.sttplatform.analytics.engine.AnswerTiming(1500, 9000));
        return session.details(candidate, Instant.now().minusSeconds(300), Map.of("Порог тишины", "450 мс"));
    }

    @Test
    void reports_are_saved_with_unique_names_and_found_only_inside_the_directory(@TempDir Path dir) throws Exception {
        var service = new ExcelReportService(new InterviewProperties(null, null, null, null, null, null, null, null, null,
                dir.resolve("reports").toString(), true, null, null, null, null));
        var first = service.export(details("Анна"));
        var second = service.export(details("Анна"));
        assertNotEquals(first.fileName(), second.fileName(), "одинаковые имена не затирают друг друга");
        assertTrue(first.downloadUrl().startsWith("/api/reports/"));
        assertTrue(Files.size(Path.of(first.path())) > 1000);
        assertNotNull(service.find(first.fileName()));
        assertNull(service.find("../" + first.fileName()));
        assertNull(service.find("нет-такого.xlsx"));
        assertNull(service.find(null));
        assertNull(service.find("a/b.xlsx"));
    }

    @Test
    void downloads_can_be_switched_off(@TempDir Path dir) throws Exception {
        var service = new ExcelReportService(new InterviewProperties(null, null, null, null, null, null, null, null, null,
                dir.toString(), false, null, null, null, null));
        var result = service.export(details("Борис"));
        assertNull(result.downloadUrl());
        assertNull(service.find(result.fileName()));
    }
}
