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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGenericException(
        Exception ex,
        WebRequest request
    ) {
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
