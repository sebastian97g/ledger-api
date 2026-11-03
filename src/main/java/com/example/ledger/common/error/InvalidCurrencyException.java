package com.example.ledger.common.error;

/**
 * Thrown when the currency code is not a valid ISO 4217 code at all.
 * HTTP 400.
 */
public class InvalidCurrencyException extends LedgerException {

    public InvalidCurrencyException(String code) {
        super("INVALID_CURRENCY", "Not a valid ISO 4217 currency code: " + code);
    }
}
