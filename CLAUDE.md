# CLAUDE.md — Ledger API

Double-entry ledger service (Java 17, Spring Boot 4, MySQL, message broker, Docker). Source of truth for the challenge: `requirements.md`. Read it before any spec/plan/implementation work.

## Workflow (Spec Kit)
Follow this order; do not skip steps or write code before the plan is approved.
1. `/speckit.constitution` — derive principles from "Non-negotiable principles" below.
2. `/speckit.specify` — feed it `requirements.md` (what/why only, no tech choices).
3. `/speckit.clarify` — resolve open questions listed in `requirements.md` §10.
4. `/speckit.plan` — tech decisions from "Stack & decisions" below.
5. `/speckit.tasks`, then `/speckit.analyze`, then `/speckit.implement` in small slices.
- Write the OpenAPI contract (`api/openapi.yaml`) BEFORE controllers; keep it in sync (contract test fails on drift).
- Commit small and often with meaningful messages; history is evaluated. Commit this file and any Spec Kit/instruction files (AI usage is disclosed).

## Non-negotiable principles
1. **Money is exact**: `BigDecimal` / minor units (`long`) only. Never `double`/`float`, anywhere (code, JSON, DB). DB: `DECIMAL(19,4)` or BIGINT minor units. JSON amounts are strings or validated decimals, never floats parsed lossy (configure Jackson `USE_BIG_DECIMAL_FOR_FLOATS`).
2. **Immutability**: transactions and entries are insert-only. No UPDATE/DELETE on those tables (no setters, no JPA cascade removal; consider DB triggers/revoked grants). Corrections = reversal transaction.
3. **Atomicity**: a transaction (entries + balance updates + idempotency record + outbox row) commits in ONE DB transaction, or nothing.
4. **Integrity**: per currency, sum of all account balances = 0 at all times. Provide a test/query that asserts it.
5. **Correctness under concurrency across several instances**: DB-level guarantees only (no in-memory locks, no per-instance caches of balances).
6. **Idempotent everywhere**: account open, record, reverse, payment-settled consumption.
7. **Reliable messaging**: transactional outbox for announcements; consumer is idempotent; poison messages go to a DLQ, never block the partition, never silently dropped.
8. **Explain decisions**: README documents every non-specified call.

## Stack & decisions (defaults; challenge them in /speckit.plan only with reasons)
- Java 17, Spring Boot 4.x, Maven (wrapper), Spring Web, Validation, Data JPA or JdbcTemplate/jOOQ (prefer plain SQL control for locking), Flyway, Actuator.
- MySQL 8 (InnoDB, READ COMMITTED or REPEATABLE READ — document choice).
- Broker: Kafka (KRaft, single node in compose). Topics: `payments.settled` (+ `payments.settled.dlq`), `ledger.transactions.recorded`. Alternative RabbitMQ acceptable if justified.
- Testing: JUnit 5, Testcontainers (real MySQL + real broker), Awaitility, REST-assured/MockMvc, ArchUnit optional.
- Docker: multi-stage Dockerfile, `docker-compose.yml` (mysql, broker, 2+ ledger instances behind nginx or direct ports for concurrency tests), `run.sh` = build + start + run tests.

### Key design choices
- **Accounts hold a materialised `balance`** updated in the same DB tx as entries. Each entry stores `balance_after`.
- **Locking**: `SELECT ... FOR UPDATE` on all involved account rows, **always in ascending account-id order** (prevents A→B / B→A deadlocks). Check overdraft after lock. Add retry on deadlock/lock-timeout as a safety net.
- **Overdraft rule**: for a credit-normal balance, `balance_after >= -overdraft_limit`. Credit increases, debit decreases.
- **Currency**: `Currency` enum limited to EUR(2), USD(2), JPY(0); validate ISO 4217 via `java.util.Currency` first → distinguish "not ISO" (invalid) from "valid but unsupported" (e.g. GBP).
- **Idempotency**: `Idempotency-Key` header (required on POST). Table `idempotency_keys(key, scope, request_hash, status, response_status, response_body, created_at)`; unique on (scope,key). Same key + same canonical request hash → replay stored response (same status/body, even from another instance or after a crash mid-way — insert the key row in the same tx as the effect, so a crash leaves no half state). Same key + different hash → `422`/`409` error, never treated as retry. Concurrent duplicate in flight → one wins via unique constraint, other waits/returns stored result or 409 with `Retry-After`.
- **Reversal**: new tx with flipped entries, `reverses_tx_id` UNIQUE (guarantees once-only at DB level); reversal of a reversal refused; overdraft rules apply. Natural idempotency in addition to header.
- **Payment redelivery**: dedupe on `payment_id` (UNIQUE on a `settled_payments` table or unique `external_ref` on transaction), NOT on event id — a re-send under a new event id must not double-record. Also keep processed event ids for tracing. Different payload for same payment_id → log/DLQ as conflict.
- **Announcements**: transactional outbox table written in the recording tx; a relay (poller with `SELECT ... FOR UPDATE SKIP LOCKED`, or Debezium optional) publishes to the broker. Guarantee: **at-least-once, no loss, duplicates possible, ordered per account (key = ledger sequence / account partition key); consumers dedupe by transaction id**. State this explicitly in README, including crash-after-record behaviour (outbox row survives → published after restart).
- **Errors**: RFC 9457 `application/problem+json` with stable `code`, `detail`, and `errors[]` containing JSON pointer to the faulty field (e.g. `/entries/1/amount`). Status: 400 validation, 404 unknown resource (entry's account → 422 or 400 with pointer; document), 409 conflict (already reversed / idempotency), 422 business rule (unbalanced, overdraft, currency mismatch).
- **API**: `/v1/...` versioning in path; ids as UUIDs/ULIDs; `Location` header on create; cursor pagination for entries.
- **Observability**: `X-Correlation-Id` / `traceparent` propagated via MDC into logs, outbox rows, and message headers; payment id/event id logged on consume.

## Code structure
Package-by-feature with hexagonal flavour, small and readable:
```
com.example.ledger
  account/      domain, service, persistence, web
  transaction/  domain (Transaction, Entry, Money, validation), service, persistence, web
  payment/      consumer (inbound adapter), mapper to transaction
  outbox/       publisher/relay
  idempotency/  filter/service, persistence
  common/       errors, correlation, config
```
- Domain validation (balance, currency, decimals, min entries) lives in the domain/service layer, pure and unit-testable, independent of Spring.
- Controllers are thin; no business logic; DTOs separate from entities.
- Constructor injection, immutable DTOs (records), no Lombok magic required.

## Testing rules
- Unit: validation rules (each "refused" example), money scale per currency, `0.10 + 0.20 == 0.30`.
- Integration (Testcontainers, real MySQL + broker) proving §6 guarantees: concurrent opposite transfers (no deadlock, no lost update), overdraft never breached under N parallel debits, idempotent retry incl. cross-instance and crash-halfway simulation, key reuse with different body, double reversal under race, payment redelivery with same and new event id, poison message → DLQ and next message processed, refused tx leaves nothing (no entries, balances, outbox, idempotency success), per-currency sum = 0, outbox → broker delivery and relay restart.
- Never mock the DB for guarantee tests.
- Run everything with `./run.sh`; it must work with only Docker, Git, JDK installed.

## Commands
- Build/test: `./mvnw verify`
- Full stack + tests: `./run.sh`
- Sample payment message: `./scripts/send-payment-settled.sh <paymentId> <merchantAccountId> <amount> <currency> [eventId]`

## Do / Don't
- DO note each unspecified decision in README ("Decisions") and keep README's final section **Improvements** (what before production, what skipped and why).
- DO write `AI_USAGE.md` (what AI was used for, how output was verified).
- DON'T use floating point, in-memory locks, `synchronized`, `@Transactional` on private/self-invoked methods, or `ddl-auto=update` (use Flyway).
- DON'T swallow exceptions in the consumer; DON'T ack a message before the DB tx commits.
- DON'T add features beyond `requirements.md` except listed nice-to-haves, and only after must-haves are green.
