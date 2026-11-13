package com.svatsa.escrow.common;

import org.springframework.http.HttpStatus;

/**
 * Validation error (request was malformed).
 */
public class ValidationException extends DomainException {

    public ValidationException(String message) {
        super("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, message);
    }
}
