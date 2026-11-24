package ru.sttplatform.gateway;

import org.junit.jupiter.api.Test;
import ru.sttplatform.common.archive.ArchiveQueue;
import ru.sttplatform.common.json.Json;
import ru.sttplatform.gateway.api.ArchiveBrowserController;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArchiveBrowserControllerTest {
    @Test
    void onlyReportsConfirmedArtifactsAndRejectsUnknownTypes() throws Exception {
        var queue = mock(ArchiveQueue.class);
        UUID id = UUID.randomUUID();
        when(queue.find(id)).thenReturn(Json.MAPPER.readTree("{\"catalogVersion\":3,\"callStatus\":\"finished\",\"artifacts\":[]}"));
        var controller = new ArchiveBrowserController(queue);
        var pending = controller.find(id).getBody();
        assertNotNull(pending);
        assertEquals("pending", pending.path("audioStatus").asText());
        assertEquals("pending", pending.path("reportStatus").asText());
        assertEquals(404, controller.download(id, "arbitrary_file").getStatusCode().value());
        when(queue.find(id)).thenReturn(Json.MAPPER.readTree("{\"catalogVersion\":3,\"callStatus\":\"finished\",\"artifacts\":[{\"kind\":\"audio_wav\"},{\"kind\":\"report_xlsx\"}]}"));
        when(queue.open(id, "audio_wav")).thenReturn(new ByteArrayInputStream(new byte[]{1, 2}));
        var unavailable = controller.find(id).getBody();
        assertNotNull(unavailable);
        assertEquals("unavailable", unavailable.path("audioStatus").asText());
        assertEquals("unavailable", unavailable.path("reportStatus").asText());
        when(queue.available(id, "audio_wav")).thenReturn(true);
        when(queue.available(id, "report_xlsx")).thenReturn(true);
        var saved = controller.find(id).getBody();
        assertNotNull(saved);
        assertEquals("saved", saved.path("audioStatus").asText());
        assertEquals("ready", saved.path("reportStatus").asText());
        assertEquals(200, controller.download(id, "audio_wav").getStatusCode().value());
    }

    @Test
    void hidesPartiallyPublishedReportUntilJobIsReady() throws Exception {
        var queue = mock(ArchiveQueue.class);
        UUID id = UUID.randomUUID();
        var controller = new ArchiveBrowserController(queue);
        String prefix = "{\"catalogVersion\":3,\"callStatus\":\"finished\",\"artifacts\":[{\"kind\":\"report_xlsx\"}],\"finalizationStatus\":\"";
        when(queue.find(id)).thenReturn(Json.MAPPER.readTree(prefix + "running\"}"));
        when(queue.available(id, "report_xlsx")).thenReturn(true);
        assertEquals("processing", controller.find(id).getBody().path("reportStatus").asText());
        assertEquals(404, controller.download(id, "report_xlsx").getStatusCode().value());
        when(queue.find(id)).thenReturn(Json.MAPPER.readTree(prefix + "failed\"}"));
        assertEquals("error", controller.find(id).getBody().path("reportStatus").asText());
        when(queue.find(id)).thenReturn(Json.MAPPER.readTree(prefix + "ready\"}"));
        assertEquals("ready", controller.find(id).getBody().path("reportStatus").asText());
    }

    @Test
    void blocksTranscriptDownloadsUntilTheWholeFinalizationIsReady() throws Exception {
        var queue = mock(ArchiveQueue.class);
        UUID id = UUID.randomUUID();
        var controller = new ArchiveBrowserController(queue);
        when(queue.find(id)).thenReturn(Json.MAPPER.readTree("{\"finalizationStatus\":\"running\",\"artifacts\":["
                + "{\"kind\":\"transcripts_zip\"}]}"));
        when(queue.open(id, "transcripts_zip")).thenReturn(new ByteArrayInputStream(new byte[]{1, 2}));
        assertEquals(404, controller.download(id, "transcripts_zip").getStatusCode().value());

        when(queue.find(id)).thenReturn(Json.MAPPER.readTree("{\"finalizationStatus\":\"ready\",\"artifacts\":["
                + "{\"kind\":\"transcripts_zip\"}]}"));
        assertEquals(200, controller.download(id, "transcripts_zip").getStatusCode().value());
    }
}
