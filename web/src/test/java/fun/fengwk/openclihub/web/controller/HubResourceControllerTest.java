package fun.fengwk.openclihub.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
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
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.async.StandardServletAsyncWebRequest;
import org.springframework.web.context.request.async.WebAsyncUtils;

/** Covers multipart, browse, binary stream and delete resource routes. */
@ImportAutoConfiguration(WebExceptionResultHandlerAutoConfiguration.class)
@WebMvcTest(
    controllers = HubResourceController.class,
    properties = "logging.file.path=${java.io.tmpdir}/opencli-hub-web-test")
class HubResourceControllerTest {

    private static final String URL = "/api/resources/2026-07-13/upload-1/hello.txt";
    private static final String ETAG =
        "\"sha256-2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824\"";
    private static final String EMPTY_ETAG =
        "\"sha256-e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webContext;

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
        summary.setGroupCount(2L);
        summary.setFileCount(3L);
        summary.setTotalSize(1_536L);
        HubResourceItemDTO resource = item("hello.txt");
        resource.setSize(1_536L);
        resource.setModifiedAt(LocalDateTime.of(2026, 7, 13, 10, 0));
        when(resourceService.listDateSummaries()).thenReturn(List.of(summary));
        when(resourceService.listDay(any())).thenReturn(List.of(resource));

        mockMvc.perform(get("/api/resources/dates"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].date").value("2026-07-13"))
            .andExpect(jsonPath("$.data[0].groupCount").value("2"))
            .andExpect(jsonPath("$.data[0].fileCount").value("3"))
            .andExpect(jsonPath("$.data[0].totalSize").value("1536"));
        mockMvc.perform(get("/api/resources").param("date", "2026-07-13")
                .param("source", "UPLOAD").param("sort", "NAME_ASC")
                .param("page", "1").param("pageSize", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].fileName").value("hello.txt"))
            .andExpect(jsonPath("$.data[0].size").value("1536"))
            .andExpect(jsonPath("$.data[0].modifiedAt").exists());

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
            .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
            .andExpect(header().string(HttpHeaders.ETAG, ETAG))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                org.hamcrest.Matchers.startsWith("inline;")));
        assertThat(leaseClosed).isTrue();
    }

    /** HEAD ignores Range and closes both resources synchronously without starting a body. */
    @Test
    void shouldReturnFullHeadersAndCloseForHead() throws Exception {
        AtomicBoolean inputClosed = new AtomicBoolean();
        AtomicBoolean leaseClosed = new AtomicBoolean();
        when(resourceService.openForRead(any())).thenReturn(stream("hello", inputClosed, leaseClosed));

        mockMvc.perform(head(URL).header(HttpHeaders.RANGE, "bytes=1-2").param("inline", "true"))
            .andExpect(status().isOk())
            .andExpect(request().asyncNotStarted())
            .andExpect(content().bytes(new byte[0]))
            .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 5))
            .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
            .andExpect(header().string(HttpHeaders.ETAG, ETAG))
            .andExpect(header().doesNotExist(HttpHeaders.CONTENT_RANGE))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                org.hamcrest.Matchers.startsWith("inline;")));
        assertThat(inputClosed).isTrue();
        assertThat(leaseClosed).isTrue();
    }

    /** Each supported range is bounded to the exact selected bytes, even for huge ends. */
    @Test
    void shouldStreamSingleRanges() throws Exception {
        String[][] cases = {
            {"bytes=1-3", "ell", "bytes 1-3/5"},
            {"bytes=2-", "llo", "bytes 2-4/5"},
            {"bytes=-2", "lo", "bytes 3-4/5"},
            {"bytes=3-99", "lo", "bytes 3-4/5"},
            {"bytes=-999999999999999999999999", "hello", "bytes 0-4/5"},
            {"BYTES=0-999999999999999999999999", "hello", "bytes 0-4/5"}
        };
        for (String[] test : cases) {
            AtomicBoolean inputClosed = new AtomicBoolean();
            AtomicBoolean leaseClosed = new AtomicBoolean();
            when(resourceService.openForRead(any())).thenReturn(stream("hello", inputClosed, leaseClosed));
            MvcResult started = mockMvc.perform(get(URL).header(HttpHeaders.RANGE, test[0]))
                .andExpect(request().asyncStarted()).andReturn();
            mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, test[2]))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, test[1].length()))
                .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(content().string(test[1]));
            assertThat(inputClosed).as(test[0]).isTrue();
            assertThat(leaseClosed).as(test[0]).isTrue();
        }
    }

    /** Invalid, multiple and unsatisfiable byte ranges never transfer and release the lease. */
    @Test
    void shouldRejectInvalidRangesAndRangesOnEmptyFiles() throws Exception {
        String[] invalid = {"bytes=5-", "bytes=4-2", "bytes=-0", "bytes=-",
            "bytes=1-2,3-4", "bytes=1-x", "bytes=1-2-3", "bytes", "bytes =1-2",
            "bytes=999999999999999999999999-"};
        for (String value : invalid) {
            AtomicBoolean inputClosed = new AtomicBoolean();
            AtomicBoolean leaseClosed = new AtomicBoolean();
            when(resourceService.openForRead(any())).thenReturn(stream("hello", inputClosed, leaseClosed));
            mockMvc.perform(get(URL).header(HttpHeaders.RANGE, value))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(request().asyncNotStarted())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */5"))
                .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                .andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
                .andExpect(content().bytes(new byte[0]));
            assertThat(inputClosed).as(value).isTrue();
            assertThat(leaseClosed).as(value).isTrue();
        }
        AtomicBoolean leaseClosed = new AtomicBoolean();
        when(resourceService.openForRead(any())).thenReturn(stream("hello", new AtomicBoolean(), leaseClosed));
        mockMvc.perform(get(URL).header(HttpHeaders.RANGE, "bytes=0-1", "bytes=2-3"))
            .andExpect(status().isRequestedRangeNotSatisfiable())
            .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */5"));
        assertThat(leaseClosed).isTrue();

        AtomicBoolean emptyClosed = new AtomicBoolean();
        when(resourceService.openForRead(any())).thenReturn(stream("", new AtomicBoolean(), emptyClosed));
        mockMvc.perform(get(URL).header(HttpHeaders.RANGE, "bytes=0-"))
            .andExpect(status().isRequestedRangeNotSatisfiable())
            .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes */0"));
        assertThat(emptyClosed).isTrue();
    }

    /** Only the exact strong validator allows a partial response; dates and weak tags restart. */
    @Test
    void shouldHonorOnlyMatchingStrongIfRange() throws Exception {
        for (String validator : List.of(ETAG, "W/" + ETAG, "\"sha256-stale\"",
            "Wed, 21 Oct 2015 07:28:00 GMT", "")) {
            AtomicBoolean leaseClosed = new AtomicBoolean();
            when(resourceService.openForRead(any())).thenReturn(stream("hello", new AtomicBoolean(), leaseClosed));
            MvcResult started = mockMvc.perform(get(URL).header(HttpHeaders.RANGE, "bytes=1-2")
                    .header(HttpHeaders.IF_RANGE, validator))
                .andExpect(request().asyncStarted()).andReturn();
            boolean matches = validator.equals(ETAG);
            mockMvc.perform(asyncDispatch(started))
                .andExpect(status().is(matches ? 206 : 200))
                .andExpect(content().string(matches ? "el" : "hello"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, matches ? 2 : 5));
            assertThat(leaseClosed).as(validator).isTrue();
        }
    }

    /** Unknown units are ignored, and an empty representation still has an ordinary 200. */
    @Test
    void shouldIgnoreUnknownUnitAndServeEmptyRepresentation() throws Exception {
        when(resourceService.openForRead(any())).thenReturn(stream(new AtomicBoolean()));
        MvcResult unknown = mockMvc.perform(get(URL).header(HttpHeaders.RANGE, "items=1-2"))
            .andExpect(request().asyncStarted()).andReturn();
        mockMvc.perform(asyncDispatch(unknown))
            .andExpect(status().isOk()).andExpect(content().string("hello"));

        AtomicBoolean leaseClosed = new AtomicBoolean();
        when(resourceService.openForRead(any())).thenReturn(stream("", new AtomicBoolean(), leaseClosed));
        MvcResult empty = mockMvc.perform(get(URL)).andExpect(request().asyncStarted()).andReturn();
        mockMvc.perform(asyncDispatch(empty))
            .andExpect(status().isOk())
            .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 0))
            .andExpect(content().bytes(new byte[0]));
        assertThat(leaseClosed).isTrue();
    }

    /** Streaming owns the lease until completion, including output failure; bad headers also close. */
    @Test
    void shouldReleaseResourcesOnTransferAndHeaderFailure() throws Exception {
        HubResourceController controller = new HubResourceController(resourceService);
        AtomicBoolean inputClosed = new AtomicBoolean();
        AtomicBoolean leaseClosed = new AtomicBoolean();
        when(resourceService.openForRead(any())).thenReturn(stream("hello", inputClosed, leaseClosed));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", URL);
        request.setAsyncSupported(true);
        request.addHeader(HttpHeaders.RANGE, "bytes=1-3");
        var response = controller.read("2026-07-13", "upload-1", "/hello.txt", false, request);
        assertThat(inputClosed).isFalse();
        assertThat(leaseClosed).isFalse();
        OutputStream failingOutput = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("client disconnected");
            }
        };
        assertThatThrownBy(() -> response.getBody().writeTo(failingOutput))
            .isInstanceOf(IOException.class).hasMessage("client disconnected");
        assertThat(inputClosed).isTrue();
        assertThat(leaseClosed).isTrue();

        AtomicBoolean badHeaderClosed = new AtomicBoolean();
        when(resourceService.openForRead(any())).thenReturn(new HubResourceStream(
            Path.of("/tmp/hello.txt"), "hello.txt", "invalid mime", 5, ETAG,
            HubResourceSource.UPLOAD, new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)),
            () -> badHeaderClosed.set(true)));
        assertThatThrownBy(() -> controller.read("2026-07-13", "upload-1", "/hello.txt", false, request))
            .isInstanceOf(RuntimeException.class);
        assertThat(badHeaderClosed).isTrue();
    }

    /** Async completion releases a resource even when the executor never runs its body. */
    @Test
    void shouldReleaseLeaseWhenAsyncExecutionIsRejected() throws Exception {
        AtomicBoolean inputClosed = new AtomicBoolean();
        AtomicBoolean leaseClosed = new AtomicBoolean();
        when(resourceService.openForRead(any())).thenReturn(stream("hello", inputClosed, leaseClosed));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", URL);
        request.setAsyncSupported(true);
        new HubResourceController(resourceService).read("2026-07-13", "upload-1", "/hello.txt", false, request);

        var manager = WebAsyncUtils.getAsyncManager(request);
        manager.setAsyncWebRequest(new StandardServletAsyncWebRequest(request, new MockHttpServletResponse()));
        manager.setTaskExecutor((AsyncTaskExecutor) task -> {
            throw new RejectedExecutionException("queue full");
        });
        manager.startCallableProcessing(() -> null);
        assertThat(leaseClosed).isFalse();
        request.getAsyncContext().complete();
        assertThat(inputClosed).isTrue();
        assertThat(leaseClosed).isTrue();
    }

    /** Return-value output failures happen before async completion can release the lease. */
    @Test
    void shouldReleaseLeaseWhenResponseOutputCannotBeOpened() throws Exception {
        AtomicBoolean leaseClosed = new AtomicBoolean();
        when(resourceService.openForRead(any())).thenReturn(stream("hello", new AtomicBoolean(), leaseClosed));
        MockMvc failingMvc = MockMvcBuilders.webAppContextSetup(webContext)
            .addFilters((request, response, chain) -> chain.doFilter(request,
                new HttpServletResponseWrapper((HttpServletResponse) response) {
                    @Override
                    public ServletOutputStream getOutputStream() {
                        throw new IllegalStateException("output unavailable");
                    }
                })).build();
        assertThatThrownBy(() -> failingMvc.perform(get(URL)))
            .hasRootCauseMessage("output unavailable");
        assertThat(leaseClosed).isTrue();
    }

    /** MVC cleanup must also release a synchronous request when async is unavailable. */
    @Test
    void shouldReleaseLeaseWhenAsyncIsNotSupported() throws Exception {
        AtomicBoolean inputClosed = new AtomicBoolean();
        AtomicBoolean leaseClosed = new AtomicBoolean();
        when(resourceService.openForRead(any())).thenReturn(stream("hello", inputClosed, leaseClosed));
        assertThatThrownBy(() -> mockMvc.perform(get(URL).with(request -> {
            request.setAsyncSupported(false);
            return request;
        }))).hasRootCauseMessage("Async resource streaming is not supported");
        assertThat(inputClosed).isTrue();
        assertThat(leaseClosed).isTrue();
    }

    /** An async-start failure after return-value handling also reaches MVC cleanup. */
    @Test
    void shouldReleaseLeaseWhenAsyncStartFails() throws Exception {
        AtomicBoolean leaseClosed = new AtomicBoolean();
        when(resourceService.openForRead(any())).thenReturn(stream("hello", new AtomicBoolean(), leaseClosed));
        MockMvc failingMvc = MockMvcBuilders.webAppContextSetup(webContext)
            .addFilters((request, response, chain) -> chain.doFilter(
                new HttpServletRequestWrapper((HttpServletRequest) request) {
                    @Override
                    public AsyncContext startAsync(ServletRequest req, ServletResponse res) {
                        throw new IllegalStateException("async start failed");
                    }
                }, response)).build();
        assertThatThrownBy(() -> failingMvc.perform(get(URL))).hasRootCauseMessage("async start failed");
        assertThat(leaseClosed).isTrue();
    }

    /** Async dispatch must not release a lease while the transfer is still blocked. */
    @Test
    void shouldKeepLeaseUntilAsyncTransferFinishes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicBoolean leaseClosed = new AtomicBoolean();
        InputStream blocking = new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                entered.countDown();
                try {
                    if (!proceed.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("transfer timed out");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                return super.read(bytes, offset, length);
            }
        };
        when(resourceService.openForRead(any())).thenReturn(new HubResourceStream(
            Path.of("/tmp/hello.txt"), "hello.txt", "text/plain", 5, ETAG,
            HubResourceSource.UPLOAD, blocking, () -> leaseClosed.set(true)));
        MvcResult started = mockMvc.perform(get(URL)).andExpect(request().asyncStarted()).andReturn();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(leaseClosed).isFalse();
        } finally {
            proceed.countDown();
        }
        mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andExpect(content().string("hello"));
        assertThat(leaseClosed).isTrue();
    }

    /** A change detected before the final range bytes cannot complete the advertised length. */
    @Test
    void shouldNotSendFinalBytesWhenConsistencyCheckFails() throws Exception {
        AtomicBoolean leaseClosed = new AtomicBoolean();
        when(resourceService.openForRead(any())).thenReturn(new HubResourceStream(
            Path.of("/tmp/hello.txt"), "hello.txt", "text/plain", 5, ETAG,
            HubResourceSource.UPLOAD, new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)),
            () -> leaseClosed.set(true), () -> { throw new IOException("resource changed"); }));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", URL);
        request.setAsyncSupported(true);
        request.addHeader(HttpHeaders.RANGE, "bytes=1-3");
        var response = new HubResourceController(resourceService)
            .read("2026-07-13", "upload-1", "/hello.txt", false, request);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThatThrownBy(() -> response.getBody().writeTo(output))
            .isInstanceOf(IOException.class).hasMessage("resource changed");
        assertThat(output.toByteArray()).isEmpty();
        assertThat(leaseClosed).isTrue();
    }

    /** A truncated underlying resource fails the promised transfer and still releases its lease. */
    @Test
    void shouldCloseOnUnexpectedEndOfStream() throws Exception {
        AtomicBoolean leaseClosed = new AtomicBoolean();
        when(resourceService.openForRead(any())).thenReturn(new HubResourceStream(
            Path.of("/tmp/hello.txt"), "hello.txt", "text/plain", 5, ETAG,
            HubResourceSource.UPLOAD, new ByteArrayInputStream("he".getBytes(StandardCharsets.UTF_8)),
            () -> leaseClosed.set(true)));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", URL);
        request.setAsyncSupported(true);
        request.addHeader(HttpHeaders.RANGE, "bytes=1-3");
        var response = new HubResourceController(resourceService)
            .read("2026-07-13", "upload-1", "/hello.txt", false, request);
        assertThatThrownBy(() -> response.getBody().writeTo(OutputStream.nullOutputStream()))
            .isInstanceOf(IOException.class).hasMessageContaining("promised length");
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
        return stream("hello", new AtomicBoolean(), leaseClosed);
    }

    private static HubResourceStream stream(String contents, AtomicBoolean inputClosed, AtomicBoolean leaseClosed) {
        byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
        return new HubResourceStream(
            Path.of("/tmp/hello.txt"), "hello.txt", "text/plain", bytes.length,
            contents.isEmpty() ? EMPTY_ETAG : ETAG,
            HubResourceSource.UPLOAD, new ByteArrayInputStream(bytes) {
                @Override
                public void close() throws IOException {
                    inputClosed.set(true);
                    super.close();
                }
            },
            () -> leaseClosed.set(true));
    }

    private static HubResourceItemDTO item(String name) {
        HubResourceItemDTO item = new HubResourceItemDTO();
        item.setFileName(name);
        return item;
    }

}
