# requirements.md — Ledger API (Backend Code Challenge)

Input for `/speckit.specify`. Behavioural requirements only; technology choices are in `CLAUDE.md`.

## 1. Purpose
A small double-entry ledger as used by a payments platform: records where money is and how it moved. Money lives in accounts and moves only through transactions. Transactions are immutable; mistakes are corrected by a reversal (a new transaction). Clients retry, messages are redelivered, requests race, and several service instances run, yet every balance must remain exact.

## 2. Actors & interfaces
- **Clients** — REST API: open accounts, record/reverse transactions, read balances/transactions.
- **Payment system** — sends `payment-settled` messages via a broker.
- **Other services** — receive an announcement for every recorded transaction.
- Storage: MySQL. The API design (resources, URLs, shapes, headers, errors, versioning) and broker are free choices; document them.

## 3. Domain model
**Account**: identifier; currency (ISO 4217; supported: EUR=2, USD=2, JPY=0 decimals); overdraft limit (how far below zero the balance may go; default 0; set only at opening); balance (credit increases, debit decreases).

**Transaction**: identifier; description; ≥2 entries; recorded-at timestamp; for a reversal, reference to the reversed transaction.

**Entry**: account; direction (debit|credit); amount (positive, ≤ the account currency's decimal places).

Initial state: all accounts start at 0. Ledger-owned `Settlement EUR`, `Settlement JPY` (and by extension Settlement USD is optional) have **no overdraft limit** (unlimited) and always exist. All others default to overdraft 0.

## 4. Functional requirements
### FR-1 Accounts
- FR-1.1 Open an account (currency, optional overdraft limit). Idempotent.
- FR-1.2 Read an account with its current balance.
- FR-1.3 Reject: not an ISO 4217 code (`EURO`, `ABC`) and valid-but-unsupported code (`GBP`) — as distinct, explainable errors.

### FR-2 Record a transaction
Recorded only if ALL hold:
- ≥2 entries; every amount positive and within currency decimals.
- All accounts exist and share the same currency.
- Total debits == total credits.
- No account would go beyond its overdraft limit.
Plus:
- All-or-nothing (every entry or none).
- On refusal the caller can tell why and which part of the request is at fault.
- A recorded transaction can be read back.
- Transactions and entries are never updated or deleted.

### FR-3 Reverse a transaction
- Records a new transaction with every entry flipped, linked to the original.
- A transaction can be reversed only once; a reversal cannot be reversed.
- Same rules as any transaction, overdraft limits included.
- The original remains untouched.

### FR-4 Record settled payments (inbound messages)
- Message fields: event id, payment id, merchant account id, amount, currency.
- Each message → one transaction: debit Settlement account of that currency, credit merchant account.
- A message that cannot be recorded (malformed, unknown account, wrong currency) must NOT block following messages and must NOT be lost without trace (dead-letter + log with reason).
- Provide a way to send sample payment-settled messages (script/endpoint/CLI).

### FR-5 Announce recorded transactions (outbound)
- Every recorded transaction, reversals included, is announced with its details.
- README must state consumer guarantees: can it miss one? see one twice? what ordering? what if the service crashes right after recording?

### FR-6 Correctness under pressure (multi-instance)
- **Concurrency**: simultaneous transactions on the same accounts never break an overdraft limit, never lose an update, never deadlock, including A→B and B→A simultaneously.
- **Idempotency**: clients may safely retry any mutating request (open account, record, reverse). Takes effect at most once; retry returns the same answer as the first attempt would, even on another instance or after the first attempt crashed halfway. A different request must never be mistaken for a retry.
- **Redelivery**: the same payment is recorded once however many times it arrives, including re-sent under a **new event id** (dedupe key = payment id).
- **Integrity**: at any moment, balances of all accounts in one currency sum to zero.

## 5. Validation examples (acceptance scenarios)
All start at zero; Settlement accounts unlimited; others overdraft 0.

| # | Scenario | Expected |
|---|----------|----------|
| 1 | Payment 125.50 EUR settles for Merchant 42 | Settlement EUR debit 125.50 (bal −125.50); Merchant 42 credit 125.50 (bal 125.50) |
| 2 | Payout 98.50 + fee 1.50: Merchant 42 debit 100.00; Payouts EUR credit 98.50; Fees EUR credit 1.50 | Merchant 42 = 25.50; Payouts 98.50; Fees 1.50; EUR sum = 0 |
| 3 | Reverse tx 2 | New tx, flipped entries, linked; Merchant 42 = 125.50, Payouts 0, Fees 0; tx 2 unchanged; second reversal refused |
| 4 | ¥15,000 settles for Merchant 7 | Settlement JPY −15000; Merchant 7 15000 |
| 5a | Debit M42 100.00, credit Payouts 99.00 | Refused: unbalanced |
| 5b | Debit M42 50.00 / credit Payouts 50.00 (from state after #2) | Refused: M42 would be −24.50 beyond limit 0 |
| 5c | Debit M7 ¥100.5 / credit Payouts JPY ¥100.5 | Refused: JPY has no decimals |
| 5d | Debit M42 10.00 EUR / credit M7 ¥10 | Refused: currency mismatch |
| 5e | Single entry crediting M42 10.00 | Refused: needs ≥2 entries |
| 5f | Entry to nonexistent account | Refused: unknown account |
| 5g | Open account `EURO` or `ABC` | Refused: not ISO 4217 |
| 5h | Open account `GBP` | Refused: valid code, unsupported |
Refused requests record nothing (no entries, balance change, announcement).

## 6. Non-functional requirements
- Java 17, Spring Boot 4, MySQL, REST, broker of choice.
- Everything runs in Docker. One script builds, starts everything, runs tests. Only Docker, Git and a JDK required on host.
- Money is exact; never approximated (0.10 + 0.20 == 0.30).
- Code structured clearly; design explained in README.

## 7. Nice-to-have (after must-haves are green)
- Tests proving §4 FR-6 guarantees and "refused leaves nothing", against real MySQL and broker (Testcontainers).
- API-first: OpenAPI written before code and kept in sync.
- Account entries, newest first, paged, each with balance-after.
- Balance of an account as at a past instant.
- Traceability of one request/payment through logs and announcements (correlation id).

## 8. Deliverables
Public Git repo with commit history containing:
- Code and tests.
- `README.md`: how to run the script; sample requests; how to send a sample payment-settled message; design decisions (API, broker, announcement); consumer guarantees; final **Improvements** section (before production / skipped and why).
- `AI_USAGE.md` (or README section): whether AI was used, for what, how output was checked; commit instruction files (`CLAUDE.md`, Spec Kit artifacts).

## 9. Evaluation criteria
Ledger integrity (balanced, atomic, immutable) · Idempotency and correctness under concurrency/retries/redelivery · Event-driven design (reliable consume and announce) · API design (resources, errors, retries, evolution) · Domain model and code clarity · Tests proving guarantees · Quality and clarity of decisions.

## 10. Open questions to resolve in `/speckit.clarify` (propose defaults)
1. Is `Idempotency-Key` required on all POSTs? Default: required; 400 if missing.
2. Error status for an entry with unknown account: 404 vs 422? Default: 422 with JSON-pointer to the entry.
3. Same idempotency key with a different body: 422 (default) vs 409.
4. Can entries of one account appear multiple times in one transaction (e.g., debit and credit same account)? Default: allowed if balanced, net effect applied once; or reject — decide.
5. Settlement USD account: create too? Default: yes, seeded by migration.
6. Is the overdraft limit applied to Settlement accounts as "unlimited" (null) — representation? Default: nullable column = unlimited.
7. Payment message with same payment id but different payload? Default: treated as redelivery if same merchant/amount/currency; otherwise DLQ as conflict.
8. Reversal when it would breach overdraft: refuse with 422 (per spec); does the original remain reversible later? Default: yes.
9. Broker: Kafka vs RabbitMQ; ordering guarantee scope (global vs per account)? Default: Kafka, per-account ordering via key + monotonic ledger sequence.
10. Account/transaction id format. Default: UUIDv7/ULID strings.
11. Balance-as-of past instant: computed from the last entry's `balance_after` with `recorded_at <= t`.
12. Idempotency key retention/TTL. Default: 24h+ (document).
