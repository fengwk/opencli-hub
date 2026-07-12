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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
        @RequestParam(defaultValue = "false") boolean inline) {
        HubResourceStream resource;
        try {
            resource = resourceService.openForRead(virtualPath(date, group, path));
        } catch (ThrowableConventionErrorCode ex) {
            throw new ResponseStatusException(
                HttpStatusCode.valueOf(ex.getStatus()), ex.getMessage(), ex);
        }
        try {
            StreamingResponseBody body = outputStream -> {
                try (resource) {
                    resource.getInputStream().transferTo(outputStream);
                }
            };
            ContentDisposition disposition = (inline
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
                .filename(resource.getFileName(), StandardCharsets.UTF_8)
                .build();
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(resource.getMimeType()))
                .contentLength(resource.getSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(body);
        } catch (RuntimeException | Error ex) {
            try {
                resource.close();
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
