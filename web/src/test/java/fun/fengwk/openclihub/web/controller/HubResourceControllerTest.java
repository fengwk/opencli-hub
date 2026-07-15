package fun.fengwk.openclihub.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fun.fengwk.convention4j.springboot.starter.web.result.WebExceptionResultHandlerAutoConfiguration;
import fun.fengwk.openclihub.core.resource.model.HubResourceListRequest;
import fun.fengwk.openclihub.core.resource.model.HubResourceStream;
import fun.fengwk.openclihub.core.resource.model.HubResourceUploadRequest;
import fun.fengwk.openclihub.core.resource.service.HubResourceService;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.resource.HubResourceDateSummaryDTO;
import fun.fengwk.openclihub.share.model.resource.HubResourceItemDTO;
import fun.fengwk.openclihub.share.model.resource.HubResourceSource;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Covers multipart, browse, binary stream and delete resource routes. */
@ImportAutoConfiguration(WebExceptionResultHandlerAutoConfiguration.class)
@WebMvcTest(
    controllers = HubResourceController.class,
    properties = "logging.file.path=${java.io.tmpdir}/opencli-hub-web-test")
class HubResourceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HubResourceService resourceService;

    /** Multipart files are converted to core upload items and returned with HTTP 201. */
    @Test
    void shouldUpload() throws Exception {
        when(resourceService.upload(any())).thenReturn(new HubResourceService.UploadResult(
            "upload-1", LocalDate.of(2026, 7, 13), List.of(item("hello.txt"))));
        MockMultipartFile file = new MockMultipartFile(
            "files", "hello.txt", "text/plain", "hello".getBytes());

        mockMvc.perform(multipart("/api/resources/uploads").file(file)
                .param("date", "2026-07-13"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.group").value("upload-1"))
            .andExpect(jsonPath("$.data.items[0].fileName").value("hello.txt"));

        ArgumentCaptor<HubResourceUploadRequest> captor =
            ArgumentCaptor.forClass(HubResourceUploadRequest.class);
        verify(resourceService).upload(captor.capture());
        assertThat(captor.getValue().getItems().get(0).getOriginalFileName())
            .isEqualTo("hello.txt");
    }

    /** Upload size violations retain their domain 413 response. */
    @Test
    void shouldMapUploadTooLarge() throws Exception {
        when(resourceService.upload(any()))
            .thenThrow(HubErrorCodes.RESOURCE_UPLOAD_TOO_LARGE.asThrowable("too large"));
        MockMultipartFile file = new MockMultipartFile(
            "files", "large.bin", "application/octet-stream", new byte[] {1});

        mockMvc.perform(multipart("/api/resources/uploads").file(file))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value(
                HubErrorCodes.RESOURCE_UPLOAD_TOO_LARGE.getCode()));
    }

    /** Resource browse requests preserve date, source, sort and pagination values. */
    @Test
    void shouldBrowse() throws Exception {
        HubResourceDateSummaryDTO summary = new HubResourceDateSummaryDTO();
        summary.setDate("2026-07-13");
        when(resourceService.listDateSummaries()).thenReturn(List.of(summary));
        when(resourceService.listDay(any())).thenReturn(List.of(item("hello.txt")));

        mockMvc.perform(get("/api/resources/dates"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].date").value("2026-07-13"));
        mockMvc.perform(get("/api/resources").param("date", "2026-07-13")
                .param("source", "UPLOAD").param("sort", "NAME_ASC")
                .param("page", "1").param("pageSize", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].fileName").value("hello.txt"));

        ArgumentCaptor<HubResourceListRequest> captor =
            ArgumentCaptor.forClass(HubResourceListRequest.class);
        verify(resourceService).listDay(captor.capture());
        assertThat(captor.getValue().getSource()).isEqualTo(HubResourceSource.UPLOAD);
        assertThat(captor.getValue().getSort())
            .isEqualTo(HubResourceListRequest.ResourceSort.NAME_ASC);
    }

    /** Inline binary responses preserve bytes and standard content headers. */
    @Test
    void shouldStreamInline() throws Exception {
        AtomicBoolean leaseClosed = new AtomicBoolean();
        when(resourceService.openForRead(
            "/resources/2026-07-13/upload-1/dir/hello.txt"))
            .thenReturn(stream(leaseClosed));

        MvcResult started = mockMvc.perform(get(
                "/api/resources/2026-07-13/upload-1/dir/hello.txt")
                .param("inline", "true"))
            .andExpect(request().asyncStarted())
            .andReturn();
        mockMvc.perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andExpect(content().bytes("hello".getBytes()))
            .andExpect(content().contentType("text/plain"))
            .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 5))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                org.hamcrest.Matchers.startsWith("inline;")));
        assertThat(leaseClosed).isTrue();
    }

    /** Encoded special-name segments are decoded once before the core virtual path is rebuilt. */
    @Test
    void shouldPassDecodedSpecialResourcePathToCore() throws Exception {
        String virtualPath = "/resources/2026-07-13/upload-1/子 目录/报告 #1?.txt";
        when(resourceService.openForRead(virtualPath)).thenReturn(stream(new AtomicBoolean()));
        URI encoded = URI.create("/api/resources/2026-07-13/upload-1/"
            + "%E5%AD%90%20%E7%9B%AE%E5%BD%95/"
            + "%E6%8A%A5%E5%91%8A%20%231%3F.txt");

        MvcResult started = mockMvc.perform(get(encoded))
            .andExpect(request().asyncStarted())
            .andReturn();
        mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk());

        verify(resourceService).openForRead(virtualPath);
    }

    /** Download defaults to attachment disposition while preserving the original name. */
    @Test
    void shouldStreamAsAttachmentByDefault() throws Exception {
        when(resourceService.openForRead(
            "/resources/2026-07-13/upload-1/hello.txt"))
            .thenReturn(stream(new AtomicBoolean()));

        MvcResult started = mockMvc.perform(get(
                "/api/resources/2026-07-13/upload-1/hello.txt"))
            .andExpect(request().asyncStarted())
            .andReturn();
        mockMvc.perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                org.hamcrest.Matchers.startsWith("attachment;")));
    }

    /** Missing binary resources are mapped before streaming starts. */
    @Test
    void shouldMapBinaryOpenError() throws Exception {
        when(resourceService.openForRead(any()))
            .thenThrow(HubErrorCodes.RESOURCE_NOT_FOUND.asThrowable("missing"));

        mockMvc.perform(get("/api/resources/2026-07-13/upload-1/missing.txt"))
            .andExpect(status().isNotFound());
    }

    /** File, group and date delete endpoints delegate to the matching core operation. */
    @Test
    void shouldDeleteAllResourceScopes() throws Exception {
        doNothing().when(resourceService).deleteResource(any());
        doNothing().when(resourceService).deleteGroup(any(), any());
        doNothing().when(resourceService).deleteDate(any());

        mockMvc.perform(delete("/api/resources/2026-07-13/upload-1/dir/hello.txt"))
            .andExpect(status().isOk());
        mockMvc.perform(delete("/api/resources/2026-07-13/upload-1"))
            .andExpect(status().isOk());
        mockMvc.perform(delete("/api/resources/2026-07-13"))
            .andExpect(status().isOk());

        verify(resourceService).deleteResource(
            "/resources/2026-07-13/upload-1/dir/hello.txt");
        verify(resourceService).deleteGroup("2026-07-13", "upload-1");
        verify(resourceService).deleteDate("2026-07-13");
    }

    private static HubResourceStream stream(AtomicBoolean leaseClosed) {
        return new HubResourceStream(
            Path.of("/tmp/hello.txt"), "hello.txt", "text/plain", 5L,
            HubResourceSource.UPLOAD, new ByteArrayInputStream("hello".getBytes()),
            () -> leaseClosed.set(true));
    }

    private static HubResourceItemDTO item(String name) {
        HubResourceItemDTO item = new HubResourceItemDTO();
        item.setFileName(name);
        return item;
    }

}
