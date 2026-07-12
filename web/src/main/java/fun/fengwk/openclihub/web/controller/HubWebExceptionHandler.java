package fun.fengwk.openclihub.web.controller;

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

}
