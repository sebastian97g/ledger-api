package com.example.ledger.common.error;

import java.math.BigDecimal;

/**
 * Thrown when the sum of debits != sum of credits in a transaction.
 * HTTP 422.
 */
public class UnbalancedTransactionException extends LedgerException {

    public UnbalancedTransactionException(BigDecimal totalDebits, BigDecimal totalCredits) {
        super("UNBALANCED_TRANSACTION",
              "Transaction entries do not balance: total debits=" + totalDebits.toPlainString() +
              " total credits=" + totalCredits.toPlainString());
    }
}
