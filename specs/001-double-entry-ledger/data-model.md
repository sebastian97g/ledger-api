# Data Model: Double-Entry Ledger

**Date**: 2026-11-03 | **Plan**: [plan.md](plan.md)

## Entity Relationship Overview

```
Account 1──* Entry *──1 Transaction
                           │
                           │ reverses (0..1)
                           ▼
                       Transaction

Account 1──* OutboxEvent
Transaction 1──* OutboxEvent
Transaction 1──0..1 SettledPayment
IdempotencyKey (standalone, scoped)
```

## Entities

### Account

Represents a financial account with a materialised balance.

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| id | UUIDv7 (string) | PK | Time-ordered, globally unique |
| currency | VARCHAR(3) | NOT NULL, CHECK in (EUR, USD, JPY) | ISO 4217 code |
| overdraft_limit | DECIMAL(19,4) | NULL = unlimited | Default 0 for regular accounts; NULL for Settlement accounts |
| balance | DECIMAL(19,4) | NOT NULL, DEFAULT 0 | Materialised; updated in same tx as entries |
| created_at | TIMESTAMP(6) | NOT NULL | Microsecond precision |

**Validation rules**:
- Currency must be valid ISO 4217 AND in supported set {EUR, USD, JPY}
- Overdraft limit >= 0 when not null
- Balance starts at 0

**Pre-seeded accounts** (via Flyway migration):
- `Settlement EUR` — currency EUR, overdraft_limit NULL
- `Settlement USD` — currency USD, overdraft_limit NULL
- `Settlement JPY` — currency JPY, overdraft_limit NULL

### Transaction

An immutable record of money movement. Insert-only — no UPDATE or DELETE.

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| id | UUIDv7 (string) | PK | |
| description | VARCHAR(255) | NULL | Optional human-readable description |
| currency | VARCHAR(3) | NOT NULL | All entries share this currency |
| recorded_at | TIMESTAMP(6) | NOT NULL | When the transaction was committed |
| reverses_transaction_id | UUIDv7 (string) | UNIQUE, NULL, FK → transactions.id | Non-null only for reversals; UNIQUE guarantees once-only reversal |

**Validation rules**:
- Must have >= 2 entries
- All entry accounts must share the same currency
- Sum of debit amounts == sum of credit amounts
- No account's balance_after breaches its overdraft limit
- If reversal: original must not already be reversed; original must not itself be a reversal

**State transitions**: None — transactions are immutable. A reversal is a new transaction linked to the original.

### Entry

A single leg of a transaction. Insert-only — no UPDATE or DELETE.

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| id | UUIDv7 (string) | PK | |
| transaction_id | UUIDv7 (string) | NOT NULL, FK → transactions.id | |
| account_id | UUIDv7 (string) | NOT NULL, FK → accounts.id | |
| direction | ENUM('DEBIT','CREDIT') | NOT NULL | |
| amount | DECIMAL(19,4) | NOT NULL, > 0 | Positive; precision <= currency's decimal places |
| balance_after | DECIMAL(19,4) | NOT NULL | Account balance immediately after this entry |
| created_at | TIMESTAMP(6) | NOT NULL | |

**Validation rules**:
- Amount must be positive
- Amount decimal places must not exceed currency's allowed decimals (EUR/USD: 2, JPY: 0)
- Account must exist

**Indexes**:
- `(account_id, created_at DESC)` — for paginated entry listing per account
- `(transaction_id)` — for loading entries by transaction

### IdempotencyKey

Tracks processed requests for idempotent replay. Inserted in same DB tx as the effect.

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| id | BIGINT AUTO_INCREMENT | PK | Internal; not exposed |
| scope | VARCHAR(64) | NOT NULL | e.g., "CREATE_ACCOUNT", "RECORD_TX", "REVERSE_TX" |
| key | VARCHAR(255) | NOT NULL | From `Idempotency-Key` header |
| request_hash | VARCHAR(64) | NOT NULL | SHA-256 of canonical request body |
| status | ENUM('IN_PROGRESS','COMPLETED') | NOT NULL | For crash recovery |
| response_status | INT | NULL | HTTP status of stored response |
| response_body | TEXT | NULL | JSON body of stored response |
| created_at | TIMESTAMP(6) | NOT NULL | |

**Constraints**:
- UNIQUE on `(scope, key)`
- Same key + same hash → replay stored response
- Same key + different hash → 409 Conflict

### SettledPayment

Records processed payment-settled messages for deduplication.

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| id | BIGINT AUTO_INCREMENT | PK | Internal |
| payment_id | VARCHAR(255) | UNIQUE, NOT NULL | Business dedup key |
| event_id | VARCHAR(255) | NOT NULL | Broker event id, for tracing |
| merchant_account_id | UUIDv7 (string) | NOT NULL, FK → accounts.id | |
| amount | DECIMAL(19,4) | NOT NULL | Original payment amount |
| currency | VARCHAR(3) | NOT NULL | |
| transaction_id | UUIDv7 (string) | NOT NULL, FK → transactions.id | Resulting ledger transaction |
| created_at | TIMESTAMP(6) | NOT NULL | |

**Dedup logic**:
- On insert, if `payment_id` already exists:
  - Same merchant + amount + currency → skip (normal redelivery)
  - Different payload → DLQ as conflict

### OutboxEvent

Transactional outbox for reliable event publishing.

| Field | Type | Constraints | Notes |
|-------|------|-------------|-------|
| id | BIGINT AUTO_INCREMENT | PK | Monotonic sequence |
| aggregate_type | VARCHAR(64) | NOT NULL | e.g., "TRANSACTION" |
| aggregate_id | UUIDv7 (string) | NOT NULL | Transaction id |
| account_id | UUIDv7 (string) | NOT NULL | Partition key for per-account ordering |
| event_type | VARCHAR(64) | NOT NULL | e.g., "TRANSACTION_RECORDED" |
| payload | TEXT | NOT NULL | JSON event payload |
| correlation_id | VARCHAR(255) | NULL | From X-Correlation-Id / traceparent |
| status | ENUM('PENDING','PUBLISHED') | NOT NULL, DEFAULT 'PENDING' | |
| created_at | TIMESTAMP(6) | NOT NULL | |
| published_at | TIMESTAMP(6) | NULL | |

**Relay behaviour**:
- Poller: `SELECT ... WHERE status = 'PENDING' ORDER BY id FOR UPDATE SKIP LOCKED LIMIT N`
- After successful Kafka send: `UPDATE SET status = 'PUBLISHED', published_at = NOW(6)`
- Partition key = `account_id` (per-account ordering)
- One outbox row per account touched by a transaction (multi-entry tx → multiple outbox rows)

## Flyway Migration Order

1. `V1__create_accounts.sql` — accounts table + pre-seed Settlement accounts
2. `V2__create_transactions_entries.sql` — transactions and entries tables with indexes
3. `V3__create_idempotency_keys.sql` — idempotency_keys table with unique constraint
4. `V4__create_settled_payments.sql` — settled_payments table with unique payment_id
5. `V5__create_outbox_events.sql` — outbox_events table with status index
