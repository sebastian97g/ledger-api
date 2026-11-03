package com.example.ledger.account.domain;

import com.example.ledger.common.error.InvalidCurrencyException;
import com.example.ledger.common.error.UnsupportedCurrencyException;

/**
 * Supported currencies. Only EUR, USD, JPY are accepted.
 * Uses ISO 4217 validation to distinguish invalid codes from unsupported ones.
 */
public enum Currency {
    EUR(2), USD(2), JPY(0);

    private final int decimalPlaces;

    Currency(int decimalPlaces) {
        this.decimalPlaces = decimalPlaces;
    }

    public int getDecimalPlaces() {
        return decimalPlaces;
    }

    /**
     * Parse a currency code, throwing distinct exceptions for invalid vs unsupported.
     *
     * @throws InvalidCurrencyException   if not a valid ISO 4217 code
     * @throws UnsupportedCurrencyException if valid ISO 4217 but not in EUR/USD/JPY
     */
    public static Currency fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new InvalidCurrencyException(code);
        }
        String upper = code.toUpperCase();
        // Validate it is a real ISO 4217 code first
        try {
            java.util.Currency.getInstance(upper);
        } catch (IllegalArgumentException e) {
            throw new InvalidCurrencyException(code);
        }
        // Now check if it is in our supported set
        try {
            return valueOf(upper);
        } catch (IllegalArgumentException e) {
            throw new UnsupportedCurrencyException(code);
        }
    }
}
