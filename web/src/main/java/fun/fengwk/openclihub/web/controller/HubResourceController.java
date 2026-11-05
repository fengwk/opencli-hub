package fun.fengwk.openclihub.web.controller;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import fun.fengwk.openclihub.core.resource.model.HubResourceListRequest;
import fun.fengwk.openclihub.core.resource.model.HubResourceStream;
import fun.fengwk.openclihub.core.resource.model.HubResourceUploadItem;
import fun.fengwk.openclihub.core.resource.model.HubResourceUploadRequest;
import fun.fengwk.openclihub.core.resource.service.HubResourceService;
import fun.fengwk.openclihub.share.model.resource.HubResourceDateSummaryDTO;
import fun.fengwk.openclihub.share.model.resource.HubResourceItemDTO;
import fun.fengwk.openclihub.share.model.resource.HubResourceSource;
import fun.fengwk.openclihub.share.model.resource.HubResourceUploadResultDTO;
import jakarta.servlet.http.HttpServletRequest;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Resource center upload, browse, download and delete API.
 *
 * @author fengwk
 */
@Slf4j
@RequiredArgsConstructor
@RestController
public class HubResourceController {

    private final HubResourceService resourceService;

    @PostMapping(value = "/api/resources/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<HubResourceUploadResultDTO> upload(
        @RequestParam(required = false) String date,
        @RequestPart("files") List<MultipartFile> files) throws IOException {
        List<InputStream> streams = new ArrayList<>(files.size());
        try {
            List<HubResourceUploadItem> items = new ArrayList<>(files.size());
            for (MultipartFile file : files) {
                InputStream stream = file.getInputStream();
                streams.add(stream);
                items.add(HubResourceUploadItem.builder()
                    .originalFileName(file.getOriginalFilename())
                    .inputStream(stream)
                    .size(file.getSize())
                    .build());
            }
            HubResourceService.UploadResult uploaded = resourceService.upload(
                HubResourceUploadRequest.builder().date(date).items(items).build());
            HubResourceUploadResultDTO dto = new HubResourceUploadResultDTO();
            dto.setDate(uploaded.getDate().toString());
            dto.setGroup(uploaded.getGroup());
            dto.setItems(uploaded.getItems());
            return Results.created(dto);
        } finally {
            closeAll(streams);
        }
    }

    @GetMapping("/api/resources/dates")
    public Result<List<HubResourceDateSummaryDTO>> dates() {
        return Results.ok(resourceService.listDateSummaries());
    }

    @GetMapping("/api/resources")
    public Result<List<HubResourceItemDTO>> list(
        @RequestParam String date,
        @RequestParam(required = false) HubResourceSource source,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) HubResourceListRequest.ResourceSort sort,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "100") int pageSize) {
        HubResourceListRequest request = HubResourceListRequest.builder()
            .date(date)
            .source(source)
            .keyword(keyword)
            .sort(sort)
            .page(page)
            .pageSize(pageSize)
            .build();
        return Results.ok(resourceService.listDay(request));
    }

    @GetMapping("/api/resources/{date}/{group}/{*path}")
    public ResponseEntity<StreamingResponseBody> read(
        @PathVariable String date,
        @PathVariable String group,
        @PathVariable String path,
        @RequestParam(defaultValue = "false") boolean inline,
        HttpServletRequest request) throws IOException {
        HubResourceStream resource;
        try {
            resource = resourceService.openForRead(virtualPath(date, group, path));
        } catch (ThrowableConventionErrorCode ex) {
            throw new ResponseStatusException(
                HttpStatusCode.valueOf(ex.getStatus()), ex.getMessage(), ex);
        }
        ResourceCloser closer = new ResourceCloser(resource);
        try {
            boolean head = "HEAD".equals(request.getMethod());
            List<String> ifRange = Collections.list(request.getHeaders(HttpHeaders.IF_RANGE));
            ByteRange range = null;
            if (!head && (ifRange.isEmpty()
                || (ifRange.size() == 1 && resource.getEtag().equals(ifRange.get(0).trim())))) {
                range = parseRange(Collections.list(request.getHeaders(HttpHeaders.RANGE)), resource.getSize());
            }
            boolean invalidRange = ByteRange.INVALID.equals(range);
            ContentDisposition disposition = (inline
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
                .filename(resource.getFileName(), StandardCharsets.UTF_8)
                .build();
            long start = range == null || invalidRange ? 0 : range.start();
            long length = range == null ? resource.getSize()
                : invalidRange ? 0 : range.end() - start + 1;
            ResponseEntity.BodyBuilder response = ResponseEntity.status(invalidRange
                    ? HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE
                    : range == null ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT)
                .contentType(MediaType.parseMediaType(resource.getMimeType()))
                .contentLength(length)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.ETAG, resource.getEtag())
                .header(HttpHeaders.CACHE_CONTROL, "no-transform")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
            if (invalidRange) {
                response.header(HttpHeaders.CONTENT_RANGE, "bytes */" + resource.getSize());
            } else if (range != null) {
                response.header(HttpHeaders.CONTENT_RANGE,
                    "bytes " + start + "-" + range.end() + "/" + resource.getSize());
            }
            if (head || invalidRange) {
                ResponseEntity<StreamingResponseBody> result = response.build();
                closer.close();
                return result;
            }
            if (!request.isAsyncSupported()) {
                throw new IllegalStateException("Async resource streaming is not supported");
            }
            StreamingResponseBody body = outputStream -> {
                try (closer) {
                    copyRange(resource.getInputStream(), outputStream, start, length);
                }
            };
            // A queued task may be rejected or time out before its body ever runs.
            WebAsyncUtils.getAsyncManager(request).registerCallableInterceptor(closer,
                new CallableProcessingInterceptor() {
                    @Override
                    public <T> void afterCompletion(NativeWebRequest webRequest, Callable<T> task)
                        throws IOException {
                        closer.close();
                    }
                });
            return response.body(body);
        } catch (IOException | RuntimeException | Error ex) {
            try {
                closer.close();
            } catch (Throwable closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
    }

    @DeleteMapping("/api/resources/{date}/{group}/{*path}")
    public Result<Void> deleteResource(
        @PathVariable String date,
        @PathVariable String group,
        @PathVariable String path) {
        resourceService.deleteResource(virtualPath(date, group, path));
        return Results.ok();
    }

    @DeleteMapping("/api/resources/{date}/{group}")
    public Result<Void> deleteGroup(
        @PathVariable String date,
        @PathVariable String group) {
        resourceService.deleteGroup(date, group);
        return Results.ok();
    }

    @DeleteMapping("/api/resources/{date}")
    public Result<Void> deleteDate(@PathVariable String date) {
        resourceService.deleteDate(date);
        return Results.ok();
    }

    private static String virtualPath(String date, String group, String path) {
        String relativePath = path.startsWith("/") ? path.substring(1) : path;
        return "/resources/" + date + "/" + group + "/" + relativePath;
    }

    private record ByteRange(long start, long end) {
        private static final ByteRange INVALID = new ByteRange(-1, -1);
    }

    private static final class ResourceCloser implements AutoCloseable {
        private final HubResourceStream resource;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ResourceCloser(HubResourceStream resource) {
            this.resource = resource;
        }

        @Override
        public void close() throws IOException {
            if (closed.compareAndSet(false, true)) {
                resource.close();
            }
        }
    }

    /** Null means no usable byte-range unit; INVALID means a byte range must be rejected. */
    private static ByteRange parseRange(List<String> headers, long size) {
        if (headers.isEmpty()) {
            return null;
        }
        boolean hasBytesUnit = headers.stream().anyMatch(header -> {
            String value = header.trim();
            int equals = value.indexOf('=');
            String unit = equals < 0 ? value : value.substring(0, equals).trim();
            return unit.equalsIgnoreCase("bytes");
        });
        if (!hasBytesUnit) {
            return null;
        }
        if (headers.size() != 1 || size == 0) {
            return ByteRange.INVALID;
        }
        String value = headers.get(0).trim();
        int equals = value.indexOf('=');
        if (equals < 0 || !value.substring(0, equals).equalsIgnoreCase("bytes")) {
            return ByteRange.INVALID;
        }
        String spec = value.substring(equals + 1);
        int dash = spec.indexOf('-');
        if (dash < 0 || spec.indexOf('-', dash + 1) >= 0) {
            return ByteRange.INVALID;
        }
        String first = spec.substring(0, dash);
        String last = spec.substring(dash + 1);
        if (first.isEmpty() && last.isEmpty()) {
            return ByteRange.INVALID;
        }
        long firstNumber = first.isEmpty() ? -1 : parseDecimal(first);
        long lastNumber = last.isEmpty() ? -1 : parseDecimal(last);
        if ((!first.isEmpty() && firstNumber < 0) || (!last.isEmpty() && lastNumber < 0)) {
            return ByteRange.INVALID;
        }
        if (first.isEmpty()) {
            if (lastNumber == 0) {
                return ByteRange.INVALID;
            }
            return new ByteRange(size - Math.min(size, lastNumber), size - 1);
        }
        if (firstNumber >= size || (!last.isEmpty() && lastNumber < firstNumber)) {
            return ByteRange.INVALID;
        }
        return new ByteRange(firstNumber, last.isEmpty() ? size - 1 : Math.min(lastNumber, size - 1));
    }

    /** Parse digits without overflow; values above Long.MAX_VALUE are safely saturated. */
    private static long parseDecimal(String value) {
        long result = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            int digit = c - '0';
            result = result > (Long.MAX_VALUE - digit) / 10 ? Long.MAX_VALUE : result * 10 + digit;
        }
        return result;
    }

    private static void copyRange(InputStream input, OutputStream output, long start, long length)
        throws IOException {
        input.skipNBytes(start);
        byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new EOFException("Resource ended before the promised length");
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void closeAll(List<InputStream> streams) {
        for (InputStream stream : streams) {
            try {
                stream.close();
            } catch (IOException ex) {
                log.warn("Failed to close multipart upload stream: {}", ex.getMessage());
            }
        }
    }

}
