package ru.sttplatform.analytics.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.sttplatform.analytics.archive.CatalogStore;
import ru.sttplatform.analytics.archive.FinalizationJobStore;
import ru.sttplatform.analytics.engine.DecisionPolicy;
import ru.sttplatform.analytics.judge.FridaJudge;
import ru.sttplatform.analytics.judge.JudgeProperties;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinalizationScoringControllerTest {
    @Test
    void refusesUnknownCredentialsExpiredLeasesAndAlreadyPreparedResult() throws Exception {
        String token = "test-token-0123456789abcdef0123456789";
        var jobs = mock(FinalizationJobStore.class);
        var catalog = mock(CatalogStore.class);
        var controller = new FinalizationScoringController(jobs, catalog, mock(InterviewProperties.class),
                mock(DecisionPolicy.class), mock(FridaJudge.class), mock(JudgeProperties.class), new ObjectMapper(), token);
        UUID id = UUID.randomUUID(), lease = UUID.randomUUID();
        var request = new FinalizationScoringController.Request("vosk", List.of());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> controller.score("wrong", id, lease, request)).getStatusCode());
        verifyNoInteractions(jobs);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> controller.score(token, id, lease, request)).getStatusCode());
        when(jobs.current(id, lease)).thenReturn(new FinalizationJobStore.Lease(id, "{}", "{}", "{}",
                lease, Instant.now().plusSeconds(60), 1));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> controller.score(token, id, lease, request)).getStatusCode());
        verifyNoInteractions(catalog);
    }
}
