package com.svatsa.escrow.ledger;

import com.svatsa.escrow.common.DomainException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when an account would go negative (but doesn't allow it).
 */
public class InsufficientFundsException extends DomainException {

    public InsufficientFundsException(String accountCode) {
        super("INSUFFICIENT_FUNDS", HttpStatus.UNPROCESSABLE_ENTITY,
            "Account " + accountCode + " has insufficient funds for this operation");
    }
}
