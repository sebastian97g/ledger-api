# Tasks: Double-Entry Ledger

**Input**: Design documents from `specs/001-double-entry-ledger/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/openapi.yaml, quickstart.md

**Tests**: Required — CLAUDE.md and spec mandate integration tests with Testcontainers (real MySQL + Kafka) and unit tests for all validation rules.

**Organization**: Tasks grouped by user story. US2 (Open Account) precedes US1 (Record Transaction) because accounts are a prerequisite.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2)
- Exact file paths included in descriptions

---

## Phase 1: Setup

**Purpose**: Project skeleton, build tooling, Docker, and configuration

- [x] T001 Initialize Maven project with Spring Boot 4.x parent, Java 17, and required dependencies (spring-boot-starter-web, spring-boot-starter-validation, spring-boot-starter-data-jpa, spring-boot-starter-actuator, spring-kafka, flyway-mysql, mysql-connector-j, jackson-datatype-jsr310, java-uuid-generator) in `ledger-api/pom.xml`
- [x] T002 Create Spring Boot application entry point in `ledger-api/src/main/java/com/example/ledger/LedgerApplication.java`
- [x] T003 [P] Configure `ledger-api/src/main/resources/application.yml` with MySQL datasource (Flyway enabled, `ddl-auto=validate`), Kafka bootstrap servers, Jackson `USE_BIG_DECIMAL_FOR_FLOATS: true` and `WRITE_NUMBERS_AS_STRINGS` for BigDecimal, server port, actuator health endpoints
- [x] T004 [P] Create Jackson configuration in `ledger-api/src/main/java/com/example/ledger/common/config/JacksonConfig.java` — register `JavaTimeModule`, configure `DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS`, `JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN`
- [x] T005 [P] Create multi-stage Dockerfile in `ledger-api/docker/Dockerfile` — stage 1: Maven build with `./mvnw package -DskipTests`; stage 2: JRE 17 slim, copy jar, expose port 8080
- [x] T006 [P] Create `ledger-api/docker/docker-compose.yml` with services: mysql (8.x, port 3306, init healthcheck), kafka (KRaft single-node, port 9092), ledger-1 (port 8080), ledger-2 (port 8081), all on a shared network. Kafka topics auto-created: `payments.settled`, `payments.settled.dlq`, `ledger.transactions.recorded`
- [x] T007 [P] Copy OpenAPI contract from `specs/001-double-entry-ledger/contracts/openapi.yaml` to `ledger-api/api/openapi.yaml` as the source-of-truth contract
- [x] T008 [P] Create `ledger-api/run.sh` — builds Maven project, builds Docker image, runs `docker-compose up -d`, waits for health checks (MySQL, Kafka, both ledger instances), runs integration tests via `./mvnw verify`, tears down on exit
- [x] T009 [P] Add test dependencies to `ledger-api/pom.xml`: spring-boot-starter-test, spring-kafka-test, testcontainers (mysql, kafka), rest-assured, awaitility

**Checkpoint**: Project builds, Docker Compose starts all services, health checks pass.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Database schema, domain value objects, error handling, correlation — MUST complete before ANY user story

**CRITICAL**: No user story work can begin until this phase is complete

- [x] T010 Create Flyway migration `ledger-api/src/main/resources/db/migration/V1__create_accounts.sql` — table `accounts` with columns: `id VARCHAR(36) PRIMARY KEY`, `currency VARCHAR(3) NOT NULL CHECK (currency IN ('EUR','USD','JPY'))`, `overdraft_limit DECIMAL(19,4) NULL` (NULL = unlimited), `balance DECIMAL(19,4) NOT NULL DEFAULT 0`, `created_at TIMESTAMP(6) NOT NULL`. Insert pre-seeded Settlement accounts: Settlement EUR (id=fixed UUIDv7, currency=EUR, overdraft_limit=NULL), Settlement USD (currency=USD, overdraft_limit=NULL), Settlement JPY (currency=JPY, overdraft_limit=NULL)
- [x] T011 Create Flyway migration `ledger-api/src/main/resources/db/migration/V2__create_transactions_entries.sql` — table `transactions`: `id VARCHAR(36) PRIMARY KEY`, `description VARCHAR(255) NULL`, `currency VARCHAR(3) NOT NULL`, `recorded_at TIMESTAMP(6) NOT NULL`, `reverses_transaction_id VARCHAR(36) NULL UNIQUE` (FK → transactions.id). Table `entries`: `id VARCHAR(36) PRIMARY KEY`, `transaction_id VARCHAR(36) NOT NULL` (FK → transactions.id), `account_id VARCHAR(36) NOT NULL` (FK → accounts.id), `direction ENUM('DEBIT','CREDIT') NOT NULL`, `amount DECIMAL(19,4) NOT NULL CHECK (amount > 0)`, `balance_after DECIMAL(19,4) NOT NULL`, `created_at TIMESTAMP(6) NOT NULL`. Indexes: `(account_id, created_at DESC)`, `(transaction_id)`
- [x] T012 Create Flyway migration `ledger-api/src/main/resources/db/migration/V3__create_idempotency_keys.sql` — table `idempotency_keys`: `id BIGINT AUTO_INCREMENT PRIMARY KEY`, `scope VARCHAR(64) NOT NULL`, `key_value VARCHAR(255) NOT NULL`, `request_hash VARCHAR(64) NOT NULL`, `status ENUM('IN_PROGRESS','COMPLETED') NOT NULL`, `response_status INT NULL`, `response_body TEXT NULL`, `created_at TIMESTAMP(6) NOT NULL`, UNIQUE `(scope, key_value)`
- [x] T013 Create Flyway migration `ledger-api/src/main/resources/db/migration/V4__create_settled_payments.sql` — table `settled_payments`: `id BIGINT AUTO_INCREMENT PRIMARY KEY`, `payment_id VARCHAR(255) NOT NULL UNIQUE`, `event_id VARCHAR(255) NOT NULL`, `merchant_account_id VARCHAR(36) NOT NULL` (FK → accounts.id), `amount DECIMAL(19,4) NOT NULL`, `currency VARCHAR(3) NOT NULL`, `transaction_id VARCHAR(36) NOT NULL` (FK → transactions.id), `created_at TIMESTAMP(6) NOT NULL`
- [x] T014 Create Flyway migration `ledger-api/src/main/resources/db/migration/V5__create_outbox_events.sql` — table `outbox_events`: `id BIGINT AUTO_INCREMENT PRIMARY KEY`, `aggregate_type VARCHAR(64) NOT NULL`, `aggregate_id VARCHAR(36) NOT NULL`, `account_id VARCHAR(36) NOT NULL`, `event_type VARCHAR(64) NOT NULL`, `payload TEXT NOT NULL`, `correlation_id VARCHAR(255) NULL`, `status ENUM('PENDING','PUBLISHED') NOT NULL DEFAULT 'PENDING'`, `created_at TIMESTAMP(6) NOT NULL`, `published_at TIMESTAMP(6) NULL`. Index: `(status, id)` for relay polling
- [x] T015 [P] Create Currency enum in `ledger-api/src/main/java/com/example/ledger/account/domain/Currency.java` — values EUR(2), USD(2), JPY(0) each with `decimalPlaces` field. Static method `fromCode(String)` that first validates via `java.util.Currency.getInstance()` (throws InvalidCurrencyException if not ISO 4217), then checks membership in supported set (throws UnsupportedCurrencyException if valid ISO but not EUR/USD/JPY)
- [x] T016 [P] Create Money value object in `ledger-api/src/main/java/com/example/ledger/transaction/domain/Money.java` — immutable record wrapping `BigDecimal amount` and `Currency currency`. Validation: amount > 0, `amount.scale() <= currency.decimalPlaces()`. Factory method `Money.of(String amount, Currency currency)` that parses with `new BigDecimal(string)` and validates scale. Override equals/hashCode for value semantics
- [x] T017 [P] Create global exception handler in `ledger-api/src/main/java/com/example/ledger/common/error/GlobalExceptionHandler.java` — `@RestControllerAdvice`, returns RFC 9457 `application/problem+json` ProblemDetail responses. Map: `InvalidCurrencyException` → 400, `UnsupportedCurrencyException` → 422, `UnbalancedTransactionException` → 422, `OverdraftExceededException` → 422 with JSON Pointer, `CurrencyMismatchException` → 422, `DecimalPrecisionException` → 422 with JSON Pointer, `AccountNotFoundException` → 422 with JSON Pointer (for entries referencing unknown accounts), `TransactionNotFoundException` → 404, `AlreadyReversedException` → 409, `CannotReverseReversalException` → 422, `IdempotencyKeyConflictException` → 409, `MissingIdempotencyKeyException` → 400, `MethodArgumentNotValidException` → 400 with field pointers. Each response includes `code` (stable string), `detail`, and `errors[]` with `pointer`, `code`, `detail`
- [x] T018 [P] Create domain exception classes in `ledger-api/src/main/java/com/example/ledger/common/error/` — one class per error: `InvalidCurrencyException`, `UnsupportedCurrencyException`, `UnbalancedTransactionException`, `OverdraftExceededException(String jsonPointer)`, `CurrencyMismatchException`, `DecimalPrecisionException(String jsonPointer)`, `AccountNotFoundException(String jsonPointer)`, `TransactionNotFoundException`, `AlreadyReversedException`, `CannotReverseReversalException`, `IdempotencyKeyConflictException`, `MissingIdempotencyKeyException`. All extend a base `LedgerException` with `code` and optional `pointer` fields
- [x] T019 [P] Create correlation ID filter in `ledger-api/src/main/java/com/example/ledger/common/correlation/CorrelationIdFilter.java` — servlet filter that reads `X-Correlation-Id` header (or generates UUIDv7 if absent), sets it in MDC (`correlationId`), adds to response header. Cleared on request completion
- [x] T020 [P] Create Kafka configuration in `ledger-api/src/main/java/com/example/ledger/common/config/KafkaConfig.java` — consumer factory for `payments.settled` (String key, JSON value, manual ack, `JsonDeserializer` with trusted packages), producer factory for outbox relay (String key, String value), topic constants. Configure consumer `isolation.level=read_committed` so it does not read uncommitted producer transactions (if using Kafka transactions) or at minimum `enable.auto.commit=false` with manual ack after DB commit
- [x] T021 [P] Create Testcontainers base test class in `ledger-api/src/test/java/com/example/ledger/integration/BaseIntegrationTest.java` — `@SpringBootTest`, shared MySQL container (8.x) and Kafka container, `@DynamicPropertySource` to wire datasource and Kafka bootstrap servers. Reusable across all integration tests

**Checkpoint**: Database schema created, domain value objects validated, error handling and correlation wired. Flyway migrations run successfully against Testcontainers MySQL.

---

## Phase 3: User Story 2 — Open an Account (Priority: P1)

**Goal**: Clients can open accounts with currency and optional overdraft limit. Idempotent. Invalid/unsupported currencies rejected with distinct errors.

**Independent Test**: Open an account via POST, read it back via GET, verify balance=0. Repeat POST and confirm idempotent replay. Try invalid/unsupported currencies.

### Implementation for User Story 2

- [ ] T022 [US2] Create Account domain entity in `ledger-api/src/main/java/com/example/ledger/account/domain/Account.java` — immutable class with fields: `id` (String, UUIDv7), `currency` (Currency enum), `overdraftLimit` (BigDecimal, nullable = unlimited), `balance` (BigDecimal, default 0), `createdAt` (Instant). No setters. Constructor validates: currency not null, overdraftLimit >= 0 when not null. Method `canDebit(BigDecimal amount)` returns true if `balance - amount >= -overdraftLimit` (or always true if overdraftLimit is null)
- [ ] T023 [US2] Create AccountRepository in `ledger-api/src/main/java/com/example/ledger/account/persistence/AccountRepository.java` — interface with methods: `save(Account)`, `findById(String id)`, `findByIdForUpdate(String id)` (SELECT FOR UPDATE), `findAllByIdForUpdateOrderById(List<String> ids)` (SELECT FOR UPDATE ORDER BY id — ascending lock order), `updateBalance(String id, BigDecimal newBalance)`. Implement with JdbcTemplate in `JdbcAccountRepository.java` in the same package
- [ ] T024 [US2] Create IdempotencyService in `ledger-api/src/main/java/com/example/ledger/idempotency/service/IdempotencyService.java` — methods: `checkAndStart(String scope, String key, String requestHash)` returns Optional<StoredResponse> (empty = proceed, present = replay or conflict). If existing record found: same hash + COMPLETED → return stored response; same hash + IN_PROGRESS → return 409 with Retry-After; different hash → throw IdempotencyKeyConflictException. `complete(String scope, String key, int responseStatus, String responseBody)` updates status to COMPLETED with stored response. Uses `IdempotencyRepository` (JdbcTemplate) in `ledger-api/src/main/java/com/example/ledger/idempotency/persistence/IdempotencyRepository.java`
- [ ] T025 [US2] Create IdempotencyFilter in `ledger-api/src/main/java/com/example/ledger/idempotency/web/IdempotencyFilter.java` — servlet filter on POST requests. Reads `Idempotency-Key` header; if missing → 400 MissingIdempotencyKeyException. Computes SHA-256 hash of canonical request body. Calls `IdempotencyService.checkAndStart()`. If replay → return stored response immediately. Otherwise wraps response to capture status+body, calls `IdempotencyService.complete()` after controller returns. Scope derived from request path pattern (e.g., "CREATE_ACCOUNT", "RECORD_TX", "REVERSE_TX")
- [ ] T026 [US2] Create AccountService in `ledger-api/src/main/java/com/example/ledger/account/service/AccountService.java` — `openAccount(String currency, BigDecimal overdraftLimit)`: validates currency via `Currency.fromCode()`, generates UUIDv7, saves via repository, returns Account. `getAccount(String id)`: finds by id, throws TransactionNotFoundException-style error (AccountNotFoundException for 404 at resource level) if not found. `@Transactional` on public methods only
- [ ] T027 [US2] Create AccountController in `ledger-api/src/main/java/com/example/ledger/account/web/AccountController.java` — `POST /v1/accounts` (OpenAccountRequest DTO → AccountService.openAccount → AccountResponse DTO, 201 + Location header). `GET /v1/accounts/{accountId}` (→ AccountResponse, 404 if not found). DTOs: `OpenAccountRequest` (currency: String required, overdraftLimit: String optional default "0"), `AccountResponse` (id, currency, overdraftLimit as string or null, balance as string, createdAt). Amounts serialised as strings in JSON
- [ ] T028 [US2] Write unit tests in `ledger-api/src/test/java/com/example/ledger/unit/CurrencyTest.java` — test `Currency.fromCode("EUR")` succeeds, `fromCode("EURO")` throws InvalidCurrencyException, `fromCode("ABC")` throws InvalidCurrencyException, `fromCode("GBP")` throws UnsupportedCurrencyException, `fromCode("USD")` succeeds, `fromCode("JPY")` succeeds with 0 decimal places
- [ ] T029 [US2] Write integration test in `ledger-api/src/test/java/com/example/ledger/integration/AccountIntegrationTest.java` — extends BaseIntegrationTest. Tests: create account (201 + Location + balance 0), read account back (200), idempotent retry (same key same body → 200 same response), idempotent conflict (same key different body → 409), missing Idempotency-Key → 400, invalid currency "EURO" → 400, unsupported currency "GBP" → 422 distinct error code, Settlement accounts exist at startup with NULL overdraft

**Checkpoint**: Accounts can be opened, read, and rejected correctly. Idempotency infrastructure proven.

---

## Phase 4: User Story 1 — Record a Transaction (Priority: P1)

**Goal**: Record multi-entry double-entry transactions with full validation (balanced, same currency, decimal precision, overdraft). Atomic — all or nothing.

**Independent Test**: Record a valid transaction, read it back, verify entries and balances. Attempt each refusal scenario from spec §5.

### Implementation for User Story 1

- [ ] T030 [US1] Create Entry domain object in `ledger-api/src/main/java/com/example/ledger/transaction/domain/Entry.java` — immutable record: `id` (String, UUIDv7), `transactionId` (String), `accountId` (String), `direction` (enum DEBIT/CREDIT), `amount` (BigDecimal, positive), `balanceAfter` (BigDecimal), `createdAt` (Instant)
- [ ] T031 [US1] Create Transaction domain object in `ledger-api/src/main/java/com/example/ledger/transaction/domain/Transaction.java` — immutable class: `id` (String, UUIDv7), `description` (String, nullable), `currency` (Currency), `recordedAt` (Instant), `reversesTransactionId` (String, nullable), `entries` (List<Entry>, unmodifiable). No setters
- [ ] T032 [US1] Create TransactionValidator in `ledger-api/src/main/java/com/example/ledger/transaction/domain/TransactionValidator.java` — pure domain validation (no Spring, no DB). Methods: `validateEntryCount(List<EntryCommand> entries)` — must be >= 2, throws with pointer; `validateBalance(List<EntryCommand> entries)` — sum debits == sum credits using BigDecimal.compareTo, throws UnbalancedTransactionException; `validateDecimalPrecision(BigDecimal amount, Currency currency, int entryIndex)` — amount.scale() <= currency.decimalPlaces(), throws DecimalPrecisionException with pointer `/entries/{index}/amount`; `validateSameCurrency(List<Account> accounts)` — all same currency, throws CurrencyMismatchException
- [ ] T033 [US1] Create TransactionRepository in `ledger-api/src/main/java/com/example/ledger/transaction/persistence/TransactionRepository.java` — interface + JdbcTemplate impl. Methods: `save(Transaction)` inserts into transactions table, `saveEntries(List<Entry>)` batch insert into entries table, `findById(String id)` loads transaction + entries, `findByReversesTransactionId(String originalTxId)` for reversal lookup
- [ ] T034 [US1] Create TransactionService in `ledger-api/src/main/java/com/example/ledger/transaction/service/TransactionService.java` — `@Transactional` `recordTransaction(RecordTransactionCommand)`: (1) validate entry count >= 2, (2) collect distinct account IDs sorted ascending, (3) `SELECT FOR UPDATE` all accounts in ascending ID order via AccountRepository, (4) validate all accounts exist (throw AccountNotFoundException with pointer for missing), (5) validate same currency, (6) validate decimal precision per entry, (7) validate balanced (sum debits == sum credits), (8) for each entry compute `balance_after` and validate overdraft (`account.canDebit(amount)` for debits; for credits balance increases), (9) update each account's balance via AccountRepository, (10) save Transaction + Entries, (11) write OutboxEvent(s) — one per distinct account in the transaction, (12) return Transaction. All in one DB transaction
- [ ] T035 [US1] Create RecordTransactionCommand and EntryCommand DTOs in `ledger-api/src/main/java/com/example/ledger/transaction/service/` — command objects used by service layer, decoupled from web DTOs
- [ ] T036 [US1] Create TransactionController in `ledger-api/src/main/java/com/example/ledger/transaction/web/TransactionController.java` — `POST /v1/transactions` (RecordTransactionRequest DTO → map to RecordTransactionCommand → TransactionService.recordTransaction → TransactionResponse DTO, 201 + Location). `GET /v1/transactions/{transactionId}` (→ TransactionResponse, 404 if not found). DTOs: `RecordTransactionRequest` (description: String optional, entries: List<EntryRequest> @Size(min=2)), `EntryRequest` (accountId: String @NotBlank, direction: String @NotNull, amount: String @NotBlank), `TransactionResponse` (id, description, currency, recordedAt, reversesTransactionId nullable, entries as List<EntryResponse>), `EntryResponse` (id, accountId, direction, amount as string, balanceAfter as string)
- [ ] T037 [US1] Write unit tests in `ledger-api/src/test/java/com/example/ledger/unit/TransactionValidatorTest.java` — test: single entry refused, unbalanced refused, decimal precision refused (JPY with 100.5, EUR with 10.123), balanced accepted, same currency accepted, mixed currency refused. Test `Money.of("0.10", EUR).add(Money.of("0.20", EUR)).equals(Money.of("0.30", EUR))` for exact arithmetic
- [ ] T038 [US1] Write unit tests in `ledger-api/src/test/java/com/example/ledger/unit/MoneyTest.java` — `0.10 + 0.20 == 0.30` with BigDecimal, scale validation per currency, negative amount refused, zero amount refused
- [ ] T039 [US1] Write integration test in `ledger-api/src/test/java/com/example/ledger/integration/TransactionIntegrationTest.java` — extends BaseIntegrationTest. Tests: record valid 2-entry tx (201, balances updated, read back matches), record 3-entry tx (payout + fee split from spec scenario 2), refused: unbalanced → 422, refused: overdraft → 422 with pointer, refused: JPY decimal → 422, refused: currency mismatch → 422, refused: single entry → 400, refused: unknown account → 422 with pointer `/entries/N/accountId`, refused tx leaves nothing (no entries, no balance change, query `SUM(balance)` per currency == 0 before and after)

**Checkpoint**: Transactions record atomically with full validation. All refusal scenarios from spec §5 pass.

---

## Phase 5: User Story 6 — Idempotent Mutating Requests (Priority: P1)

**Goal**: Verify idempotency works end-to-end for record-transaction (account idempotency already proven in Phase 3). Same key + same body → replay. Same key + different body → 409. Concurrent duplicates → exactly one effect.

**Independent Test**: Record a transaction, retry with same key → same response. Different body with same key → 409. Missing key → 400.

### Implementation for User Story 6

- [ ] T040 [US6] Write integration test in `ledger-api/src/test/java/com/example/ledger/integration/IdempotencyIntegrationTest.java` — extends BaseIntegrationTest. Tests: (1) record tx with key K, replay same key+body → 200 with identical response body and no new transaction, (2) same key + different entries → 409, (3) missing Idempotency-Key → 400, (4) concurrent duplicate: submit same key+body from 2 threads simultaneously → exactly one 201, other gets 200 replay or 409 with Retry-After — verify only one transaction exists, (5) idempotent account open + idempotent tx record in sequence with different scopes/keys — verify independence, (6) crash-halfway simulation: insert idempotency key with status IN_PROGRESS (simulating crash), retry same key+body → system detects in-flight state and handles appropriately (409 with Retry-After or completes)

**Checkpoint**: Idempotency proven across all mutating endpoints including concurrent and crash scenarios.

---

## Phase 6: User Story 7 — Correctness Under Concurrency (Priority: P1)

**Goal**: Multi-instance concurrent transactions never breach overdrafts, lose updates, or deadlock permanently.

**Independent Test**: Run N parallel transfers on overlapping accounts, assert final balances correct and SUM=0.

### Implementation for User Story 7

- [ ] T041 [US7] Write integration test in `ledger-api/src/test/java/com/example/ledger/integration/ConcurrencyIntegrationTest.java` — extends BaseIntegrationTest. Tests: (1) accounts A and B each with balance 100 (overdraft 0); 10 concurrent threads each transfer 20 from A to B; assert exactly 5 succeed, A=0, B=200, no deadlock, (2) simultaneous A→B and B→A transfers — both succeed or one retries; no permanent deadlock; no lost update; verify final balances consistent, (3) N parallel debits from same account — overdraft never breached (check balance never < -overdraftLimit), (4) after all concurrent tests: `SELECT currency, SUM(balance) FROM accounts GROUP BY currency` returns 0 for every currency, (5) deadlock retry verification: if a deadlock is detected by MySQL, the application retries and eventually succeeds (use Awaitility with timeout)
- [ ] T042 [US7] Add deadlock retry logic in `ledger-api/src/main/java/com/example/ledger/common/config/RetryConfig.java` or as a Spring `@Retryable` on `TransactionService.recordTransaction` — retry on `DeadlockLoserDataAccessException` and `CannotAcquireLockException` with bounded exponential back-off (max 3 retries, 50ms/100ms/200ms). Document in README Decisions section

**Checkpoint**: Concurrency guarantees proven under load. No overdraft breach, no lost update, no permanent deadlock.

---

## Phase 7: User Story 3 — Reverse a Transaction (Priority: P2)

**Goal**: Reverse a recorded transaction by creating a new transaction with flipped entries. Once-only reversal enforced at DB level (UNIQUE on `reverses_transaction_id`). Reversals of reversals refused.

**Independent Test**: Record tx, reverse it, verify flipped entries and balances. Second reversal → 409. Reverse a reversal → 422.

### Implementation for User Story 3

- [ ] T043 [US3] Add `reverseTransaction(String transactionId)` method to `TransactionService` in `ledger-api/src/main/java/com/example/ledger/transaction/service/TransactionService.java` — (1) load original transaction, throw TransactionNotFoundException if missing, (2) if original has `reversesTransactionId != null` → throw CannotReverseReversalException, (3) check no existing reversal (query by `reverses_transaction_id`; if found → throw AlreadyReversedException), (4) build reversed entries (flip DEBIT↔CREDIT, same amounts, same accounts), (5) call `recordTransaction` logic internally (same validation: lock accounts ascending, check overdraft on flipped entries, compute balance_after, atomic write), (6) set `reversesTransactionId` on the new transaction, (7) UNIQUE constraint on `reverses_transaction_id` provides DB-level once-only guarantee — handle duplicate key exception as AlreadyReversedException for race conditions. Idempotency via `Idempotency-Key` header as with any POST
- [ ] T044 [US3] Add reversal endpoint to TransactionController — `POST /v1/transactions/{transactionId}/reversal` (ReverseTransactionRequest optional DTO with description → TransactionService.reverseTransaction → TransactionResponse, 201 + Location). Maps to OpenAPI spec
- [ ] T045 [US3] Write integration test in `ledger-api/src/test/java/com/example/ledger/integration/ReversalIntegrationTest.java` — extends BaseIntegrationTest. Tests: (1) record tx (debit M42 100, credit Payouts 98.50, credit Fees 1.50), reverse it → 201, entries flipped, original unchanged, balances restored, (2) second reversal of same tx → 409 ALREADY_REVERSED, (3) reverse the reversal → 422 CANNOT_REVERSE_REVERSAL, (4) reversal that would breach overdraft → 422 OVERDRAFT_EXCEEDED; verify original remains reversible later (after depositing funds, reversal succeeds), (5) concurrent double reversal: 2 threads reverse same tx simultaneously → exactly one 201, one 409; only one reversal transaction exists, (6) idempotent reversal: same Idempotency-Key → replay, (7) zero-sum check after all reversals

**Checkpoint**: Reversals work correctly including race conditions and overdraft edge cases.

---

## Phase 8: User Story 5 — Announce Recorded Transactions (Priority: P2)

**Goal**: Every recorded transaction is announced via Kafka through a transactional outbox. Survives crashes. Per-account ordering.

**Independent Test**: Record a transaction, verify event appears on `ledger.transactions.recorded` topic with correct payload. Simulate crash, verify event delivered after recovery.

### Implementation for User Story 5

- [ ] T046 [US5] Create OutboxEvent domain object in `ledger-api/src/main/java/com/example/ledger/outbox/domain/OutboxEvent.java` — record with fields per data-model.md: id (Long), aggregateType, aggregateId, accountId, eventType, payload (JSON string), correlationId, status (PENDING/PUBLISHED), createdAt, publishedAt
- [ ] T047 [US5] Create OutboxRepository in `ledger-api/src/main/java/com/example/ledger/outbox/persistence/OutboxRepository.java` — JdbcTemplate impl. Methods: `save(OutboxEvent)`, `findPendingForUpdate(int limit)` → `SELECT * FROM outbox_events WHERE status = 'PENDING' ORDER BY id FOR UPDATE SKIP LOCKED LIMIT ?`, `markPublished(Long id, Instant publishedAt)`
- [ ] T048 [US5] Wire outbox writes into TransactionService — after saving transaction + entries + balance updates, create one OutboxEvent per distinct account in the transaction (aggregateType="TRANSACTION", aggregateId=tx.id, accountId=entry.accountId, eventType="TRANSACTION_RECORDED", payload=JSON of TransactionRecordedEvent per OpenAPI schema, correlationId from MDC). All in same @Transactional
- [ ] T049 [US5] Create OutboxRelay in `ledger-api/src/main/java/com/example/ledger/outbox/relay/OutboxRelay.java` — `@Scheduled(fixedDelay = 500ms)` method: (1) `findPendingForUpdate(100)`, (2) for each event: send to Kafka topic `ledger.transactions.recorded` with key = `event.accountId` (per-account ordering), (3) on successful send: `markPublished(event.id, Instant.now())`, (4) on Kafka send failure: log error, skip (will retry next poll). The relay runs on all instances — `SKIP LOCKED` prevents double-publish
- [ ] T050 [US5] Write integration test in `ledger-api/src/test/java/com/example/ledger/integration/OutboxIntegrationTest.java` — extends BaseIntegrationTest. Tests: (1) record a transaction, wait (Awaitility) for message on `ledger.transactions.recorded` topic, verify payload matches TransactionRecordedEvent schema (transactionId, entries, currency, recordedAt), (2) verify partition key = accountId, (3) record a reversal, verify announcement includes `reversesTransactionId`, (4) simulate crash: insert outbox row with status PENDING directly in DB (simulating committed tx but relay not yet run), trigger relay, verify message published, (5) duplicate handling: consumer receives same event twice (at-least-once) — verify transaction id allows deduplication, (6) refused transaction → no outbox row exists

**Checkpoint**: Outbox relay delivers events to Kafka. Crash recovery proven. Per-account ordering verified.

---

## Phase 9: User Story 4 — Consume Payment-Settled Messages (Priority: P2)

**Goal**: Consume `payment-settled` messages from Kafka, record ledger transactions (debit Settlement, credit merchant). Dedup on payment_id. Poison → DLQ.

**Independent Test**: Send payment message, verify transaction in ledger. Resend → no duplicate. Bad message → DLQ.

### Implementation for User Story 4

- [ ] T051 [US4] Create PaymentSettledMessage DTO in `ledger-api/src/main/java/com/example/ledger/payment/consumer/PaymentSettledMessage.java` — record: `eventId` (String), `paymentId` (String), `merchantAccountId` (String), `amount` (String — parsed to BigDecimal), `currency` (String)
- [ ] T052 [US4] Create SettledPaymentRepository in `ledger-api/src/main/java/com/example/ledger/payment/persistence/SettledPaymentRepository.java` — JdbcTemplate impl. Methods: `save(SettledPayment)` inserts row, `findByPaymentId(String paymentId)` returns Optional<SettledPayment>. On duplicate key (payment_id UNIQUE) → detect and compare payload
- [ ] T053 [US4] Create PaymentSettledConsumer in `ledger-api/src/main/java/com/example/ledger/payment/consumer/PaymentSettledConsumer.java` — `@KafkaListener(topics = "payments.settled")` with manual ack. Flow: (1) deserialise PaymentSettledMessage, (2) log payment_id + event_id + correlationId from message headers, (3) check `SettledPaymentRepository.findByPaymentId` — if exists and same merchant+amount+currency → ack and skip (redelivery); if exists and different payload → send to DLQ topic `payments.settled.dlq` with reason, ack original, (4) look up Settlement account for currency and merchant account, (5) call `TransactionService.recordTransaction` with 2 entries (debit Settlement, credit merchant), (6) save SettledPayment row (in same @Transactional via service), (7) ack only after DB commit. On any unrecoverable exception (unknown account, wrong currency, malformed): send to DLQ with error detail, ack original, log with payment_id + event_id. Never swallow exceptions silently. Never block partition on failure
- [ ] T054 [US4] Create DLQ producer utility in `ledger-api/src/main/java/com/example/ledger/payment/consumer/DeadLetterProducer.java` — sends failed messages to `payments.settled.dlq` topic with headers: original topic, partition, offset, error reason, payment_id, event_id, timestamp
- [ ] T055 [US4] Create `ledger-api/scripts/send-payment-settled.sh` — shell script that sends a sample `payment-settled` JSON message to the Kafka topic `payments.settled` using `kafka-console-producer` (from Docker container). Args: `paymentId merchantAccountId amount currency [eventId]`. Generates random eventId if not provided
- [ ] T056 [US4] Write integration test in `ledger-api/src/test/java/com/example/ledger/integration/PaymentConsumerIntegrationTest.java` — extends BaseIntegrationTest. Tests: (1) send valid payment-settled message → transaction recorded (debit Settlement EUR, credit merchant), balances correct, (2) JPY payment → Settlement JPY debited, amounts have 0 decimals, (3) redeliver same payment (same payment_id, new event_id) → no duplicate transaction (count stays 1), (4) same payment_id with different amount → message sent to DLQ, original acked, no new transaction, (5) unknown merchant account → DLQ with reason logged, not blocking, next message processed, (6) malformed message → DLQ, not blocking, (7) verify settled_payments row created with payment_id, event_id, transaction_id, (8) zero-sum check after all payment tests

**Checkpoint**: Payment pipeline works end-to-end. Dedup, DLQ, and non-blocking error handling proven.

---

## Phase 10: User Story 8 — Read Account and Entries (Priority: P3)

**Goal**: Browse an account's entries newest-first with cursor-based pagination, each with balance_after.

**Independent Test**: Record several transactions, paginate through entries, verify order and balance_after values.

### Implementation for User Story 8

- [ ] T057 [US8] Create EntryRepository query in `ledger-api/src/main/java/com/example/ledger/transaction/persistence/EntryRepository.java` (or add to existing) — `findByAccountIdPaginated(String accountId, String cursor, int limit)`: query `SELECT * FROM entries WHERE account_id = ? AND (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC LIMIT ?` (cursor decodes to created_at + id pair). Returns list of Entry + boolean hasMore. If cursor is null → omit the WHERE clause for cursor
- [ ] T058 [US8] Add `listEntries(String accountId, String cursor, int limit)` to AccountController or create EntryController in `ledger-api/src/main/java/com/example/ledger/account/web/AccountController.java` — `GET /v1/accounts/{accountId}/entries?cursor=X&limit=20` → EntryPage DTO (entries: List<AccountEntryResponse>, nextCursor: String nullable, hasMore: boolean). AccountEntryResponse: id, transactionId, direction, amount (string), balanceAfter (string), createdAt. 404 if account not found
- [ ] T059 [US8] Write integration test in `ledger-api/src/test/java/com/example/ledger/integration/EntryPaginationIntegrationTest.java` — extends BaseIntegrationTest. Tests: (1) record 5 transactions on same account, request entries with limit=2 → 2 entries returned newest first, nextCursor present, hasMore=true, (2) follow cursor → next 2 entries, (3) follow again → 1 entry, hasMore=false, nextCursor null, (4) each entry has correct balanceAfter, (5) empty account → empty list, (6) account not found → 404

**Checkpoint**: Entry browsing with pagination works correctly.

---

## Phase 11: Polish & Cross-Cutting Concerns

**Purpose**: Docker integration, documentation, final validation

- [ ] T060 [P] Create `ledger-api/README.md` — sections: How to Run (`./run.sh`), Sample Requests (curl examples from quickstart.md), How to Send Sample Payment-Settled Message (`./scripts/send-payment-settled.sh`), Design Decisions (document: isolation level REPEATABLE READ and why, locking strategy ascending account-id, idempotency key design with status/response, outbox relay with SKIP LOCKED, payment dedup on payment_id not event_id, UUIDv7 for IDs, per-account announcement ordering, overdraft_limit NULL = unlimited, same-account multi-entry allowed, key retention indefinite for MVP), Consumer Guarantees (at-least-once delivery, duplicates possible, per-account ordering, consumers dedupe by transaction id, crash-after-record → outbox row survives → published after restart), Improvements (before production: idempotency key TTL/cleanup, Debezium CDC as outbox alternative, rate limiting, authentication/authorization, observability dashboards, horizontal scaling considerations, balance-as-of-past-instant query)
- [ ] T061 [P] Create `ledger-api/AI_USAGE.md` — document: AI used (Claude via Spec Kit workflow), what for (constitution, specification, clarification, planning, task generation, implementation), how output was verified (integration tests with Testcontainers, manual review, contract tests), commit history includes CLAUDE.md and Spec Kit artifacts
- [ ] T062 [P] Write contract test in `ledger-api/src/test/java/com/example/ledger/contract/OpenApiContractTest.java` — loads `api/openapi.yaml`, validates that controller endpoints match the contract (paths, methods, request/response schemas). Use a library like `swagger-request-validator-spring-webmvc` or manual schema comparison. Fails if contract drifts from implementation
- [ ] T063 Finalize `ledger-api/run.sh` — ensure it: (1) runs `./mvnw clean package -DskipTests`, (2) builds Docker image, (3) starts docker-compose (MySQL, Kafka, ledger-1, ledger-2), (4) waits for all health endpoints, (5) runs `./mvnw verify` (integration tests against Testcontainers), (6) optionally runs smoke tests against docker-compose instances, (7) prints summary, (8) tears down on exit/error. Make executable (`chmod +x`)
- [ ] T064 Final validation: run `./run.sh` end-to-end, verify all tests pass, verify zero-sum SQL query (`SELECT currency, SUM(balance) FROM accounts GROUP BY currency`) returns 0 for all currencies, verify quickstart.md scenarios work

**Checkpoint**: All deliverables complete. `./run.sh` passes. README documents all decisions.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies — start immediately
- **Phase 2 (Foundational)**: Depends on Phase 1 — BLOCKS all user stories
- **Phases 3-10 (User Stories)**: All depend on Phase 2 completion
  - **Phase 3 (US2 Open Account)**: Can start after Phase 2. BLOCKS Phases 4, 7, 8, 9
  - **Phase 4 (US1 Record Transaction)**: Depends on Phase 3 (accounts must exist)
  - **Phase 5 (US6 Idempotency)**: Depends on Phase 4 (tests need transaction recording)
  - **Phase 6 (US7 Concurrency)**: Depends on Phase 4 (tests need transaction recording)
  - **Phase 7 (US3 Reversal)**: Depends on Phase 4 (needs recorded transactions to reverse)
  - **Phase 8 (US5 Announcements)**: Depends on Phase 4 (outbox written during recording)
  - **Phase 9 (US4 Payments)**: Depends on Phase 4 + Phase 8 (needs recording + announcements)
  - **Phase 10 (US8 Entries)**: Depends on Phase 4 (needs entries to paginate)
- **Phase 11 (Polish)**: Depends on all user stories complete

### User Story Dependencies

```
Phase 2 (Foundation)
  └─→ Phase 3 (US2: Open Account) ← P1, must be first
        └─→ Phase 4 (US1: Record Transaction) ← P1, core
              ├─→ Phase 5 (US6: Idempotency tests) ← P1, can parallel with 6,7,8
              ├─→ Phase 6 (US7: Concurrency tests) ← P1, can parallel with 5,7,8
              ├─→ Phase 7 (US3: Reversal) ← P2, can parallel with 5,6,8
              ├─→ Phase 8 (US5: Announcements) ← P2, can parallel with 5,6,7
              │     └─→ Phase 9 (US4: Payments) ← P2, needs outbox
              └─→ Phase 10 (US8: Entries) ← P3, can parallel with 5,6,7,8
```

### Parallel Opportunities

After Phase 4 (Record Transaction) completes, Phases 5, 6, 7, 8, and 10 can all proceed in parallel:

```
                    ┌── Phase 5 (US6: Idempotency)
                    ├── Phase 6 (US7: Concurrency)
Phase 4 ──────────►├── Phase 7 (US3: Reversal)
                    ├── Phase 8 (US5: Announcements) ──► Phase 9 (US4: Payments)
                    └── Phase 10 (US8: Entries)
```

---

## Implementation Strategy

### MVP First (P1 User Stories)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL — blocks all stories)
3. Complete Phase 3: US2 Open Account
4. Complete Phase 4: US1 Record Transaction
5. Complete Phase 5: US6 Idempotency (verify)
6. Complete Phase 6: US7 Concurrency (verify)
7. **STOP and VALIDATE**: Core ledger with idempotency and concurrency safety proven

### Incremental Delivery

8. Add Phase 7: US3 Reversal → test independently → commit
9. Add Phase 8: US5 Announcements → test independently → commit
10. Add Phase 9: US4 Payments → test independently → commit
11. Add Phase 10: US8 Entries → test independently → commit
12. Complete Phase 11: Polish → final validation

### Commit Strategy

Commit after each completed task or logical group. Each phase completion is a meaningful commit point. History is evaluated — keep commits small, focused, and well-messaged.

---

## Notes

- [P] tasks = different files, no dependencies on incomplete tasks
- [Story] label maps each task to its user story for traceability
- All amounts in code, DB, and JSON are BigDecimal/DECIMAL/string — never double/float
- Transactions and entries are insert-only — no UPDATE/DELETE
- All mutating endpoints require Idempotency-Key header
- Integration tests use Testcontainers (real MySQL + Kafka) — never mock for guarantee tests
- `@Transactional` only on public service methods; never private or self-invoked
- Lock accounts in ascending ID order to prevent deadlocks
