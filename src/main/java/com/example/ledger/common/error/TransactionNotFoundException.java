package com.example.ledger.common.error;

/**
 * Thrown when a referenced transaction does not exist.
 * HTTP 404.
 */
public class TransactionNotFoundException extends LedgerException {

    public TransactionNotFoundException(String transactionId) {
        super("TRANSACTION_NOT_FOUND", "Transaction not found: " + transactionId);
    }
}
