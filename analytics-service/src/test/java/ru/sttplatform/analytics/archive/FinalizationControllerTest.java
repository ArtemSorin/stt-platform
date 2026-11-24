package ru.sttplatform.analytics.archive;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinalizationControllerTest {
    private static final String TOKEN = "test-token-0123456789abcdef0123456789";

    @Test
    void rejectsInvalidSecretsAndExpiredLeases() throws Exception {
        FinalizationJobStore jobs = mock(FinalizationJobStore.class);
        FinalizationController controller = new FinalizationController(jobs, TOKEN);
        var forbidden = assertThrows(ResponseStatusException.class,
                () -> controller.claim("wrong", new FinalizationController.DurationRequest(10)));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
        verifyNoInteractions(jobs);

        var invalidDuration = assertThrows(ResponseStatusException.class,
                () -> controller.claim(TOKEN, new FinalizationController.DurationRequest(0)));
        assertEquals(HttpStatus.BAD_REQUEST, invalidDuration.getStatusCode());
        UUID id = UUID.randomUUID();
        UUID lease = UUID.randomUUID();
        when(jobs.renew(id, lease, Duration.ofSeconds(60))).thenReturn(false);
        var expired = assertThrows(ResponseStatusException.class,
                () -> controller.renew(TOKEN, id, lease, new FinalizationController.DurationRequest(60)));
        assertEquals(HttpStatus.CONFLICT, expired.getStatusCode());
    }

    @Test
    void emptyQueueReturnsNoContent() throws Exception {
        FinalizationJobStore jobs = mock(FinalizationJobStore.class);
        FinalizationController controller = new FinalizationController(jobs, TOKEN);
        assertEquals(HttpStatus.NO_CONTENT,
                controller.claim(TOKEN, new FinalizationController.DurationRequest(60)).getStatusCode());
    }

    @Test
    void acceptsOnlyBoundAndCompleteResultManifests() throws Exception {
        FinalizationJobStore jobs = mock(FinalizationJobStore.class);
        FinalizationController controller = new FinalizationController(jobs, TOKEN);
        UUID id = UUID.randomUUID(), lease = UUID.randomUUID();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var missing = mapper.readTree("{\"interviewId\":\"" + id + "\",\"transcriptSource\":\"vosk\",\"artifacts\":[]}");
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.result(TOKEN, id, new FinalizationController.ResultRequest(lease, missing))).getStatusCode());
        var valid = mapper.readTree("{\"interviewId\":\"" + id + "\",\"transcriptSource\":\"vosk\",\"artifacts\":[{\"kind\":\"report_xlsx\",\"stagingKey\":\"staging/" + id + "/attempt/report\",\"sizeBytes\":8,\"sha256\":\"" + "a".repeat(64) + "\"}]}");
        when(jobs.saveResult(id, lease, valid.toString())).thenReturn(true);
        controller.result(TOKEN, id, new FinalizationController.ResultRequest(lease, valid));
        var fallback = mapper.readTree(valid.toString().replace("\"vosk\"", "\"vosk_fallback\""));
        when(jobs.saveResult(id, lease, fallback.toString())).thenReturn(true);
        controller.result(TOKEN, id, new FinalizationController.ResultRequest(lease, fallback));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.result(TOKEN, UUID.randomUUID(), new FinalizationController.ResultRequest(lease, valid))).getStatusCode());
    }
}
