# Feature Specification: Double-Entry Ledger

**Feature Branch**: `001-double-entry-ledger`

**Created**: 2026-11-03

**Status**: Draft

**Input**: User description: "Double-entry ledger service for a payments platform — accounts, transactions, reversals, payment-settled consumption, transaction announcements, correctness under concurrency and retries."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Record a Transaction (Priority: P1)

A client records a multi-entry transaction moving money between accounts. The ledger validates that entries balance, all accounts exist in the same currency, amounts respect decimal precision, and no account breaches its overdraft limit. On success the transaction is persisted atomically and an announcement is emitted. On failure the caller receives a clear explanation of what is wrong and which part of the request caused it.

**Why this priority**: Recording transactions is the core ledger operation. Without it no other feature (reversals, payments, announcements) can function.

**Independent Test**: Submit a valid multi-entry transaction via the API, then read it back. Verify balances updated and an announcement was produced.

**Acceptance Scenarios**:

1. **Given** Merchant 42 has balance 125.50 EUR and Payouts EUR has balance 0, **When** a transaction debits Merchant 42 by 100.00 and credits Payouts EUR 98.50 and Fees EUR 1.50, **Then** the transaction is recorded; Merchant 42 balance is 25.50; Payouts 98.50; Fees 1.50; EUR balances sum to zero.
2. **Given** Merchant 42 has balance 25.50 EUR (overdraft limit 0), **When** a transaction debits Merchant 42 by 50.00 and credits Payouts 50.00, **Then** the transaction is refused because Merchant 42 would breach its overdraft limit; no entries, balance changes, or announcements are recorded.
3. **Given** two accounts in EUR, **When** debit 100.00 and credit 99.00, **Then** the transaction is refused as unbalanced.
4. **Given** a JPY account, **When** an entry has amount 100.5 (fractional yen), **Then** the transaction is refused because JPY allows 0 decimal places.
5. **Given** an EUR account and a JPY account, **When** a transaction mixes entries across both currencies, **Then** it is refused for currency mismatch.
6. **Given** a single entry crediting an account 10.00, **When** the transaction is submitted, **Then** it is refused because a transaction requires at least 2 entries.
7. **Given** an entry references a non-existent account, **When** submitted, **Then** the transaction is refused identifying the unknown account.

---

### User Story 2 - Open an Account (Priority: P1)

A client opens a new account specifying a currency and an optional overdraft limit. The account starts at balance zero. The operation is idempotent — opening the same account twice produces the same result. Invalid or unsupported currencies are rejected with distinct, explainable errors.

**Why this priority**: Accounts must exist before any transaction can reference them. This is a prerequisite for P1 transaction recording.

**Independent Test**: Open an account, read it back, verify balance is zero and currency matches. Repeat the open request and confirm idempotent behaviour.

**Acceptance Scenarios**:

1. **Given** no account exists for Merchant 42, **When** the client opens an EUR account with overdraft limit 0, **Then** the account is created with balance 0 and currency EUR.
2. **Given** Merchant 42 already has an EUR account, **When** the client sends the same open request, **Then** the response is identical to the first — idempotent.
3. **Given** a currency code `EURO`, **When** the client tries to open an account, **Then** it is refused as "not a valid ISO 4217 code."
4. **Given** currency code `GBP`, **When** the client tries to open an account, **Then** it is refused as "valid ISO 4217 code but not supported" — distinct from the invalid-code error.

---

### User Story 3 - Reverse a Transaction (Priority: P2)

A client reverses a previously recorded transaction. A new transaction is created with every entry's direction flipped, linked to the original. The original transaction is never modified. A transaction may be reversed at most once; reversing a reversal is refused.

**Why this priority**: Corrections are essential for operational use but depend on the core recording mechanism.

**Independent Test**: Record a transaction, reverse it, verify the reversal transaction exists with flipped entries. Attempt a second reversal and confirm refusal.

**Acceptance Scenarios**:

1. **Given** transaction T was recorded (debit Merchant 42 100.00, credit Payouts 98.50, credit Fees 1.50), **When** a reversal is requested for T, **Then** a new transaction is recorded with entries flipped (credit Merchant 42 100.00, debit Payouts 98.50, debit Fees 1.50); original T is unchanged; linked to T.
2. **Given** transaction T has already been reversed, **When** a second reversal of T is requested, **Then** it is refused.
3. **Given** transaction R is itself a reversal, **When** a reversal of R is requested, **Then** it is refused — reversals cannot be reversed.
4. **Given** a reversal would breach an account's overdraft limit, **When** the reversal is requested, **Then** it is refused with overdraft explanation. The original transaction remains reversible at a later time when conditions allow it.

---

### User Story 4 - Consume Payment-Settled Messages (Priority: P2)

The payment system sends `payment-settled` messages via a broker. Each message contains an event id, payment id, merchant account id, amount, and currency. The ledger records a transaction debiting the Settlement account of that currency and crediting the merchant account.

**Why this priority**: Integrating the payment pipeline is a core business requirement, but depends on accounts and transaction recording being functional.

**Independent Test**: Send a sample payment-settled message, verify the transaction appears in the ledger with correct entries and balances. Resend the same message and confirm no duplicate recording.

**Acceptance Scenarios**:

1. **Given** Settlement EUR and Merchant 42 (EUR) exist, **When** a payment-settled message arrives (payment P1, 125.50 EUR, Merchant 42), **Then** a transaction is recorded: debit Settlement EUR 125.50, credit Merchant 42 125.50.
2. **Given** JPY accounts exist, **When** a payment-settled message arrives for 15000 JPY to Merchant 7, **Then** debit Settlement JPY 15000, credit Merchant 7 15000.
3. **Given** payment P1 has already been recorded, **When** the same message arrives again (even under a new event id), **Then** no duplicate transaction is recorded.
4. **Given** a payment message references an unknown merchant account, **When** the message arrives, **Then** it is not recorded; it is sent to a dead-letter destination; the failure reason is logged; subsequent messages continue processing without delay.
5. **Given** a payment message has a malformed payload, **When** the message arrives, **Then** it is dead-lettered with a logged reason; it does not block the queue.

---

### User Story 5 - Announce Recorded Transactions (Priority: P2)

Every recorded transaction (including reversals) is announced to other services with its full details. The announcement mechanism must survive service crashes — if the service crashes immediately after recording a transaction, the announcement is still eventually delivered.

**Why this priority**: Downstream services depend on these events. Reliable delivery is a core requirement but is an outbound concern layered on top of recording.

**Independent Test**: Record a transaction, verify an announcement is delivered with correct transaction details. Simulate a crash between recording and announcement delivery; verify the announcement is eventually delivered after recovery.

**Acceptance Scenarios**:

1. **Given** a transaction is successfully recorded, **When** the recording completes, **Then** an announcement containing the transaction details is eventually delivered to consumers.
2. **Given** a reversal transaction is recorded, **When** the recording completes, **Then** an announcement is delivered for the reversal, including its link to the original.
3. **Given** the service crashes immediately after a transaction is committed to the database, **When** the service restarts, **Then** the pending announcement is delivered — no recorded transaction is ever silently lost.
4. **Given** a consumer receives the same announcement twice (at-least-once delivery), **Then** the consumer can deduplicate using the transaction identifier.
5. **Given** two transactions on the same account are recorded in sequence, **When** announcements are delivered, **Then** they arrive in the same order for that account. No ordering guarantee exists across different accounts.

---

### User Story 6 - Idempotent Mutating Requests (Priority: P1)

All mutating client requests (open account, record transaction, reverse transaction) are idempotent. A retry of the same request — even from a different service instance or after a crash — returns the same result. A different request reusing the same idempotency key is detected and rejected.

**Why this priority**: Without idempotency, client retries during network failures double-record transactions. This is fundamental to ledger correctness and ranks alongside core operations.

**Independent Test**: Submit a record-transaction request, note the response. Resubmit the identical request and verify the same response is returned without a new transaction being created. Submit a different body with the same key and verify rejection.

**Acceptance Scenarios**:

1. **Given** a transaction was recorded with idempotency key K, **When** the same request (same key, same body) is retried, **Then** the stored response is replayed — same status, same body, no new transaction.
2. **Given** idempotency key K was used for request A, **When** a different request B is submitted with the same key K, **Then** it is rejected with 409 Conflict — never silently processed.
3. **Given** two identical requests with the same key arrive simultaneously on different instances, **When** both attempt to process, **Then** exactly one succeeds and the other either waits and returns the stored result or returns a conflict with a retry hint.
4. **Given** a mutating request is submitted without an `Idempotency-Key` header, **Then** it is rejected with a 400 error before any processing occurs.
5. **Given** a request was recorded with idempotency key K and the service crashed mid-processing, **When** the service recovers and the same request is retried with key K, **Then** the system detects the in-flight state and either completes the operation or replays the stored response.

---

### User Story 7 - Correctness Under Concurrency (Priority: P1)

Multiple service instances handle requests simultaneously against the same database. Concurrent transactions on overlapping accounts never violate overdraft limits, never lose balance updates, and never deadlock permanently — including the classic A-debits-B / B-debits-A scenario.

**Why this priority**: A ledger that loses money or allows overdrafts under load is fundamentally broken. Concurrency correctness is a non-negotiable integrity guarantee.

**Independent Test**: Run parallel transactions on overlapping accounts. Verify final balances match the expected sequential result, no overdraft is breached, and no permanent deadlocks occur.

**Acceptance Scenarios**:

1. **Given** accounts A and B each with balance 100 and overdraft 0, **When** 10 concurrent requests each transfer 20 from A to B, **Then** exactly 5 succeed (A reaches 0), 5 are refused for overdraft, and final balances are A=0, B=200.
2. **Given** accounts A and B, **When** a transfer A→B and a transfer B→A are submitted simultaneously, **Then** both succeed or one is retried after a transient lock conflict — no permanent deadlock, no lost update.
3. **Given** any number of concurrent transactions complete, **When** the sum of all account balances per currency is computed, **Then** it equals zero.

---

### User Story 8 - Read Account and Entries (Priority: P3)

A client reads an account's current balance and browses its entries in reverse chronological order with pagination. Each entry shows the balance after that entry was applied.

**Why this priority**: Read operations are important for usability but do not affect ledger correctness guarantees.

**Independent Test**: Record several transactions, then read the account balance and paginate through entries. Verify ordering and balance-after values.

**Acceptance Scenarios**:

1. **Given** an account with recorded transactions, **When** the client reads the account, **Then** the response includes the current balance and currency.
2. **Given** an account with many entries, **When** the client requests entries with pagination, **Then** entries are returned newest-first with a cursor for the next page.
3. **Given** an entry in the list, **Then** it includes the balance of the account immediately after that entry was applied.

---

### Edge Cases

- What happens when a transaction has entries referencing the same account multiple times (e.g., both a debit and a credit on the same account)? Assumed: allowed if the overall transaction balances and the net effect does not breach the overdraft limit.
- What happens when the broker is temporarily unavailable? Announcements queue in the outbox and are delivered when connectivity resumes; no transaction recording is blocked.
- What happens when a payment-settled message arrives with the same payment id but a different amount/merchant/currency than a previously recorded payment? It is dead-lettered as a conflict with both payloads logged for investigation — never silently reprocessed or ignored.
- What happens when the idempotency key storage becomes unavailable? Mutating requests fail rather than risk double-processing.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST allow clients to open accounts with a specified currency and optional overdraft limit. Accounts start at balance zero. The operation MUST be idempotent.
- **FR-002**: System MUST reject account creation with a currency code that is not valid ISO 4217 (e.g., `EURO`, `ABC`) with a "not valid ISO 4217" error.
- **FR-003**: System MUST reject account creation with a valid ISO 4217 code that is not supported (e.g., `GBP`) with a distinct "valid but unsupported" error.
- **FR-004**: System MUST allow clients to read an account and its current balance.
- **FR-005**: System MUST allow clients to record a transaction with two or more entries. Every entry specifies an account, direction (debit or credit), and a positive amount.
- **FR-006**: System MUST validate that all entries in a transaction reference accounts in the same currency.
- **FR-007**: System MUST validate that the total debits equal total credits in a transaction.
- **FR-008**: System MUST validate that entry amounts do not exceed the decimal precision of the account's currency (EUR/USD: 2 decimals, JPY: 0 decimals).
- **FR-009**: System MUST validate that no account's balance would breach its overdraft limit as a result of the transaction.
- **FR-010**: System MUST record a transaction atomically — all entries and balance updates succeed together, or nothing is persisted.
- **FR-011**: On refusal, the system MUST return an error identifying the specific reason and, where applicable, a JSON Pointer to the part of the request that caused the failure (e.g., `/entries/1/accountId`). An entry referencing a non-existent account MUST return 422 Unprocessable Entity, not 404.
- **FR-012**: System MUST allow clients to read back a recorded transaction with all its entries.
- **FR-013**: Transactions and entries MUST be immutable once recorded — never updated or deleted.
- **FR-014**: System MUST allow clients to reverse a recorded transaction by creating a new transaction with every entry's direction flipped, linked to the original.
- **FR-015**: A transaction MUST be reversible at most once. A second reversal attempt MUST be refused.
- **FR-016**: A reversal transaction MUST NOT be reversible. Attempting to reverse a reversal MUST be refused.
- **FR-017**: Reversals are subject to the same validation rules as regular transactions, including overdraft limits.
- **FR-018**: The original transaction MUST remain untouched after reversal. If a reversal is refused due to overdraft, the original remains reversible at a later time.
- **FR-019**: System MUST consume `payment-settled` messages containing event id, payment id, merchant account id, amount, and currency.
- **FR-020**: Each valid payment-settled message MUST result in a transaction debiting the Settlement account of that currency and crediting the merchant account.
- **FR-021**: Messages that cannot be recorded (malformed, unknown account, wrong currency) MUST be dead-lettered with the failure reason logged. They MUST NOT block subsequent messages.
- **FR-022**: A sample mechanism (script, endpoint, or CLI) MUST exist to send payment-settled messages for testing.
- **FR-023**: Every recorded transaction (including reversals) MUST be announced to other services with its full details. Announcements MUST be ordered per account (partitioned by account id); global ordering across accounts is not guaranteed.
- **FR-024**: Announcements MUST survive service crashes — a transaction committed to the database MUST eventually be announced even if the service dies immediately after recording.
- **FR-025**: All mutating client requests MUST include an `Idempotency-Key` header. Requests missing the header MUST be rejected with a 400 error. A retry of the same request (same key, same body) MUST return the stored response. A different request with the same key MUST be rejected with 409 Conflict.
- **FR-025a**: The idempotency record MUST include a status/state (to distinguish in-flight from completed) and a stored response (containing the resource identifier and sufficient data to replay the original response and resume processing after a crash).
- **FR-026**: Concurrent duplicate requests (same idempotency key) MUST result in exactly one effect; the other request waits or receives a conflict response with a retry hint.
- **FR-027**: Payment deduplication MUST be based on payment id (UNIQUE at DB level), not event id. The same payment arriving under a new event id MUST NOT be recorded twice. If a message arrives with the same payment id but different amount, merchant, or currency, it MUST be dead-lettered as a conflict with both payloads logged for investigation.
- **FR-028**: Concurrent transactions on overlapping accounts MUST NOT violate overdraft limits, lose balance updates, or deadlock permanently.
- **FR-029**: At any moment, for each supported currency, the sum of all account balances MUST equal zero.
- **FR-030**: Pre-seeded Settlement accounts (at minimum Settlement EUR and Settlement JPY) MUST exist at system startup with unlimited overdraft allowance (no overdraft limit). Settlement USD is also seeded.
- **FR-031**: Money MUST be exact — 0.10 + 0.20 MUST equal 0.30 at all times.
- **FR-032**: System MUST allow clients to browse an account's entries in reverse chronological order with cursor-based pagination, each entry showing the balance after it was applied.

### Key Entities

- **Account**: Represents a financial account. Key attributes: unique identifier, currency (ISO 4217 from the supported set), overdraft limit (default 0; null/unlimited for Settlement accounts), current balance. Starts at zero.
- **Transaction**: An immutable record of money movement. Key attributes: unique identifier, description, recorded-at timestamp, list of entries (at least 2), optional reference to a reversed transaction (for reversals).
- **Entry**: A single leg of a transaction. Key attributes: account reference, direction (debit or credit), positive amount (respecting currency decimal precision), balance of the account after this entry was applied.
- **Settlement Account**: A special pre-seeded account (one per supported currency) with unlimited overdraft. Used as the counterparty for payment-settled transactions.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The sum of all account balances per currency equals zero after every transaction, with zero exceptions — verified by an automated check.
- **SC-002**: Under 50 concurrent transfer requests on overlapping accounts, no overdraft limit is breached and no balance update is lost.
- **SC-003**: A retried request (same idempotency key, same body) returns the identical response as the original — verified across multiple service instances.
- **SC-004**: A redelivered payment-settled message (same payment id, new event id) results in exactly one recorded transaction.
- **SC-005**: A poison payment message is dead-lettered within one retry cycle, and the next valid message is processed without delay.
- **SC-006**: After a simulated crash immediately following a transaction commit, the announcement is delivered upon service recovery with zero recorded transactions missing announcements.
- **SC-007**: All refused transactions leave zero side effects — no entries, no balance changes, no announcements, no successful idempotency records.
- **SC-008**: Simultaneous A→B and B→A transfers complete without permanent deadlock.
- **SC-009**: Every unspecified design decision is documented, verifiable by reviewing the documentation.
- **SC-010**: The system builds, starts, and passes all tests from a single script with only a container runtime, Git, and a JDK installed on the host.

## Clarifications

### Session 2026-11-03

- Q: Is the `Idempotency-Key` header required on every mutating request, and what happens when it is missing? → A: Required on all POST requests; 400 error if missing. The idempotency record MUST store a status/state column (to track in-flight vs completed) and a response column (containing the resource identifier and enough data to replay the response and resume processing after a crash).
- Q: What error status should the system return when an idempotency key is reused with a different request body? → A: 409 Conflict. The key is already bound to a different request; this is a resource conflict, not a validation error.
- Q: What error status should the system return when a transaction entry references a non-existent account? → A: 422 Unprocessable Entity with a JSON Pointer to the faulty entry (e.g., `/entries/1/accountId`). The request target exists; the error is an invalid embedded reference.
- Q: When a payment-settled message arrives with the same payment id but a different amount, merchant, or currency than the previously recorded payment, how should the system handle it? → A: Dead-letter as conflict; log the mismatch with both payloads for investigation. Payment deduplication is on payment id (UNIQUE at DB level), distinct from event id (broker delivery identifier) and Idempotency-Key (HTTP-layer, REST API only).
- Q: What ordering guarantee does the system provide for transaction announcements? → A: Per-account ordering. Announcements are partitioned by account id; order is guaranteed within an account only. Global ordering is not provided.

## Assumptions

- Settlement accounts for EUR, JPY, and USD are pre-seeded at system startup and do not need to be created by clients.
- Overdraft limit is set only at account creation and cannot be modified afterward.
- Entries of the same account appearing multiple times within one transaction are allowed, provided the transaction balances and the net effect does not breach the overdraft limit.
- A payment-settled message with the same payment id but different amount/merchant/currency than a previously recorded payment is treated as a conflict (dead-lettered and logged), not as a valid redelivery.
- Idempotency keys are retained for at least 24 hours; the exact retention period is a design decision to be documented.
- The reversal of a transaction that was previously refused due to overdraft may be reattempted later — the original transaction remains reversible.
- Announcement consumers may see duplicates (at-least-once delivery) and are responsible for deduplication using the transaction identifier.
- The system runs as multiple instances sharing one database; correctness relies exclusively on database-level guarantees.
