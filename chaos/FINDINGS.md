# Chaos findings

Scenarios C2 and C3 were the first tests in this repo to run the service against a real
Postgres (Testcontainers + Toxiproxy). Until then the default test profile was H2 with Flyway
off, and 47 of 48 integration tests were `@Disabled`. So some of these bugs are "never ran on
Postgres" bugs that chaos surfaced as a side effect. Others only show up under faults or
concurrency. Each fixed bug has a regression IT outside the `chaos` tag:

- `payouts/PayoutSagaRegressionIT`
- `ledger/api/TransferIdempotencyRegressionIT`

## Bugs found by chaos

### 1. Payout deadlock despite ascending lock order (invariant 7)
- **Found by:** C2. Concurrent payouts from one wallet failed with `deadlock detected`.
- **Root cause:** `requestPayout` inserted the `payouts` row *before* `LedgerService.post`.
  That row has `FOREIGN KEY (wallet_account_id) REFERENCES accounts(id)`, and the FK check
  takes a `FOR KEY SHARE` lock on the wallet row. So the real lock order was
  wallet(KEY SHARE) → PAYOUT_IN_FLIGHT(UPDATE) → wallet(UPDATE), not ascending id order.
  - Tx A holds wallet KEY SHARE and waits for PAYOUT_IN_FLIGHT.
  - Tx B holds PAYOUT_IN_FLIGHT and waits to upgrade to FOR UPDATE on the wallet, which
    conflicts with A's KEY SHARE.
  - Deadlock.
- **Fix:** post the reservation first (ordered `FOR UPDATE` locks), then insert the payout row.
  The FK check then finds a row this transaction already holds.
- **Lesson:** "lock in ascending order" has to include *implicit* locks such as FK checks.
  Any flow that inserts a row referencing an account before calling `post()` has the same
  hazard (worth auditing the escrow and dispute flows).
- **Regression:** `concurrentPayoutsFromOneWalletDoNotDeadlock`. It was verified to fail with
  `deadlock detected` when the old insert order is put back.

### 2. Payout provider called inside the reservation transaction
- **Found by:** building C2. A connection reset after the provider has paid must leave a
  durable RESERVED/UNKNOWN row, and it did not.
- **Root cause:** `requestPayout` was `@Transactional` and called the provider inside that
  transaction. `confirmPayout` / `reversePayout` / `transitionToUnknown` were self-invoked, and
  two were `private`, so their `@Transactional` did nothing (no proxy). Consequences:
  - T1 was not committed when the provider was told to pay. A crash after the provider call
    (`AFTER_PROVIDER_CALL`) would roll back the reservation while the provider had paid.
    That is money out, with no ledger record.
  - The `PAYOUT_IN_FLIGHT` row lock was held across a 2s HTTP call, which serialises all
    payouts.
  - Confirm and reverse were not atomic on their own.
- **Fix:** explicit `TransactionTemplate` boundaries. T1 commits, then one provider call, then T2.
  Settling is one conditional update from any non-terminal state (`PayoutRepository.settle`),
  so the inline path and the recovery job can't both settle.
- **Regression:** `reservationIsDurableBeforeProviderCall`. The fake provider reads the DB on
  another connection at the moment the POST arrives and must see `RESERVED`.

### 3. Provider never received the idempotency key
- **Found by:** C2.
- **Root cause:** `PayoutProviderClient` POSTed `{beneficiary, amountPaise}` with no
  `X-Payout-Idempotency-Key`. The provider could not dedupe, and the recovery job's
  `GET /v1/payouts/by-key/{id}` could never find anything. Every UNKNOWN payout would
  eventually be tombstoned, even ones the provider had paid.
- **Fix:** send `X-Payout-Idempotency-Key = payoutId`.
- **Regression:** `providerGetsIdempotencyKey`.

### 4. Hikari `auto-commit: false` silently discarded writes made outside a transaction
- **Found by:** C2 setup. `AccountRepository.create` returned "Account not found after create".
- **Root cause:** with auto-commit off globally, a write outside `@Transactional` runs in an
  implicit transaction that is never committed, and Hikari rolls it back when the connection
  returns to the pool. This hit account creation and `PayoutRecoveryJob`'s backoff reschedule.
- **Fix:** removed the setting. Spring's transaction manager turns auto-commit off for each
  real transaction anyway.
- **Regression:** `writesOutsideTransactionsAreNotRolledBack`.

### 5. Every transfer returned 500 (idempotency response never stored)
- **Found by:** C3. The first run reported 0 violations with 0 of 100 transfers acknowledged,
  so it proved nothing. The scenario now also requires at least half of transfers to succeed.
- **Root cause:** `IdempotencyService` wrote `response_body = ?` (varchar) into a `JSONB`
  column. Postgres rejected it, the whole transaction rolled back, and the global handler
  returned 500 without logging anything.
- **Fix:** `?::jsonb`. `GlobalExceptionHandler` now logs unexpected exceptions (no bodies).
- **Regression:** `transferThenReplay`, `sameKeyDifferentBodyIsRejected`.

### 6. Code and migrations that had never run on Postgres
Found as soon as the chaos context booted against real Postgres:
- `V5__consumers.sql` used `raise_immutable_error()`, which no migration defined. Fixed by
  defining it in V5.
- `V7__payouts.sql` had a partial index predicate using `now()`, which Postgres rejects
  because it isn't IMMUTABLE. The age filter moved to the query.
- No database had a `flyway_schema_history` table, so editing V5 and V7 did not break the
  "never edit an applied migration" rule.
- pgjdbc can't bind `java.time.Instant`. Added `common.SqlTime.ts(...)` (OffsetDateTime) at
  every bind site.
- `LedgerService.post` bound a `List` to `ANY(?)`, which JdbcClient rejects. It now binds a
  `Long[]`.
- `PayoutProviderProperties` was never registered and its prefix didn't match
  `application.yml`, so the provider URL and timeouts were never bound.
- The recovery job compared app-clock timestamps with DB `now()`. It now uses the injected Clock.
- A tombstone cancel was recorded as `FAILED` instead of `CANCELLED`, and a 409 from
  `/cancel` (already settled) was ignored. Both are fixed.

### 7. Build: `./mvnw verify -Pchaos` ran zero tests
- **Root cause:** the profile set `<excludedGroups></excludedGroups>`, but Maven ignores empty
  config values when merging. The tests were both included and excluded as `chaos`.
- **Fix:** `test.groups` / `test.excludedGroups` properties that the profile overrides.
- Also, test JVMs now run with `-Duser.timezone=UTC`. A Windows JVM defaults to
  `Asia/Calcutta`, which Postgres 16 rejects at connect.

## Bugs found while building the load tests (P15)
These turned up when an end-to-end API test and a k6 smoke run drove every endpoint the load
scripts use against real Postgres. Each fix has its own regression test, named below.

### 8. Concurrent projects shared escrow accounts
- **Symptom:** I6 violations after a 15-second k6 smoke run, e.g. "ESCROW account 41 has
  balance 20600 but in-escrow milestones sum to 0". Projects 6 and 10 both pointed at account 41.
- **Root cause:** `createProject` named the accounts `"ESCROW:" + System.currentTimeMillis()`.
  Two projects created in the same millisecond got the same code, and
  `AccountRepository.create`'s `ON CONFLICT DO NOTHING` quietly returned the first project's
  account to the second. Their escrow and dispute money mixed.
- **Fix:** reserve the project id from its sequence first, then create `ESCROW:{projectId}` and
  `DISPUTE_HOLD:{projectId}` as the AGENTS.md account model specifies.
- **Regression:** `ProjectApiIT.concurrentProjectsDoNotShareAccounts` (30 concurrent creates).

### 9. Creating a project always failed on Postgres
`review_window` (INTERVAL) was bound as varchar and read back with no INTERVAL→Duration
converter. Fixed with `?::interval` on write and an explicit row mapper on read.
Regression: `ProjectApiIT.createAndReadProject`.

### 10. State conflicts returned 500
`InvalidStateTransitionException`, `CancelTooEarlyException` and `DisputeAlreadyOpenException`
extend `RuntimeException`, not `DomainException`, so a second approve on the same milestone was a
500. They now map to `409 INVALID_STATE_TRANSITION`. Services' `IllegalArgumentException`
("... not found") now maps to `404 NOT_FOUND`, and other bad input to `400 VALIDATION_FAILED`.
Regression: `ErrorMappingIT`.

### 11. Rate limiting was silently off everywhere
`RateLimitConfig` binds `escrow.ratelimit.*`, but `application.yml` had the settings at top-level
`ratelimit.*`. Nothing bound, so `enabled` defaulted to `false`. Fixed the yml. The Redis
settings were also under Boot 2's `spring.redis.*` (ignored); they're now `spring.data.redis.*`.
Regression: `RateLimitConfigBindingTest` binds the real `application.yml`.

### 12. Kafka consumers crash-looped (was an open issue above)
The relay publishes JSON without type headers, and `JsonDeserializer` had no default type, so
every record failed and the consumer was stuck at offset 0. Fixed in config: `JsonNode` default
type, type headers ignored, wrapped in `ErrorHandlingDeserializer`.
Regression: `AuditLogConsumerIT` waits for a ledger entry's event to land in `audit_log` exactly once.

### 13. docker compose `app` profile could not start
- The build contexts didn't match the Dockerfiles' `COPY` paths.
- kafka-init called `kafka-topics.sh`, which doesn't exist in cp-kafka images, so no topics
  were ever created.
- The Kafka healthcheck used `nc`, which isn't in the image, so Kafka was always unhealthy.
- The host listener advertised `kafka:9092`, which the host can't resolve.
- No JUnit regression is possible for compose. It was verified by bringing the stack up: Kafka
  is healthy, kafka-init exits 0 after creating the topics, and escrow-service reports UP.

### 14. Outbox relay fell minutes behind under load (benchmark finding)
- **Symptom:** after the first benchmark run, `/admin/invariants` reported `I9_WARN`: 28,778
  (later 42,496) outbox rows unpublished for more than 5 minutes. Kafka lagged the DB by minutes.
  Money invariants stayed clean.
- **Root cause:** the relay published one event at a time: `kafkaTemplate.send(...).get()`,
  then a separate `UPDATE outbox SET published_at` per row, then a 200ms sleep before the next
  batch of 100. About 140 events/s, while every transfer writes at least one event.
- **Fix:** send the whole batch asynchronously, wait for all acks, and mark every acked row
  published in one `UPDATE ... WHERE id = ANY(?)`. Keep draining while batches come back full.
  Still at-least-once: a row is marked only after Kafka acks it, and consumers dedupe by eventId.
- **Result:** 5,000 events drained in 2.97s (about 1,700 events/s) against about 140 events/s
  before, roughly 12× faster.
- **Regression:** `OutboxRelayThroughputIT` (5,000 events within 10s). It was verified to fail
  against the old relay, which still had 3,624 pending after 10s.

## Open issues (not fixed yet)
- **Remaining `@Disabled` integration tests.** `AbstractIntegrationTest` still uses H2, so the
  P2–P13 integration tests (47 of them) are still `@Disabled`. Moving them onto
  `AbstractToxiproxyIT` (or a plain Postgres base) will likely surface more bugs like #5 and #6.
- **The real provider service doesn't exist yet.** `mock-payout-provider` is still an empty
  Spring Boot app. C1/C2 use the in-JVM `FakePayoutProvider`. K1/K2 (docker compose) need the
  real one.
- **ChaosConfig never reads `chaos.crash-point`.** The crash point is only settable from code,
  so K1's `CHAOS_CRASH_POINT=...` env var won't work yet.

## Scenario notes
- **C2:** RANDOM provider plus flapping `reset_peer` toxics (upstream, timeout 0: request lost;
  downstream, timeout 50ms against a 150ms provider: provider paid, response lost). The first
  recovery passes still run under fault.
- **C3:** 300±100ms latency, plus a 2s timeout toxic every 4–10s. The first version used 1–4s
  intervals, so every ~4s transaction overlapped an outage and nothing could succeed. Gave-up
  transfers are expected (5 client attempts take ~2.5s, shorter than a drop window plus
  reconnect) and must have moved no money.
