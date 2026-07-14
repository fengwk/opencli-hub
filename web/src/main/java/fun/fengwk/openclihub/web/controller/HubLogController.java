package fun.fengwk.openclihub.web.controller;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import fun.fengwk.openclihub.core.log.HubLogService;
import fun.fengwk.openclihub.core.log.HubLogStream;
import fun.fengwk.openclihub.share.model.log.HubLogContentDTO;
import fun.fengwk.openclihub.share.model.log.HubLogSource;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Read-only system and Instance process log API.
 *
 * @author fengwk
 */
@RequiredArgsConstructor
@RestController
public class HubLogController {

    private final HubLogService logService;

    @GetMapping("/api/logs/system")
    public Result<HubLogContentDTO> tailSystem(
        @RequestParam(defaultValue = "500") int lines) {
        return Results.ok(logService.tailSystem(validatedLines(lines)));
    }

    @GetMapping("/api/logs/system/download")
    public ResponseEntity<StreamingResponseBody> downloadSystem() {
        try {
            return download(logService.openSystemDownload());
        } catch (ThrowableConventionErrorCode ex) {
            throw downloadError(ex);
        }
    }

    @GetMapping("/api/instances/{id}/logs")
    public Result<HubLogContentDTO> tailInstance(
        @PathVariable String id,
        @RequestParam(required = false) String source,
        @RequestParam(defaultValue = "500") int lines) {
        HubLogSource parsedSource = HubLogService.parseInstanceSource(source);
        return Results.ok(logService.tailInstance(id, parsedSource, validatedLines(lines)));
    }

    @GetMapping("/api/instances/{id}/logs/download")
    public ResponseEntity<StreamingResponseBody> downloadInstance(
        @PathVariable String id,
        @RequestParam(required = false) String source) {
        try {
            return download(logService.openInstanceDownload(id, HubLogService.parseInstanceSource(source)));
        } catch (ThrowableConventionErrorCode ex) {
            throw downloadError(ex);
        }
    }

    private static int validatedLines(int lines) {
        try {
            HubLogService.validateLineCount(lines);
            return lines;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                ex.getMessage(), ex);
        }
    }

    private static ResponseStatusException downloadError(ThrowableConventionErrorCode ex) {
        return new ResponseStatusException(org.springframework.http.HttpStatusCode.valueOf(ex.getStatus()),
            ex.getMessage(), ex);
    }

    private static ResponseEntity<StreamingResponseBody> download(HubLogStream log) {
        try {
            StreamingResponseBody body = outputStream -> {
                try (log) {
                    log.getInputStream().transferTo(outputStream);
                }
            };
            ContentDisposition disposition = ContentDisposition.attachment()
                .filename(log.getFileName(), StandardCharsets.UTF_8)
                .build();
            return ResponseEntity.ok()
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .contentLength(log.getSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(body);
        } catch (RuntimeException | Error ex) {
            try {
                log.close();
            } catch (Throwable closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
    }

}
