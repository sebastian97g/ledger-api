package com.example.ledger.common.error;

/**
 * Base exception for all domain-level ledger errors.
 */
public abstract class LedgerException extends RuntimeException {

    private final String code;

    protected LedgerException(String code, String message) {
        super(message);
        this.code = code;
    }

    protected LedgerException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
