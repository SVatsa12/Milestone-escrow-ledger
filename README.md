# Milestone Escrow Ledger

A double-entry ledger system for milestone-based escrow payments with auto-release and payout saga patterns.

## Problem

Freelancers and clients both get ghosted in milestone-based work:
- Clients fund milestones upfront but don't approve completion
- Freelancers submit work but never get paid after the review window expires
- Disputes freeze funds indefinitely
- Payout timeouts cause blind retries and double payments

## Solution

- **Double-entry ledger** with database-enforced invariants: all entries sum to zero, accounts never exceed constraints
- **Milestone state machine** with explicit valid transitions: DRAFT → FUNDED → SUBMITTED → APPROVED → RELEASED or DISPUTED
- **Auto-release job** paying freelancers after a fixed review window if the client doesn't approve
- **Payout saga** handling provider timeouts safely through an UNKNOWN state and tombstone-based cancellation
- **Idempotency keys** and transactional outbox ensuring exactly-once delivery to Kafka
- **Distributed tracing** through event IDs (UUIDv7) for audit and reconciliation

## Getting Started

### Prerequisites

- Docker and docker-compose
- JDK 21
- Maven 3.9+

### Running Locally

```bash
# Start infrastructure (Postgres, Kafka, Redis)
docker compose up -d

# Build
./mvnw clean package

# Run tests
./mvnw verify

# Run escrow-service (from IDE or):
./mvnw spring-boot:run -pl escrow-service

# Run mock payout provider (from IDE or):
./mvnw spring-boot:run -pl mock-payout-provider
```

### Authentication (stub) and the bootstrap admin

Every write endpoint needs `X-Actor-Id` and `X-Actor-Role` (`CLIENT`, `FREELANCER` or `ADMIN`).
The service checks both against the `users` table: an unknown id, or a role that isn't that
user's stored role, is `401 UNAUTHENTICATED`; the right kind of user acting on someone else's
project, wallet or dispute is `403 FORBIDDEN`.

A fresh database has exactly one user, seeded by Flyway `V11`:

| id | name | role |
|---|---|---|
| 1 | `bootstrap-admin` | `ADMIN` |

Only an ADMIN can create users, so start there:

```bash
curl -X POST localhost:8080/api/v1/users   -H 'X-Actor-Id: 1' -H 'X-Actor-Role: ADMIN' -H 'Content-Type: application/json'   -d '{"name":"alice","role":"CLIENT"}'
```

If an existing database already has a non-admin user with id 1, V11 refuses to run; recreate the
database (`docker compose down -v`).

### API Documentation

Once the service is running, visit:
- Swagger UI: http://localhost:8080/swagger-ui.html
- Actuator: http://localhost:8080/actuator/health

## Architecture

```
┌─ API Layer ─────┐
│ POST /transfer  │
│ POST /approve   │
│ POST /payout    │
└────────┬────────┘
         │
    ┌────▼──────────────────┐
    │  Service Layer        │
    │ Escrow / Ledger       │
    │ Idempotency check     │
    │ State transitions     │
    └────┬────────┬─────────┘
         │        │
    ┌────▼──┐  ┌──▼──────────────┐
    │ Ledger│  │ Outbox (Kafka)  │
    │ DB    │  │ Relay job       │
    └─────┬─┘  └──┬──────────────┘
          │       │
    ┌─────▼──┐  ┌─▼───────────────────────┐
    │ Postgres │  │ Kafka Topics           │
    │ - ledger  │  │ - milestone-events     │
    │ - escrow  │  │ - payment-events       │
    │ - outbox  │  │ - entry-events        │
    │ - disputes│  │ - *.DLT (dead letters) │
    │ - payouts │  └────────────────────────┘
    └──────────┘
```

## Double-Entry Ledger

All money moves are atomic, two-legged transactions:

```
Ledger Entry {
  journal_entry:
    type: TRANSFER
    reference_id: milestone_123
  postings:
    - account: CLIENT_WALLET, amount: -1000 paise
    - account: FREELANCER_WALLET, amount: +1000 paise
}
```

Database constraints enforce:
- **Balanced**: every entry sums to 0 and has ≥ 2 postings
- **Immutable**: ledger rows cannot be updated or deleted
- **Accurate**: each account's balance = SUM of all postings to it

## Invariants

| ID | Guarantee | Test |
|---|---|---|
| I1 | Every journal entry sums to 0 and has ≥2 postings | LedgerSchemaIT |
| I2 | SUM(postings.amount) over all entries = 0 | LedgerPropertyIT |
| I3 | account.balance = SUM(postings) for that account | InvariantChecker |
| I4 | No non-negative-allow account has balance < 0 | LedgerPropertyIT + ConcurrencyIT |
| I5 | No duplicate idempotency_key in journal_entries | UNIQUE index + test |
| I6 | ESCROW balance = SUM(milestone amounts in FUNDED\|SUBMITTED\|APPROVED) | EscrowFlowIT |
| I7 | PAYOUT_IN_FLIGHT balance = SUM(payouts in RESERVED\|PROCESSING\|UNKNOWN) | PayoutSagaIT |
| I8 | CONFIRMED payouts have exactly 1 confirm entry; FAILED/CANCELLED have 1 reverse | PayoutRecoveryIT |
| I9 | Outbox rows > 5 min old are published | OutboxRelayIT |

## Development

### Project Structure

```
escrow-service/
├── src/main/java/com/svatsa/escrow/
│   ├── ledger/              # Posting engine, idempotency, accounts
│   ├── escrow/              │ Projects, milestones, state machine
│   ├── payouts/             │ Saga, recovery job
│   ├── deposits/            │ Razorpay webhook handling
│   ├── events/              │ Outbox, relay, consumers
│   ├── recon/               │ Reconciliation, invariants
│   ├── ratelimit/           │ Redis rate limiting
│   └── common/              │ Clock, exception handler, config
├── src/test/java/...        # Integration tests, chaos tests
└── src/main/resources/
    ├── db/migration/        # Flyway migrations
    └── application.yml

mock-payout-provider/
├── src/main/java/com/svatsa/provider/
│   └── api/                 │ REST endpoints (modes, payouts, settlements)
├── src/test/java/...
└── src/main/resources/
    └── db/migration/
```

### Running Tests

```bash
# Unit + integration tests (excludes chaos)
./mvnw verify

# Chaos tests only
./mvnw verify -Pchaos

# Single test class
./mvnw test -Dtest=LedgerPropertyIT

# With debug logging
./mvnw test -X
```

## Key Design Decisions

### Why a DB-level deferred trigger for balanced entries?

A CHECK constraint can't sum rows. A deferred AFTER INSERT trigger fires at commit, after all postings are inserted, so it sees the complete transaction. Rolling back on violation preserves atomicity.

### Why UNKNOWN state in payouts?

Timeouts don't tell you if the provider received or rejected the request. Parking in UNKNOWN + periodic status checks avoids blind retries and double payments. A tombstone (canceled key) makes late-arriving successful responses harmless.

### Why transactional outbox?

Kafka and Postgres are not transactional together. Outbox rows commit with ledger changes; a relay job publishes them to Kafka later. Consumers dedupe by event ID, so we get exactly-once-delivered events with exactly-once money movement.

### Why integer paise instead of decimal?

Paise (1/100 rupee) as BIGINT avoids floating-point rounding errors. All APIs and responses use integers. Rupee formatting is only in frontend/notifications.

## FAQ

**Q: Can I get a double payout?**  
A: No. Payouts use a conditional UPDATE with an idempotency key that's the payout ID. A second call finds it already CONFIRMED (or FAILED) and doesn't confirm again. If a provider timeout causes us to query its status, we never POST again.

**Q: What if Kafka is down?**  
A: Ledger changes commit to Postgres; outbox rows are marked published_at only after Kafka acks. Audit and notifications wait until Kafka is up. Monetary operations are never blocked by Kafka.

**Q: Can I approve a disputed milestone?**  
A: No. The state machine only allows SUBMITTED → APPROVED, and disputed milestones are in DISPUTED state, which has no transition to APPROVED. The state machine is enforced by SQL UPDATE conditionals.

**Q: How do you handle read replicas?**  
A: All reads go to the primary (connection string must target primary). A future version could route reads to replicas with eventual consistency and a read-repair pattern on conflicts.

## Contributing

See `.github/workflows/ci.yml` for build requirements. New money-moving code must:
1. Use `@Transactional` with explicit propagation
2. Include an Idempotency-Key header check or OutboxWriter.write() call
3. Add an invariant check to InvariantChecker
4. Have an integration test (IT suffix) that asserts invariants at cleanup

## License

MIT
