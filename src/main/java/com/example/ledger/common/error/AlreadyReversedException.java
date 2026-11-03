package com.example.ledger.common.error;

/**
 * Thrown when a transaction has already been reversed.
 * HTTP 409.
 */
public class AlreadyReversedException extends LedgerException {

    public AlreadyReversedException(String transactionId) {
        super("ALREADY_REVERSED", "Transaction " + transactionId + " has already been reversed");
    }
}
