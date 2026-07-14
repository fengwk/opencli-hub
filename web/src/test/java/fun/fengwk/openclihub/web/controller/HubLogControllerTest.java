package fun.fengwk.openclihub.web.controller;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fun.fengwk.convention4j.springboot.starter.web.result.WebExceptionResultHandlerAutoConfiguration;
import fun.fengwk.openclihub.core.log.HubLogService;
import fun.fengwk.openclihub.core.log.HubLogStream;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.log.HubLogContentDTO;
import fun.fengwk.openclihub.share.model.log.HubLogSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Covers JSON tails, safe source validation, and binary fixed-log downloads. */
@ImportAutoConfiguration(WebExceptionResultHandlerAutoConfiguration.class)
@WebMvcTest(
    controllers = HubLogController.class,
    properties = "logging.file.path=${java.io.tmpdir}/opencli-hub-web-test")
class HubLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HubLogService logService;

    /** System defaults and lowercase fixed instance sources reach the matching core methods. */
    @Test
    void shouldTailSystemAndInstanceLogs() throws Exception {
        when(logService.tailSystem(HubLogService.DEFAULT_TAIL_LINES)).thenReturn(logContent(HubLogSource.SYSTEM));
        when(logService.tailInstance("19", HubLogSource.CHROME, 2)).thenReturn(logContent(HubLogSource.CHROME));

        mockMvc.perform(get("/api/logs/system"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.source").value("SYSTEM"));
        mockMvc.perform(get("/api/instances/19/logs").param("source", "chrome").param("lines", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.source").value("CHROME"));

        verify(logService).tailSystem(HubLogService.DEFAULT_TAIL_LINES);
        verify(logService).tailInstance("19", HubLogSource.CHROME, 2);
    }

    /** Invalid paths-as-sources and invalid line bounds fail before a core file operation. */
    @Test
    void shouldRejectInvalidSourceAndLineBounds() throws Exception {
        mockMvc.perform(get("/api/instances/19/logs").param("source", "../../etc/passwd"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(HubErrorCodes.INSTANCE_LOG_SOURCE_INVALID.getCode()));
        verifyNoInteractions(logService);

        mockMvc.perform(get("/api/logs/system").param("lines", "0"))
            .andExpect(status().isBadRequest());
        verify(logService, never()).tailSystem(0);
    }

    /** Both binary routes set length and attachment headers and close their owned streams. */
    @Test
    void shouldDownloadSystemAndInstanceLogs() throws Exception {
        AtomicBoolean systemClosed = new AtomicBoolean();
        AtomicBoolean instanceClosed = new AtomicBoolean();
        when(logService.openSystemDownload()).thenReturn(stream("opencli-hub-all.log", "system\n", systemClosed));
        when(logService.openInstanceDownload("19", HubLogSource.X11VNC))
            .thenReturn(stream("x11vnc.log", "vnc\n", instanceClosed));

        MvcResult system = mockMvc.perform(get("/api/logs/system/download"))
            .andExpect(request().asyncStarted())
            .andReturn();
        mockMvc.perform(asyncDispatch(system))
            .andExpect(status().isOk())
            .andExpect(content().bytes("system\n".getBytes(StandardCharsets.UTF_8)))
            .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 7))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                org.hamcrest.Matchers.startsWith("attachment;")));

        MvcResult instance = mockMvc.perform(get("/api/instances/19/logs/download").param("source", "x11vnc"))
            .andExpect(request().asyncStarted())
            .andReturn();
        mockMvc.perform(asyncDispatch(instance))
            .andExpect(status().isOk())
            .andExpect(content().bytes("vnc\n".getBytes(StandardCharsets.UTF_8)))
            .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 4));
        org.assertj.core.api.Assertions.assertThat(systemClosed).isTrue();
        org.assertj.core.api.Assertions.assertThat(instanceClosed).isTrue();
    }

    /** Missing fixed logs preserve the Hub JSON code for tails and HTTP 404 for downloads. */
    @Test
    void shouldMapMissingLogFiles() throws Exception {
        when(logService.tailSystem(1)).thenThrow(HubErrorCodes.LOG_FILE_NOT_FOUND.asThrowable("missing"));
        when(logService.openInstanceDownload("19", HubLogSource.OPENBOX))
            .thenThrow(HubErrorCodes.LOG_FILE_NOT_FOUND.asThrowable("missing"));

        mockMvc.perform(get("/api/logs/system").param("lines", "1"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(HubErrorCodes.LOG_FILE_NOT_FOUND.getCode()));
        mockMvc.perform(get("/api/instances/19/logs/download").param("source", "openbox"))
            .andExpect(status().isNotFound());
    }

    private static HubLogContentDTO logContent(HubLogSource source) {
        HubLogContentDTO content = new HubLogContentDTO();
        content.setSource(source);
        content.setContent("line\n");
        return content;
    }

    private static HubLogStream stream(String fileName, String content, AtomicBoolean closed) {
        return new HubLogStream(fileName, content.getBytes(StandardCharsets.UTF_8).length,
            new CloseTrackingInputStream(content.getBytes(StandardCharsets.UTF_8), closed));
    }

    private static final class CloseTrackingInputStream extends ByteArrayInputStream {

        private final AtomicBoolean closed;

        private CloseTrackingInputStream(byte[] bytes, AtomicBoolean closed) {
            super(bytes);
            this.closed = closed;
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            super.close();
        }

    }

}
