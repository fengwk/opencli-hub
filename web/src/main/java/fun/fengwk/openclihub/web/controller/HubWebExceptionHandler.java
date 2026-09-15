package fun.fengwk.openclihub.web.controller;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import fun.fengwk.openclihub.core.command.service.OpenCliCommandPolicyException;
import fun.fengwk.openclihub.core.command.validator.OpenCliArgvValidationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Adapts core validation exceptions that predate convention4j throwable codes.
 *
 * @author fengwk
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class HubWebExceptionHandler {

    @ExceptionHandler(OpenCliArgvValidationException.class)
    public Result<Void> handleArgvValidation(OpenCliArgvValidationException ex) {
        return Results.error(ex.getErrorCode());
    }

    @ExceptionHandler(OpenCliCommandPolicyException.class)
    public Result<Void> handleCommandPolicy(OpenCliCommandPolicyException ex) {
        return Results.error(ex.getErrorCode());
    }

    /**
     * Maps {@code HubErrorCodes#asThrowable} rejections to their declared status.
     *
     * <p>convention4j's own advice only maps handlers whose return type is {@code Result};
     * handlers returning {@code ResponseEntity} (e.g. the 202 submit endpoint) would
     * otherwise surface every rejection as HTTP 500 without its error code.
     */
    @ExceptionHandler(ThrowableConventionErrorCode.class)
    public Result<Void> handleThrowableConventionErrorCode(ThrowableConventionErrorCode ex) {
        return Results.error(ex);
    }

}
