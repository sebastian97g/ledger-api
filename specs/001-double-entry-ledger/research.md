# Research: Double-Entry Ledger

**Date**: 2026-11-03 | **Plan**: [plan.md](plan.md)

No NEEDS CLARIFICATION items remained after the Technical Context was filled — all decisions were resolved from CLAUDE.md Stack & decisions, the constitution, and the clarification session. This document records the rationale behind each key design choice.

## R1: MySQL Isolation Level

**Decision**: REPEATABLE READ (InnoDB default)

**Rationale**: Combined with `SELECT ... FOR UPDATE`, REPEATABLE READ provides:
- Consistent reads within a transaction (no non-repeatable reads)
- Gap locking on `SELECT FOR UPDATE` prevents phantom inserts by concurrent transactions
- InnoDB default — no configuration change needed; well-understood behaviour

**Alternatives considered**:
- READ COMMITTED: lower lock contention, but requires explicit gap-lock handling and allows phantom reads between statements within a transaction. Would need `FOR UPDATE` on every read regardless. The benefit of reduced contention does not outweigh the added complexity for this workload.
- SERIALIZABLE: strongest guarantees but converts all reads to `SELECT ... FOR SHARE`, causing unnecessary lock contention on read-only queries (account reads, entry listings).

## R2: Locking Strategy for Concurrent Transfers

**Decision**: `SELECT ... FOR UPDATE` on all involved account rows, acquired in ascending `account_id` order within a single DB transaction.

**Rationale**: Consistent lock ordering eliminates the A→B / B→A deadlock scenario. Ascending order is deterministic and simple. Overdraft check happens after lock acquisition, ensuring the balance read is authoritative.

**Alternatives considered**:
- Optimistic locking (version column): would require retry loops at the application level for every contended write. With multiple entries per transaction needing consistent balance reads, pessimistic locking is simpler and more predictable.
- Advisory locks (`GET_LOCK`): MySQL-specific, harder to reason about in multi-statement transactions, and don't integrate with InnoDB's MVCC.

## R3: Idempotency Key Design

**Decision**: Table `idempotency_keys` with UNIQUE `(scope, key)`, status column (IN_PROGRESS / COMPLETED), request hash, and stored response. Row inserted in the same DB transaction as the effect.

**Rationale**: The status column enables crash recovery — an IN_PROGRESS record from a crashed instance can be detected and the operation retried or completed. Storing the response allows exact replay without re-executing business logic. The request hash detects key reuse with different payloads (→ 409).

**Alternatives considered**:
- Idempotency via natural keys only (e.g., payment_id): works for payments but not for arbitrary transactions. A generic mechanism is needed.
- Redis-based idempotency: violates the constitution (Principle V: DB-level guarantees only). A crash between Redis write and DB commit creates inconsistency.

## R4: Outbox Relay Mechanism

**Decision**: Scheduled poller using `SELECT ... FOR UPDATE SKIP LOCKED` + Kafka producer.

**Rationale**: `SKIP LOCKED` allows multiple relay instances to poll concurrently without blocking. The poller marks rows as published after successful Kafka send. On crash, unpublished rows are picked up on next poll (at-least-once). Simple, no external dependencies beyond MySQL and Kafka.

**Alternatives considered**:
- Debezium CDC: powerful but adds operational complexity (connector management, schema registry). Overkill for a challenge-scope service.
- Application-level publish in the same thread: if Kafka send fails after DB commit, the transaction is recorded but the announcement is lost — violates Principle VII.

## R5: Payment Deduplication Strategy

**Decision**: `settled_payments` table with UNIQUE on `payment_id`. Stores full payload metadata for conflict detection. Dedup key is `payment_id`, not `event_id`.

**Rationale**: Brokers may redeliver the same payment under a new event id (rebalance, timeout). Using payment_id as the dedup key correctly handles this. Storing the original payload allows detecting conflicting redeliveries (same payment_id, different amount/merchant/currency → DLQ).

**Alternatives considered**:
- Dedup on event_id: fails when the broker redelivers the same payment under a new event id.
- Dedup on (merchant, amount, currency): too broad — legitimate different payments may share these attributes.

## R6: Announcement Ordering

**Decision**: Per-account ordering. Kafka partition key = account id.

**Rationale**: Global ordering would require a single partition, bottlenecking throughput. Per-account ordering is sufficient for balance reconciliation and audit. A transaction touching accounts A and B produces one outbox event per account, each routed to the correct partition.

**Alternatives considered**:
- Global ordering (single partition): throughput bottleneck, unnecessary for downstream consumers.
- Transaction-id partitioning: loses per-account ordering, which is the meaningful ordering dimension for consumers.

**Open question resolved**: A multi-entry transaction touching N accounts produces N outbox events (one per account), each partitioned by account id. This ensures per-account ordering while keeping the outbox write in the same DB transaction.

## R7: UUIDv7 vs ULID

**Decision**: UUIDv7 strings.

**Rationale**: Time-ordered (suitable for cursor pagination), globally unique without coordination, standard UUID format (128-bit, hyphenated string representation). Java `java.util.UUID` compatible with libraries like `uuid-creator` or `java-uuid-generator`. ULID is functionally equivalent but less standard in Java ecosystems.

## R8: Overdraft Representation for Settlement Accounts

**Decision**: Nullable `overdraft_limit` column. NULL = unlimited (no overdraft check applied).

**Rationale**: Simpler than a sentinel value (e.g., `Long.MAX_VALUE`). The overdraft check logic is: `if overdraft_limit IS NOT NULL AND balance_after < -overdraft_limit THEN reject`. NULL naturally means "no limit."

## R9: Same-Account Multi-Entry in One Transaction

**Decision**: Allowed if the transaction balances and the net effect does not breach the overdraft limit.

**Rationale**: Some legitimate transaction shapes (e.g., a fee split) may reference the same account in both debit and credit directions. The overdraft check applies to the account's final `balance_after` across all entries, not per-entry.

## R10: Idempotency Key Retention

**Decision**: Retained indefinitely (no TTL in MVP). Document in README as an improvement candidate.

**Rationale**: TTL adds complexity (scheduled cleanup, race between expiry and late retry). For a challenge-scope service, indefinite retention is acceptable. Production would add a cleanup job with a configurable retention period (e.g., 7-30 days).
