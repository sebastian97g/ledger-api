package com.example.ledger.common.error;

/**
 * Thrown when a referenced account does not exist.
 *
 * - If {@code pointer} is null: the account is the top-level resource of the request
 *   (e.g. GET /v1/accounts/{id}) → HTTP 404.
 * - If {@code pointer} is non-null: the account is referenced inside a request body
 *   (e.g. an entry in a transaction) → HTTP 422 with JSON Pointer to the faulty field.
 */
public class AccountNotFoundException extends LedgerException {

    private final String accountId;
    private final String pointer;

    /** 404 variant — top-level resource not found. */
    public AccountNotFoundException(String accountId) {
        super("ACCOUNT_NOT_FOUND", "Account not found: " + accountId);
        this.accountId = accountId;
        this.pointer = null;
    }

    /** 422 variant — account referenced in request body at the given JSON Pointer. */
    public AccountNotFoundException(String accountId, String pointer) {
        super("ACCOUNT_NOT_FOUND", "Account not found: " + accountId);
        this.accountId = accountId;
        this.pointer = pointer;
    }

    public String getAccountId() {
        return accountId;
    }

    /** Non-null means the account is embedded in a body field → return 422. */
    public String getPointer() {
        return pointer;
    }
}
