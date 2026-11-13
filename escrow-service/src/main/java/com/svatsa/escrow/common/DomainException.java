package com.svatsa.escrow.common;

import org.springframework.http.HttpStatus;

/**
 * Base exception for domain-level errors (invariant violations, business rule violations, etc.).
 * 
 * DomainExceptions are not bugs; they are expected business logic outcomes that should be
 * communicated to the client with an appropriate HTTP status and error code.
 * 
 * The GlobalExceptionHandler catches these and converts them to RFC 9457 ProblemDetail responses.
 */
public class DomainException extends RuntimeException {

    private final String code;
    private final HttpStatus httpStatus;

    public DomainException(String code, HttpStatus httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
