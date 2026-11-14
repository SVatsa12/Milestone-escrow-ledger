package com.svatsa.escrow.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * Global exception handler for RFC 9457 ProblemDetail responses.
 * 
 * DomainExceptions and ValidationExceptions are caught here and converted
 * to structured error responses with a stable error code.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemDetail> handleDomainException(
        DomainException ex,
        WebRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatus(ex.getHttpStatus());
        problem.setTitle(ex.getHttpStatus().getReasonPhrase());
        problem.setDetail(ex.getMessage());
        problem.setProperty("code", ex.getCode());

        return ResponseEntity
            .status(ex.getHttpStatus())
            .body(problem);
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ProblemDetail> handleValidationException(
        ValidationException ex,
        WebRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatus(ex.getHttpStatus());
        problem.setTitle(ex.getHttpStatus().getReasonPhrase());
        problem.setDetail(ex.getMessage());
        problem.setProperty("code", ex.getCode());

        return ResponseEntity
            .status(ex.getHttpStatus())
            .body(problem);
    }

    /**
     * State-machine conflicts. These exceptions predate DomainException and used to fall
     * through to the generic handler as 500s (AGENTS.md requires 409 INVALID_STATE_TRANSITION).
     */
    @ExceptionHandler({
        com.svatsa.escrow.escrow.InvalidStateTransitionException.class,
        com.svatsa.escrow.escrow.CancelTooEarlyException.class,
        com.svatsa.escrow.dispute.DisputeAlreadyOpenException.class
    })
    public ResponseEntity<ProblemDetail> handleStateConflict(RuntimeException ex) {
        return problem(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", ex.getMessage());
    }

    /**
     * Malformed requests (bad header value, missing required header or parameter, unreadable
     * body) are the client's fault: 400 VALIDATION_FAILED. They used to fall through to 500.
     */
    @ExceptionHandler({
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingRequestHeaderException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class
    })
    public ResponseEntity<ProblemDetail> handleBadRequest(Exception ex) {
        String detail = ex instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException m
            ? "Invalid value for " + m.getName()
            : ex instanceof org.springframework.http.converter.HttpMessageNotReadableException
                ? "Request body is missing or not valid JSON"
                : ex.getMessage();
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail);
    }

    /**
     * Services throw IllegalArgumentException for unknown ids ("... not found") and bad input.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
        String message = ex.getMessage() == null ? "Invalid request" : ex.getMessage();
        if (message.toLowerCase().contains("not found")) {
            return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
        }
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGenericException(
        Exception ex,
        WebRequest request
    ) {
        // Unexpected failures were previously swallowed without a trace.
        log.error("request.failed path={} error={}", request.getDescription(false), ex.toString(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Internal server error"
        );
        problem.setProperty("code", "INTERNAL_ERROR");

        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(problem);
    }
}
