CREATE TABLE outbox_events (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    aggregate_type  VARCHAR(64)     NOT NULL,
    aggregate_id    VARCHAR(36)     NOT NULL,
    account_id      VARCHAR(36)     NOT NULL,
    event_type      VARCHAR(64)     NOT NULL,
    payload         TEXT            NOT NULL,
    correlation_id  VARCHAR(255)    NULL,
    status          ENUM('PENDING','PUBLISHED') NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at    TIMESTAMP(6)    NULL,
    CONSTRAINT pk_outbox_events PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_outbox_events_status_id ON outbox_events (status, id);
