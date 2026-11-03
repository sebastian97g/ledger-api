CREATE TABLE transactions (
    id                      VARCHAR(36)     NOT NULL,
    description             VARCHAR(255)    NULL,
    currency                VARCHAR(3)      NOT NULL,
    recorded_at             TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    reverses_transaction_id VARCHAR(36)     NULL,
    CONSTRAINT pk_transactions PRIMARY KEY (id),
    CONSTRAINT uq_transactions_reversal UNIQUE (reverses_transaction_id),
    CONSTRAINT fk_transactions_reversal FOREIGN KEY (reverses_transaction_id)
        REFERENCES transactions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE entries (
    id              VARCHAR(36)     NOT NULL,
    transaction_id  VARCHAR(36)     NOT NULL,
    account_id      VARCHAR(36)     NOT NULL,
    direction       ENUM('DEBIT','CREDIT') NOT NULL,
    amount          DECIMAL(19,4)   NOT NULL,
    balance_after   DECIMAL(19,4)   NOT NULL,
    created_at      TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_entries PRIMARY KEY (id),
    CONSTRAINT chk_entries_amount CHECK (amount > 0),
    CONSTRAINT fk_entries_transaction FOREIGN KEY (transaction_id)
        REFERENCES transactions(id),
    CONSTRAINT fk_entries_account FOREIGN KEY (account_id)
        REFERENCES accounts(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_entries_account_created ON entries (account_id, created_at DESC);
CREATE INDEX idx_entries_transaction ON entries (transaction_id);
