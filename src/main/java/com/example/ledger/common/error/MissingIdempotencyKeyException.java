package com.example.ledger.common.error;

/**
 * Thrown when a POST request is missing the required Idempotency-Key header.
 * HTTP 400.
 */
public class MissingIdempotencyKeyException extends LedgerException {

    public MissingIdempotencyKeyException() {
        super("MISSING_IDEMPOTENCY_KEY",
              "The Idempotency-Key header is required for this request");
    }
}
