CREATE TABLE idempotency_keys (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    scope           VARCHAR(64)     NOT NULL,
    key_value       VARCHAR(255)    NOT NULL,
    request_hash    VARCHAR(64)     NOT NULL,
    status          ENUM('IN_PROGRESS','COMPLETED') NOT NULL DEFAULT 'IN_PROGRESS',
    response_status INT             NULL,
    response_body   TEXT            NULL,
    created_at      TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_keys_scope_key UNIQUE (scope, key_value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
