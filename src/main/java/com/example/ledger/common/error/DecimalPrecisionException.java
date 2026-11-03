package com.example.ledger.common.error;

/**
 * Thrown when an amount has more decimal places than the currency allows.
 * HTTP 422.
 */
public class DecimalPrecisionException extends LedgerException {

    private final String pointer;

    public DecimalPrecisionException(String message, String pointer) {
        super("DECIMAL_PRECISION_EXCEEDED", message);
        this.pointer = pointer;
    }

    public String getPointer() {
        return pointer;
    }
}
