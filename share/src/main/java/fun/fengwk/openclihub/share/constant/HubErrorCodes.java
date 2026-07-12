package fun.fengwk.openclihub.share.constant;

import fun.fengwk.convention4j.api.code.DomainConventionErrorCodeEnumAdapter;
import fun.fengwk.convention4j.api.code.HttpStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * OpenCLI Hub 领域错误码。
 *
 * @author fengwk
 */
@AllArgsConstructor
@Getter
public enum HubErrorCodes implements DomainConventionErrorCodeEnumAdapter {

    INSTANCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    INSTANCE_CODE_CONFLICT(HttpStatus.CONFLICT),
    INSTANCE_BUSY(HttpStatus.CONFLICT),
    INSTANCE_OFFLINE(HttpStatus.BAD_REQUEST),
    INSTANCE_UNHEALTHY(HttpStatus.BAD_REQUEST),
    COMMAND_NOT_SUPPORTED(HttpStatus.BAD_REQUEST),
    NO_INSTANCE_AVAILABLE(HttpStatus.BAD_REQUEST),
    INSTANCE_QUEUE_FULL(HttpStatus.TOO_MANY_REQUESTS),
    INVALID_EXECUTION_REQUEST(HttpStatus.BAD_REQUEST),
    EXECUTION_PERSIST_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),

    ;

    private final HttpStatus httpStatus;

    @Override
    public String getDomain() {
        return "HUB";
    }

}
