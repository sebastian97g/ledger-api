package com.example.ledger.common.error;

/**
 * Thrown when an idempotency key is reused with a different request body.
 * HTTP 409.
 */
public class IdempotencyKeyConflictException extends LedgerException {

    public IdempotencyKeyConflictException(String key, String scope) {
        super("IDEMPOTENCY_KEY_CONFLICT",
              "Idempotency key '" + key + "' in scope '" + scope +
              "' was already used with a different request body");
    }
}
