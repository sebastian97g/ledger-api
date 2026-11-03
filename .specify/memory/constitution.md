<!--
SYNC IMPACT REPORT (remove before committing)
=============================================
Version change: [UNVERSIONED / blank template] → 1.0.0
Added sections:
  - Core Principles (8 principles derived from CLAUDE.md Non-negotiable principles)
  - Stack & Quality Constraints (Section 2)
  - Testing Standards (Section 3)
  - Governance
Modified principles: N/A (initial population)
Removed sections: N/A (initial population)
Deferred TODOs: None — all placeholders resolved.
-->

# Ledger API Constitution

## Core Principles

### I. Exact Money (NON-NEGOTIABLE)

Monetary values MUST be represented as `BigDecimal` or minor-unit `long` throughout every layer
of the system — domain, persistence, API, and messaging. `double` and `float` are forbidden
everywhere: in Java code, JSON serialisation, and database column types.

- DB columns: `DECIMAL(19,4)` or `BIGINT` minor units; never `FLOAT`/`DOUBLE`.
- JSON: amounts serialised as strings or validated decimals; Jackson MUST be configured with
  `USE_BIG_DECIMAL_FOR_FLOATS`.
- Arithmetic: use `BigDecimal` with explicit scale and rounding mode; assert
  `0.10 + 0.20 == 0.30` in unit tests.

**Rationale**: Floating-point rounding errors accumulate silently and are unacceptable in a
financial ledger. A single misrepresented cent compounds into compliance and audit failures.

### II. Immutable Ledger (NON-NEGOTIABLE)

Transactions and entries are insert-only records. `UPDATE` and `DELETE` MUST NOT be issued
against `transactions` or `entries` tables — enforced by the application (no setters, no JPA
cascade removal) and ideally by DB-level grants or triggers.

- Corrections are expressed as reversal transactions, not edits.
- Entities have no mutating setters; use constructor injection and immutable value objects.

**Rationale**: An auditable financial ledger requires a complete, tamper-evident history.
Mutating past records destroys the audit trail and violates accounting immutability invariants.

### III. Atomic Transactions (NON-NEGOTIABLE)

Every "record transaction" operation — entries, balance updates, idempotency key insertion, and
outbox row — MUST commit in a single database transaction, or nothing commits.

- `@Transactional` MUST be applied only to public methods invoked through a Spring proxy;
  never on private or self-invoked methods.
- Partial states (entries written but no balance update, or idempotency key without effect) are
  forbidden.

**Rationale**: A ledger that can partially record a transaction produces inconsistent balances
and phantom idempotency records that silently suppress legitimate retries.

### IV. Zero-Sum Integrity (NON-NEGOTIABLE)

For every supported currency, the sum of all account balances MUST equal zero at all times.
Each double-entry transaction MUST debit and credit in equal amounts within the same currency.

- A test or SQL query that asserts `SUM(balance) = 0` per currency MUST exist and run in CI.
- Unbalanced entry sets MUST be rejected at the domain/service layer before any DB write.

**Rationale**: Double-entry accounting's fundamental invariant. Any non-zero sum signals a
missing counterpart entry — a data-integrity failure that invalidates all downstream reporting.

### V. DB-Level Concurrency Safety (NON-NEGOTIABLE)

Correctness under concurrent requests across multiple service instances MUST be guaranteed
by database-level mechanisms only. In-memory locks, `synchronized` blocks, and per-instance
caches of balances are forbidden.

- Account rows MUST be locked with `SELECT ... FOR UPDATE` before reading balances or writing
  entries.
- Locks on multiple accounts in one transaction MUST be acquired in ascending `account_id`
  order to prevent A→B / B→A deadlocks.
- Overdraft checks MUST occur after the lock is held.
- The application MUST retry on deadlock or lock-timeout with bounded back-off.

**Rationale**: Multiple service instances share one database. Any concurrency control that
lives outside the DB is invisible to other instances and permits lost updates or phantom reads.

### VI. Idempotency Everywhere (NON-NEGOTIABLE)

Every mutating endpoint and every message consumer MUST be idempotent.

- All POST endpoints require an `Idempotency-Key` header.
- The `idempotency_keys(scope, key)` table has a UNIQUE constraint on `(scope, key)`; the row
  is inserted in the same DB transaction as the effect.
- Same key + same canonical request hash → replay stored response (status + body).
- Same key + different hash → `422`/`409`; never silently processed as a retry.
- Concurrent duplicates: one wins via unique constraint; the other waits or returns `409` with
  `Retry-After`.
- Payment consumer deduplication is on `payment_id` (UNIQUE), not on event id.

**Rationale**: Clients and brokers retry on network failure. Without idempotency, retries
double-record transactions and corrupt balances.

### VII. Reliable Messaging (NON-NEGOTIABLE)

Outbound ledger events MUST be published with at-least-once delivery and zero silent loss.
Inbound payment messages MUST never silently drop and MUST never permanently block a partition.

- Outbound: transactional outbox table written in the recording transaction; a relay
  (poller with `SELECT ... FOR UPDATE SKIP LOCKED`, or Debezium) publishes to the broker.
  Guarantee: at-least-once, ordered per account; consumers deduplicate by transaction id.
- Inbound: exceptions in the consumer MUST NOT be swallowed; a message MUST NOT be
  acknowledged before its DB transaction commits; poison messages go to a DLQ after exhausting
  retries and never block the partition.
- Crash-after-record behaviour: the outbox row survives; the relay republishes after restart.
  This MUST be documented in the README.

**Rationale**: Silent message loss breaks event-driven consumers. Blocking a partition on a
poison message halts all subsequent processing — the DLQ preserves progress while isolating
bad data.

### VIII. Decision Documentation (NON-NEGOTIABLE)

Every technical decision not mandated by the requirements MUST be recorded in the README under
a "Decisions" section. The README MUST also contain an "Improvements" section listing what was
deferred and why.

- An `AI_USAGE.md` file MUST document what AI tooling was used, for which tasks, and how
  outputs were verified.
- Undocumented design choices in code review are treated as compliance violations.

**Rationale**: A financial service must be auditable at the architecture level. Future
maintainers and evaluators need to understand why choices were made, not just what was built.

## Stack & Quality Constraints

**Language & Runtime**: Java 17, Spring Boot 4.x, Maven wrapper (`./mvnw`).

**Persistence**: MySQL 8 (InnoDB). Isolation level MUST be documented (READ COMMITTED or
REPEATABLE READ — document the choice and its implications for phantom reads). Schema migrations
via Flyway only; `ddl-auto=update` is forbidden.

**Broker**: Kafka (KRaft, single node in compose). Topics: `payments.settled`,
`payments.settled.dlq`, `ledger.transactions.recorded`. RabbitMQ is acceptable only if
justified in the README Decisions section.

**API**: REST under `/v1/...`; resource identifiers as UUIDs or ULIDs; `Location` header on
201 responses; cursor-based pagination for entry lists; errors as RFC 9457
`application/problem+json` with stable `code`, `detail`, and `errors[]` containing JSON
Pointer to the faulty field.

**Observability**: `X-Correlation-Id` / `traceparent` propagated via MDC into logs, outbox
rows, and message headers. Payment id and event id MUST be logged on every consumer invocation.

**Currency**: `Currency` enum limited to EUR (2 dp), USD (2 dp), JPY (0 dp). Validation MUST
distinguish "not ISO 4217" (400) from "valid ISO but unsupported" (422).

**Forbidden constructs**: `double`, `float`, `synchronized`, in-memory balance caches,
`ddl-auto=update`, `@Transactional` on private/self-invoked methods, swallowing consumer
exceptions, acknowledging a message before DB commit.

## Testing Standards

Integration tests for all guarantee scenarios MUST use **Testcontainers** against a real MySQL
instance and a real Kafka broker — no mocked DB or mocked broker for guarantee-level tests.

**Required test scenarios**:
- Concurrent opposite transfers: no deadlock, no lost update.
- Overdraft never breached under N parallel debits.
- Idempotent retry: same key + same body returns stored response; cross-instance and
  crash-halfway simulation.
- Key reuse with different body returns 409/422.
- Double reversal under race condition: only one succeeds.
- Payment redelivery: same `payment_id` under new event id does not double-record.
- Poison message → DLQ; next message on partition processed normally.
- Refused transaction leaves no entries, no balance change, no outbox row, no idempotency
  success record.
- Per-currency `SUM(balance) = 0` assertion.
- Outbox-to-broker delivery verified end-to-end; relay restart after simulated crash.

**Unit tests** MUST cover: every validation rule with a "refused" example, money scale per
currency, `BigDecimal` arithmetic exactness (`0.10 + 0.20 == 0.30`).

**Run**: `./run.sh` MUST build, start all services via Docker Compose (MySQL, Kafka, 2+ ledger
instances), and execute the full test suite with only Docker, Git, and JDK installed.

## Governance

This constitution supersedes all other practices, patterns, or conventions within this
repository. Where a conflict exists between this document and any other guide, this document
takes precedence.

**Amendment procedure**:
1. Propose the amendment with a rationale tied to a concrete problem or requirement.
2. Update `.specify/memory/constitution.md` and bump the version per semantic versioning rules
   (MAJOR: principle removal/redefinition; MINOR: new principle or material expansion;
   PATCH: clarification or wording).
3. Update `LAST_AMENDED_DATE` to the date of change.
4. Record the amendment in the Sync Impact Report comment (remove before committing).
5. Reference the amended constitution version in the relevant commit message.

**Compliance review**: Every PR MUST be verified against this constitution before merge.
Non-compliance blocks merge. The reviewer is responsible for checking all eight principles.

**Guidance file**: `CLAUDE.md` provides runtime development guidance and stack defaults.
This constitution governs the non-negotiable invariants; `CLAUDE.md` governs implementation
defaults that may be challenged with justification.

**Version**: 1.0.0 | **Ratified**: 2026-11-03 | **Last Amended**: 2026-11-03
