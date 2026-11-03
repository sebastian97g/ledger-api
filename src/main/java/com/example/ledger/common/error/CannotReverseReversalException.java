package com.example.ledger.common.error;

/**
 * Thrown when attempting to reverse a transaction that is itself a reversal.
 * HTTP 422.
 */
public class CannotReverseReversalException extends LedgerException {

    public CannotReverseReversalException(String transactionId) {
        super("CANNOT_REVERSE_REVERSAL",
              "Transaction " + transactionId + " is a reversal and cannot be reversed again");
    }
}
