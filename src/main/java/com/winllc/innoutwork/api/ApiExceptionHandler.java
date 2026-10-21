package com.winllc.innoutwork.api;

import com.winllc.innoutwork.audit.AccessDenials;
import com.winllc.innoutwork.service.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * JSON for every refusal and error on /api/v1, ahead of the application-wide handler (an HTML page); license
 * refusals are LicenseExceptionHandler's. A missing
 * scope or a person the token may not see is 403, recorded as access.denied.
 */
@RestControllerAdvice(basePackageClasses = ApiExceptionHandler.class)
// After LicenseExceptionHandler, which answers license refusals (with JSON for /api paths).
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private AccessDenials denials;

    @Autowired(required = false)
    public void setAccessDenials(AccessDenials denials) {
        this.denials = denials;
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> denied(HttpServletRequest request, AccessDeniedException e) {
        if (denials != null) {
            denials.denied(request, "permission_denied", e.getMessage(), null);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("forbidden", "This token may not read that: check its scopes and its owner's rights."));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiError> invalid(InvalidRequestException e) {
        return ResponseEntity.status(e.status()).body(new ApiError(e.error(), e.getMessage()));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> badParameter(Exception e) {
        String message = e instanceof MissingServletRequestParameterException missing
                ? missing.getParameterName() + " is required"
                : ((MethodArgumentTypeMismatchException) e).getName() + " has the wrong type";
        return ResponseEntity.badRequest().body(new ApiError("bad_request", message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> failed(HttpServletRequest request, Exception e) {
        log.error("API request {} failed", com.winllc.innoutwork.util.LogSafe.of(request.getRequestURI()), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("internal_error", "The request failed; the server log has the details."));
    }
}
