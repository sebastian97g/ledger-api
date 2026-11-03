# Implementation Plan: Double-Entry Ledger

**Branch**: `001-double-entry-ledger` | **Date**: 2026-11-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-double-entry-ledger/spec.md`

## Summary

Build a double-entry ledger microservice that records immutable, balanced transactions across accounts with exact-money arithmetic. The service consumes `payment-settled` messages from Kafka, exposes a REST API for account/transaction management, and announces recorded transactions via a transactional outbox. Correctness under multi-instance concurrency is guaranteed by database-level locking (SELECT FOR UPDATE in ascending account-id order). All mutating operations are idempotent. The system runs as 2+ instances behind Docker Compose.

## Technical Context

**Language/Version**: Java 17 (LTS)

**Primary Dependencies**: Spring Boot 4.x (Web, Validation, Data JPA/JdbcTemplate, Kafka, Actuator), Flyway, Jackson (configured with `USE_BIG_DECIMAL_FOR_FLOATS`)

**Storage**: MySQL 8 (InnoDB), REPEATABLE READ isolation level (InnoDB default; prevents non-repeatable reads within a transaction; phantom reads are prevented by gap locking with SELECT FOR UPDATE). Schema managed by Flyway migrations only; `ddl-auto=update` forbidden.

**Broker**: Apache Kafka (KRaft mode, single node in compose). Topics: `payments.settled`, `payments.settled.dlq`, `ledger.transactions.recorded`.

**Testing**: JUnit 5, Testcontainers (MySQL + Kafka), REST-assured/MockMvc, Awaitility for async assertions

**Target Platform**: Linux containers (Docker), multi-instance (2+ instances in compose)

**Project Type**: Web service (REST API + message consumer)

**Performance Goals**: Correctness over throughput. Concurrent transfers on overlapping accounts must never breach overdraft, lose updates, or deadlock permanently. 50 concurrent transfers is the test target (SC-002).

**Constraints**: Money as `BigDecimal`/`DECIMAL(19,4)` only — no `double`/`float` anywhere. Transactions and entries are insert-only (immutable). No in-memory locks or synchronized blocks. Single DB transaction for entries + balances + idempotency + outbox.

**Scale/Scope**: Challenge-scope service; 3 supported currencies (EUR, USD, JPY); 3 pre-seeded Settlement accounts; moderate volume sufficient.

### Key Design Decisions

**Isolation level — REPEATABLE READ**: InnoDB default. Combined with `SELECT ... FOR UPDATE`, provides the row-level locking needed for balance reads and updates. Gap locking prevents phantom inserts of concurrent entries. Documented in README.

**Locking strategy**: `SELECT ... FOR UPDATE` on account rows in ascending `account_id` order within each transaction. Prevents A→B / B→A deadlocks. Overdraft checked after lock acquired. Bounded retry with back-off on deadlock/lock-timeout.

**Idempotency**: `idempotency_keys` table with columns: `key`, `scope`, `request_hash`, `status` (IN_PROGRESS | COMPLETED), `response_status`, `response_body`, `created_at`. UNIQUE on `(scope, key)`. Row inserted in the same DB transaction as the effect. Status allows crash recovery: IN_PROGRESS records from a crashed instance are detectable.

**Outbox pattern**: `outbox_events` table written in the recording transaction. A scheduled relay polls with `SELECT ... FOR UPDATE SKIP LOCKED` and publishes to Kafka. Partition key = account id (per-account ordering). At-least-once; consumers deduplicate by transaction id.

**Payment deduplication**: `settled_payments` table with UNIQUE on `payment_id`. Stores `payment_id`, `merchant_account_id`, `amount`, `currency`, `event_id`, `transaction_id`. On duplicate payment id with different payload → DLQ as conflict.

**Account balance**: Materialised `balance` column on `accounts` updated in the same DB tx as entries. Each entry stores `balance_after`. Credit-normal: credit increases balance, debit decreases. Overdraft rule: `balance_after >= -overdraft_limit`.

**ID format**: UUIDv7 strings (time-ordered, sortable, globally unique).

**Error responses**: RFC 9457 `application/problem+json`. Status mapping: 400 (validation/missing header), 404 (unknown resource at URL level), 409 (idempotency key conflict, already reversed), 422 (business rule: overdraft, unbalanced, currency mismatch, unknown account in entry body with JSON Pointer).

**Currency validation**: `java.util.Currency.getInstance()` for ISO 4217 check → if fails, 400 "not valid ISO 4217". If valid but not in supported set {EUR, USD, JPY} → 422 "valid but unsupported".

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Principle | Status | How Addressed |
|---|-----------|--------|---------------|
| I | Exact Money | PASS | `BigDecimal` in domain, `DECIMAL(19,4)` in DB, Jackson `USE_BIG_DECIMAL_FOR_FLOATS`, JSON amounts as strings. No `double`/`float`. |
| II | Immutable Ledger | PASS | No UPDATE/DELETE on `transactions`/`entries`. Immutable domain objects (records). Corrections via reversal only. |
| III | Atomic Transactions | PASS | Single DB transaction for entries + balance updates + idempotency key + outbox row. `@Transactional` on public service methods only. |
| IV | Zero-Sum Integrity | PASS | Domain validation: sum(debits) == sum(credits). Integration test: `SUM(balance) = 0` per currency. Unbalanced rejected before write. |
| V | DB-Level Concurrency | PASS | `SELECT FOR UPDATE` in ascending account-id order. No in-memory locks/synchronized. Retry on deadlock with bounded back-off. |
| VI | Idempotency Everywhere | PASS | `Idempotency-Key` header required on all POSTs (400 if missing). `idempotency_keys` table with UNIQUE (scope, key), status tracking, response storage. Payment dedup on `payment_id`. |
| VII | Reliable Messaging | PASS | Transactional outbox; relay with `SELECT FOR UPDATE SKIP LOCKED`; at-least-once; per-account ordering; poison → DLQ; no ack before commit. |
| VIII | Decision Documentation | PASS | README Decisions section for every unspecified choice. Improvements section. `AI_USAGE.md`. |

**Gate result: ALL PASS** — proceed to Phase 0.

## Project Structure

### Documentation (this feature)

```text
specs/001-double-entry-ledger/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── openapi.yaml     # Phase 1 output — OpenAPI 3.1 contract
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

```text
ledger-api/
├── pom.xml
├── src/
│   └── main/
│       ├── java/com/example/ledger/
│       │   ├── LedgerApplication.java
│       │   ├── account/
│       │   │   ├── domain/          # Account, Currency enum, value objects
│       │   │   ├── service/         # AccountService
│       │   │   ├── persistence/     # AccountRepository, JdbcAccountRepository
│       │   │   └── web/             # AccountController, DTOs
│       │   ├── transaction/
│       │   │   ├── domain/          # Transaction, Entry, Money, validation
│       │   │   ├── service/         # TransactionService (record, reverse)
│       │   │   ├── persistence/     # TransactionRepository, EntryRepository
│       │   │   └── web/             # TransactionController, DTOs
│       │   ├── payment/
│       │   │   ├── consumer/        # PaymentSettledConsumer (Kafka listener)
│       │   │   └── mapper/          # Maps payment message → transaction command
│       │   ├── outbox/
│       │   │   ├── domain/          # OutboxEvent
│       │   │   ├── persistence/     # OutboxRepository
│       │   │   └── relay/           # OutboxRelay (scheduled poller + Kafka producer)
│       │   ├── idempotency/
│       │   │   ├── domain/          # IdempotencyKey, Status enum
│       │   │   ├── service/         # IdempotencyService
│       │   │   ├── persistence/     # IdempotencyRepository
│       │   │   └── web/             # IdempotencyFilter (extracts header, checks/stores)
│       │   └── common/
│       │       ├── error/           # ProblemDetailFactory, GlobalExceptionHandler
│       │       ├── correlation/     # CorrelationIdFilter, MDC config
│       │       └── config/          # JacksonConfig, KafkaConfig
│       └── resources/
│           ├── application.yml
│           ├── db/migration/        # Flyway SQL migrations (V1__, V2__, ...)
│           └── api/
│               └── openapi.yaml     # Canonical OpenAPI contract (symlink or copy)
├── src/test/
│   └── java/com/example/ledger/
│       ├── unit/                    # Domain validation, money arithmetic
│       ├── integration/             # Testcontainers: concurrency, idempotency, outbox, DLQ
│       └── contract/                # OpenAPI contract drift tests
├── api/
│   └── openapi.yaml                # Source-of-truth OpenAPI contract
├── docker/
│   ├── Dockerfile                   # Multi-stage build
│   └── docker-compose.yml           # mysql, kafka, 2x ledger, nginx (optional)
├── scripts/
│   └── send-payment-settled.sh      # Sample payment message sender
└── run.sh                           # Build + start + test
```

**Structure Decision**: Package-by-feature with hexagonal flavour as prescribed by CLAUDE.md. Each feature package (account, transaction, payment, outbox, idempotency) contains domain, service, persistence, and web layers. Common cross-cutting concerns (errors, correlation, config) in `common/`. OpenAPI contract at `api/openapi.yaml` is the source of truth; contract tests verify no drift.

## Complexity Tracking

No constitution violations to justify. All principles addressed with standard patterns.
