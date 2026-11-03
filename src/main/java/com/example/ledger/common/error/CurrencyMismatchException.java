package com.example.ledger.common.error;

/**
 * Thrown when an entry references an account with a different currency than
 * the transaction currency.
 * HTTP 422.
 */
public class CurrencyMismatchException extends LedgerException {

    private final String pointer;

    public CurrencyMismatchException(String accountId, String accountCurrency,
                                     String transactionCurrency, String pointer) {
        super("CURRENCY_MISMATCH",
              "Account " + accountId + " has currency " + accountCurrency +
              " but transaction currency is " + transactionCurrency);
        this.pointer = pointer;
    }

    public String getPointer() {
        return pointer;
    }
}
