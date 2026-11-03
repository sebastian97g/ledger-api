CREATE TABLE settled_payments (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    payment_id          VARCHAR(255)    NOT NULL,
    event_id            VARCHAR(255)    NOT NULL,
    merchant_account_id VARCHAR(36)     NOT NULL,
    amount              DECIMAL(19,4)   NOT NULL,
    currency            VARCHAR(3)      NOT NULL,
    transaction_id      VARCHAR(36)     NOT NULL,
    created_at          TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_settled_payments PRIMARY KEY (id),
    CONSTRAINT uq_settled_payments_payment_id UNIQUE (payment_id),
    CONSTRAINT fk_settled_payments_account FOREIGN KEY (merchant_account_id)
        REFERENCES accounts(id),
    CONSTRAINT fk_settled_payments_transaction FOREIGN KEY (transaction_id)
        REFERENCES transactions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
