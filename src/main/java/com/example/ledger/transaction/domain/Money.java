package com.example.ledger.transaction.domain;

import com.example.ledger.account.domain.Currency;
import com.example.ledger.common.error.DecimalPrecisionException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Value object representing a positive monetary amount with a currency.
 * Enforces: positive amount, no floating-point, correct decimal precision per currency.
 */
public final class Money {

    private final BigDecimal amount;
    private final Currency currency;

    private Money(BigDecimal amount, Currency currency) {
        this.amount = amount;
        this.currency = currency;
    }

    /**
     * Construct Money from a string amount and currency.
     * The string must be a valid positive decimal with at most currency.getDecimalPlaces() fraction digits.
     */
    public static Money of(String amount, Currency currency) {
        if (amount == null || amount.isBlank()) {
            throw new IllegalArgumentException("Amount must not be null or blank");
        }
        BigDecimal bd;
        try {
            bd = new BigDecimal(amount);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid amount format: " + amount, e);
        }
        return of(bd, currency);
    }

    /**
     * Construct Money from a BigDecimal amount and currency.
     * Validates positive value and decimal precision. Never uses float/double.
     */
    public static Money of(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive, got: " + amount.toPlainString());
        }
        // Check precision: strip trailing zeros to get the real scale
        if (amount.stripTrailingZeros().scale() > currency.getDecimalPlaces()) {
            throw new DecimalPrecisionException(
                    "Amount " + amount.toPlainString() + " exceeds decimal precision for " + currency +
                    " (max " + currency.getDecimalPlaces() + " decimal places)", null);
        }
        return new Money(amount.setScale(currency.getDecimalPlaces(), RoundingMode.UNNECESSARY), currency);
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Currency getCurrency() {
        return currency;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money m)) return false;
        return amount.compareTo(m.amount) == 0 && currency == m.currency;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }
}
