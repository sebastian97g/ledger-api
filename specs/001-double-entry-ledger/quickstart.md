# Quickstart Validation Guide: Double-Entry Ledger

**Date**: 2026-11-03 | **Plan**: [plan.md](plan.md)

## Prerequisites

- Docker and Docker Compose
- Git
- JDK 17+
- No other dependencies required

## Build & Start

```bash
# Clone and enter the repo
git clone <repo-url> && cd ledger-api

# Build and start everything (MySQL, Kafka, 2 ledger instances)
./run.sh
```

`run.sh` performs: Maven build → Docker image build → `docker-compose up` (MySQL, Kafka, ledger-1, ledger-2) → wait for health → run tests.

## Validation Scenarios

All scenarios use the REST API at `http://localhost:8080/v1` (or the configured port). Every POST requires an `Idempotency-Key` header.

### V1: Open an Account and Read It

```bash
# Open an EUR account
curl -X POST http://localhost:8080/v1/accounts \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: open-merchant42" \
  -d '{"currency": "EUR", "overdraftLimit": "0"}'
# Expected: 201 Created, Location header, balance "0"

# Read it back
curl http://localhost:8080/v1/accounts/{accountId}
# Expected: 200, balance "0", currency "EUR"
```

### V2: Reject Invalid and Unsupported Currencies

```bash
# Not valid ISO 4217
curl -X POST http://localhost:8080/v1/accounts \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: open-invalid" \
  -d '{"currency": "EURO"}'
# Expected: 400, code "INVALID_CURRENCY_CODE"

# Valid ISO but unsupported
curl -X POST http://localhost:8080/v1/accounts \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: open-gbp" \
  -d '{"currency": "GBP"}'
# Expected: 422, code "UNSUPPORTED_CURRENCY"
```

### V3: Record a Balanced Transaction

```bash
# Debit Settlement EUR, credit Merchant 42
curl -X POST http://localhost:8080/v1/transactions \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: tx-payment-001" \
  -d '{
    "description": "Payment settlement",
    "entries": [
      {"accountId": "<settlement-eur-id>", "direction": "DEBIT", "amount": "125.50"},
      {"accountId": "<merchant42-id>", "direction": "CREDIT", "amount": "125.50"}
    ]
  }'
# Expected: 201, transaction with 2 entries, balanceAfter values correct
```

### V4: Refuse Unbalanced, Overdraft, and Precision Violations

```bash
# Unbalanced: debit 100, credit 99
# Expected: 422, code "ENTRIES_NOT_BALANCED"

# Overdraft: debit more than balance allows
# Expected: 422, code "OVERDRAFT_EXCEEDED", pointer "/entries/0/amount"

# JPY with decimals: amount "100.5"
# Expected: 422, code "DECIMAL_PRECISION_EXCEEDED"

# Currency mismatch: EUR and JPY in same transaction
# Expected: 422, code "CURRENCY_MISMATCH"
```

### V5: Reverse a Transaction

```bash
curl -X POST http://localhost:8080/v1/transactions/{transactionId}/reversal \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: reverse-tx1"
# Expected: 201, new transaction with flipped entries, reversesTransactionId set

# Second reversal attempt
# Expected: 409, code "ALREADY_REVERSED"

# Reverse a reversal
# Expected: 422, code "CANNOT_REVERSE_REVERSAL"
```

### V6: Idempotent Retry

```bash
# Replay exact same request with same Idempotency-Key
# Expected: 200, same response body as original, no new transaction

# Same key, different body
# Expected: 409, code "IDEMPOTENCY_KEY_CONFLICT"

# Missing Idempotency-Key header
# Expected: 400, code "MISSING_IDEMPOTENCY_KEY"
```

### V7: Payment-Settled Message

```bash
# Send a sample payment via the provided script
./scripts/send-payment-settled.sh P-9001 <merchant42-id> 125.50 EUR evt-001
# Expected: transaction appears in ledger, Settlement EUR debited, Merchant 42 credited

# Redeliver same payment (new event id)
./scripts/send-payment-settled.sh P-9001 <merchant42-id> 125.50 EUR evt-002
# Expected: no duplicate transaction

# Conflicting payload for same payment id
./scripts/send-payment-settled.sh P-9001 <merchant42-id> 200.00 EUR evt-003
# Expected: message goes to DLQ (payments.settled.dlq)
```

### V8: Announcement Delivery

After any recorded transaction, verify a message appears on `ledger.transactions.recorded` topic:

```bash
# Consume from the topic (Kafka CLI or test consumer)
# Expected: event contains transactionId, entries, currency, recordedAt
# Expected: partition key = account id (per-account ordering)
```

### V9: Concurrency (Integration Test)

Run via the test suite (not manual curl):
- 10 concurrent transfers of 20 from A to B (A starts at 100, overdraft 0)
- Expected: exactly 5 succeed, A=0, B=200, no deadlock
- Simultaneous A→B and B→A: both succeed or one retries, no permanent deadlock
- Per-currency SUM(balance) = 0 after all

### V10: Zero-Sum Integrity Check

```sql
-- Run against the database at any time
SELECT currency, SUM(balance) AS total
FROM accounts
GROUP BY currency;
-- Expected: total = 0.0000 for every currency
```

## Expected Test Output

`./run.sh` should end with all tests green:
- Unit tests: money arithmetic, validation rules, currency precision
- Integration tests: concurrency, idempotency, outbox delivery, DLQ, reversal races, zero-sum
- Contract tests: OpenAPI spec matches controller responses

## Troubleshooting

- **Port conflict**: Check that 8080, 8081, 3306, 9092 are free
- **Kafka not ready**: `run.sh` includes health-check waits; increase timeout if slow
- **Test timeout**: Concurrency tests use Awaitility; default timeout may need adjustment on slow machines
