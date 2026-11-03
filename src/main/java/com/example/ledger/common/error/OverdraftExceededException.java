package com.example.ledger.common.error;

import java.math.BigDecimal;

/**
 * Thrown when a transaction would cause an account's balance to fall below
 * its allowed overdraft limit.
 * HTTP 422.
 */
public class OverdraftExceededException extends LedgerException {

    private final String accountId;
    private final String pointer;

    public OverdraftExceededException(String accountId, BigDecimal projectedBalance,
                                      BigDecimal overdraftLimit, String pointer) {
        super("OVERDRAFT_EXCEEDED",
              "Account " + accountId + " would breach overdraft limit: projected balance=" +
              projectedBalance.toPlainString() + " limit=-" + overdraftLimit.toPlainString());
        this.accountId = accountId;
        this.pointer = pointer;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getPointer() {
        return pointer;
    }
}
