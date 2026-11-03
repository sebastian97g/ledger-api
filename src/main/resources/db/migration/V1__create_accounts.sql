CREATE TABLE accounts (
    id          VARCHAR(36)     NOT NULL,
    currency    VARCHAR(3)      NOT NULL,
    overdraft_limit DECIMAL(19,4)   NULL COMMENT 'NULL means unlimited (Settlement accounts)',
    balance     DECIMAL(19,4)   NOT NULL DEFAULT 0.0000,
    created_at  TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_accounts PRIMARY KEY (id),
    CONSTRAINT chk_accounts_currency CHECK (currency IN ('EUR','USD','JPY')),
    CONSTRAINT chk_accounts_overdraft CHECK (overdraft_limit IS NULL OR overdraft_limit >= 0),
    CONSTRAINT chk_accounts_balance_precision CHECK (balance = ROUND(balance, 4))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Pre-seed Settlement accounts with fixed IDs for stable referencing
INSERT INTO accounts (id, currency, overdraft_limit, balance) VALUES
    ('00000000-0000-7001-8000-000000000001', 'EUR', NULL, 0.0000),
    ('00000000-0000-7001-8000-000000000002', 'USD', NULL, 0.0000),
    ('00000000-0000-7001-8000-000000000003', 'JPY', NULL, 0.0000);
