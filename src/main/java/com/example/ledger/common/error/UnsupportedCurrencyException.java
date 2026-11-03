package com.example.ledger.common.error;

/**
 * Thrown when the currency code is a valid ISO 4217 code but not supported
 * by this ledger (i.e. not EUR, USD, or JPY).
 * HTTP 422.
 */
public class UnsupportedCurrencyException extends LedgerException {

    public UnsupportedCurrencyException(String code) {
        super("UNSUPPORTED_CURRENCY",
              "Currency is valid ISO 4217 but not supported by this ledger: " + code +
              ". Supported: EUR, USD, JPY.");
    }
}
