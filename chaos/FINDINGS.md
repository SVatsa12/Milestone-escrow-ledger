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

## Bugs found by re-enabling the integration tests
Until now 72 of 85 tests were skipped. `AbstractIntegrationTest` booted an H2 "test" profile with
Flyway off, so the schema never existed; from P3 on, each prompt marked its new integration tests
`@Disabled("Requires Postgres")` instead of fixing the base class. Docker and Testcontainers were
never the problem. The base class now starts real Postgres, Kafka, Redis and Toxiproxy (H2 and
the H2 profiles are deleted), every test runs, and the `@AfterEach` invariant check was proven to
fire by a test that only corrupts one balance. Many old tests were themselves wrong (they wrote
`accounts.balance` directly, never moved the clock, asserted nothing, or counted global rows);
those were rewritten in the test commit. The code bugs they then exposed are below.

### 15. InvariantChecker reported "no violations" whenever a check failed to run
`check()` wrapped all nine checks in `catch (Exception e) { return List.of(); }` ("tables may not
exist, e.g. H2"), and I7/I8/I9 each had their own swallowing catch. On H2 the after-test invariant
assertion could never fail, and on Postgres any broken check query would silently disable it, along
with `GET /admin/invariants` and the reconciliation job. **Fix:** no try/catch; a check that can't
run fails loudly.
Regression: every integration test's @AfterEach (proven with a balance-corrupting test).

### 16. A journal entry with no postings could commit
The deferred balancing trigger fires on postings inserts, so an entry nobody posted to was never
checked, and I1 grouped by `postings.entry_id`, so it couldn't see it either. **Fix:** `V9` adds the
same deferred check on `journal_entries` inserts; I1 also looks for entries with no postings.
Regression: `LedgerSchemaIT.entryWithNoPostingsFailsAtCommit`.

### 17. The non-negative balance CHECK wasn't named as P2 specifies
V1 created `CHECK (allow_negative OR balance >= 0)` unnamed (Postgres called it `accounts_check`);
P2 specifies `CONSTRAINT balance_non_negative`. **Fix:** `V9` renames it.
Regression: `LedgerSchemaIT.nonNegativeAccountCannotGoNegative`.

### 18. Reusing an idempotency key with a different body was 400, not 422
P3 and AGENTS.md specify `422 IDEMPOTENCY_KEY_REUSED`; the code threw a `ValidationException`
(400 `VALIDATION_FAILED`). **Fix:** `DomainException("IDEMPOTENCY_KEY_REUSED", 422)`.
Regression: `TransferApiIT.sameKeyWithDifferentBodyIs422IdempotencyKeyReused`.

### 19. An unknown account id was 400, not 404
`GET /api/v1/accounts/{id}` threw a `ValidationException`. **Fix:** `404 NOT_FOUND`.
Regression: `TransferApiIT.unknownAccountIs404NotFound`.

### 20. Every reconciliation query failed on Postgres (`?1` placeholders)
`ReconciliationRepository` used JPA-style `?1, ?2, …` placeholders, which JDBC doesn't understand,
so P12's reconciliation never ran once. **Fix:** plain `?` (each was used once, in order, and bound by
explicit index).
Regression: `ReconciliationIT` (all repository tests).

### 21. Reconciliation couldn't read its own rows
It read `TIMESTAMPTZ` columns with `rs.getObject(col, Instant.class)`, which pgjdbc rejects
("conversion to class java.time.Instant from timestamp not supported"). **Fix:**
`SqlTime.instant(rs, col)` reads an `OffsetDateTime` and converts it.
Regression: `ReconciliationIT.shouldFindRunsByDate` and friends.

### 22. Every confirmed payout was reported as a mismatch
Reconciliation compared our state (`CONFIRMED`) with the provider's (`SUCCESS`) as raw strings,
so a clean day could never have zero mismatches. **Fix:** map the provider's vocabulary
(`SUCCESS` → `CONFIRMED`; FAILED, CANCELLED, PROCESSING unchanged) before comparing.
Regression: `ReconciliationIT.cleanDayHasNoMismatchForAConfirmedPayout`.

### 23. Re-running reconciliation for a date duplicated every mismatch
A rerun reuses the date's run (`UNIQUE (run_date, entity_type)`) and inserted each mismatch again.
**Fix:** `V10` makes mismatches unique per run, entity and reason; the insert is
`ON CONFLICT DO NOTHING`.
Regression: `ReconciliationIT.rerunDoesNotDuplicateMismatches`.

### 24. Every Razorpay webhook was a 500: the config was never registered
`RazorpayConfig` (`@ConfigurationProperties`) was never registered, so the controller always got
`null` (it was injected as `@Nullable`) and threw NullPointerException. With no secret configured,
`WebhookSignatureVerifier` also threw (NPE for null, "Empty key" for ""), which surfaced as 500s.
**Fix:** `@EnableConfigurationProperties(RazorpayConfig.class)`; the verifier fails closed (no secret
or no signature means "invalid", 400).
Regression: `DepositWebhookIT`.

### 25. Webhooks stored `"[B@1a2b…"` instead of the payload
The dedupe insert bound `rawBody.toString()` (a Java array reference) into a `jsonb` column, so
Postgres rejected it ("Token B is invalid") and every valid webhook failed. **Fix:**
`new String(rawBody, UTF_8)`.
Regression: `DepositWebhookIT.validCaptureCreditsTheWallet`.

### 26. No Razorpay deposit ever credited a wallet
The capture ran in the controller with **no transaction**: `LedgerService.post` (propagation
MANDATORY) threw, the controller caught it and answered **200**, and the dedupe row was already
committed, so Razorpay's retries were treated as duplicates. Money captured from the customer never
reached their wallet, and the event was lost. The old tests only checked `webhook_events` rows.
**Fix:** `DepositWebhookService.process(...)` is one `@Transactional` method (dedupe row, deposit
update, posting, outbox event); the controller verifies the signature and answers 5xx on a genuine
failure, so the rolled-back event is retried.
Regression: `DepositWebhookIT` (every case checks the wallet balance).

### 27. The app always used the fake Razorpay gateway
`FakeRazorpayGateway` was an unconditional `@Component @Primary`, and `RazorpaySdkGateway` was
conditional on `razorpay.key-id` having the literal value `"not_empty"`, so the real gateway never
loaded, even with test-mode keys configured. **Fix:** the SDK gateway when a key id is set, the fake
when it's blank.
Regression: `DepositWebhookIT` (runs with no key id, so the fake is selected), and since #41
`RazorpayGatewayWiringTest`: the `prod` profile wires `RazorpaySdkGateway` and no fake, and refuses
to start without keys.

### 28. Malformed requests were 500, not 400
A bad header value (`MethodArgumentTypeMismatchException`), a missing required header or
parameter, or an unreadable body fell through to the generic handler as 500. **Fix:**
`400 VALIDATION_FAILED`.
Regression: `RateLimiterIT` (a non-numeric X-Actor-Id) and `TransferApiIT.missingIdempotencyKeyIs400`.

### 29. No notification was ever created
`NotificationConsumer` read camelCase fields (`clientId`) at the envelope's top level, but real
events nest snake_case fields (`client_id`) under `payload`, so every handler threw NPE. The old
`ConsumersIT` never touched Kafka. **Fix:** unwrap `envelope.payload` and read the real field names,
with a clear error when one is missing.
Regression: `ConsumersKafkaIT.duplicateDeliveriesAreProcessedOnce`.

### 30. Poison messages never reached a dead-letter topic
The error handler was a bare `new DefaultErrorHandler()`: failed records were retried 9 times,
logged and dropped (its javadoc claimed "implicit .DLT support"). Once a recoverer was added, Spring
Kafka's default destination was `<topic>-dlt` on the same partition number, a topic nothing creates,
so the producer looped on `UNKNOWN_TOPIC_OR_PARTITION`. **Fix:** a `DeadLetterPublishingRecoverer` to
`<topic>.DLT` (the name P8 and kafka-init use), with its own producer (raw `byte[]` for records that
failed deserialization, JSON otherwise), and the three `.DLT` topics declared as beans.
Regression: `ConsumersKafkaIT.poisonMessageGoesToDltAndDoesNotBlock`.

### 31. One aggregate's events had no order on Kafka
The relay published without a key, so a project's events were spread over partitions and could be
consumed out of order (P8 asks for "the expected sequence"). **Fix:** key = `aggregateType:aggregateId`.
Regression: `ConsumersKafkaIT.lifecycleProducesTheExpectedAuditSequence`.

### 32. Approve and auto-release could deadlock
`approveMilestone` posted the release (locking ESCROW, the freelancer wallet and PLATFORM_FEES)
before its conditional milestone update; `AutoReleaseJob` locks the milestone first
(`FOR UPDATE SKIP LOCKED`) and then calls `approveMilestone`. A client approving at the moment the
job fired deadlocked. `openDispute` had the same inversion. **Fix:** the conditional state change
comes first in both (milestone row, then accounts), so a loser of the race stops before touching money.
Regression: `AutoReleaseIT.approveVersusAutoReleaseReleasesOnce`.

### 33. Every request was counted twice
The interceptor was registered by both `common/WebConfig` and `ratelimit/RateLimitWebConfig`
(rate limiting had been implemented as both "P10" and "P13"), so the real limit was 15 requests a
minute, not 30, and 2 payouts, not 5. An unused `RateLimitProperties` bound the dead `ratelimit.*`
prefix. **Fix:** both leftovers deleted.
Regression: `RateLimiterIT.limitIsHitAtNPlusOne`.

### 34. A 429 had no `code`
The interceptor serialized `ProblemDetail` with a plain `new ObjectMapper()`, which nests extension
members under `"properties"`, so the body had no top-level `code: RATE_LIMITED` (RFC 9457).
**Fix:** use Spring's configured `ObjectMapper`.
Regression: `RateLimiterIT.limitIsHitAtNPlusOne`.

### 35. A dispute split that doesn't add up was 400, not 422
P6 specifies 422; the code threw `IllegalArgumentException` (400). **Fix:**
`DomainException("VALIDATION_FAILED", 422)`.
Regression: `DisputeRulesIT.splitThatDoesNotAddUpIs422`.

## Closed P5-era gaps
These were listed as open issues below; each is now closed with its own tests.

### 36. No actor or role checks (P5) — closed
- **Gap:** the escrow service took no actor at all, so anyone could fund, submit, approve or
  cancel anyone's milestone. Disputes and payouts checked only the *role* header in the
  controller (any client could dispute any milestone, any freelancer could submit on any
  project), deposits trusted `X-Actor-Id`, admin endpoints and the simulated deposit were open,
  and `milestone_transitions` was never written.
- **Fix:** `common.Actor(id, role)` is resolved from `X-Actor-Id` / `X-Actor-Role` by
  `ActorArgumentResolver`; missing or invalid headers are `401 UNAUTHENTICATED`, and a header
  claiming `SYSTEM` is invalid (SYSTEM is built only in code, for auto-release). Every
  `EscrowService`, `DisputeService`, `DepositService` and `PayoutService` entry point takes the
  actor and checks it (`common.Authz`), so a wrong person or role is `403 FORBIDDEN` before
  anything moves:
  - create project: that client (or ADMIN); fund, approve, cancel: the project's client;
    submit: the project's freelancer; approve also accepts SYSTEM (auto-release)
  - open dispute: the project's client or freelancer; resolve: ADMIN
  - deposit: the client themselves (`clientId` in the body may not name someone else)
  - payout: the freelancer themselves, from `FREELANCER_WALLET:{id}` only
  - `/api/v1/admin/**` and `/api/v1/admin/deposits/simulate`: ADMIN
- Every milestone transition now inserts a `milestone_transitions` row with the actor's id and
  role (NULL id for SYSTEM), a reason, and the journal entry that moved money, if any. Auto-release
  is recorded as SYSTEM, and its `MilestoneReleased` event now says `autoReleased: true` (it was
  hard-coded `false`).
- **Regression:** `common/AuthorizationIT` (every endpoint: the right actor succeeds, each wrong
  actor is 403 and changes nothing; a freelancer of project B can't submit on project A; a client
  can't top up someone else's wallet; 401 cases; the transition audit trail).

### 37. Escrow flows posted with the caller's Idempotency-Key (invariant 8) — closed
- **Gap:** fund, release, dispute freeze, dispute resolve and refund posted their journal entries
  as type `TRANSFER` keyed by the *HTTP* Idempotency-Key (the dispute controller built the right
  key itself, but the service would post whatever it was given). Only the conditional state update
  stopped a second posting; with that guard bypassed (a bug, a manual DB fix, a racing job) the
  same approve under a new HTTP key released the money twice.
- **Fix:** `ledger.LedgerKeys` holds the entry types and the AGENTS.md key for each flow, and
  every `LedgerService.post` uses it: `FUND fund:project:{id}`, `RELEASE release:milestone:{id}`,
  `DISPUTE_FREEZE dispute-freeze:milestone:{id}`, `DISPUTE_RESOLVE dispute-resolve:{id}`,
  `REFUND refund:milestone:{id}`, `DEPOSIT deposit:rzp:{paymentId}`,
  `PAYOUT_RESERVE|CONFIRM payout:reserve|confirm:{id}`, `PAYOUT_REVERSE|CANCEL payout:reverse:{id}`.
  The HTTP Idempotency-Key is still required, and only for replaying responses. The dev-only
  simulated deposit is now type `SIMULATED_DEPOSIT` (it is not a Razorpay capture), and test seed
  postings are type `TEST_SEED`.
- **New invariant I10** (`InvariantChecker`): every FUND, RELEASE, DISPUTE_FREEZE,
  DISPUTE_RESOLVE, REFUND, DEPOSIT or PAYOUT_* entry has exactly the key its reference dictates (a
  DEPOSIT's key must match the payment id recorded on its deposit; an unknown PAYOUT_* type is a
  violation). It runs after every integration test.
- **Also fixed:** resolving an already resolved dispute threw `IllegalStateException` (500); it is
  now `409 INVALID_STATE_TRANSITION`.
- **Regression:** `ledger/LedgerIdempotencyKeyIT` (per flow: once over HTTP, again with another
  HTTP key, then with the state guard bypassed in SQL and the service called directly, or a direct
  ledger replay where the schema forbids the bypass; exactly one entry each time. All 9 tests fail
  against the old code), `DepositWebhookIT.depositKeyHoldsWithoutTheStatusGuard`, and
  `i10FlagsWrongKeys`.

### 38. Transfers and account creation were open to anyone — closed
- **Gap:** `POST /api/v1/transfers` (move money between *any* two accounts) and
  `POST /api/v1/accounts` said "ADMIN only" in their javadoc but took no actor, so any caller,
  with or without headers, could drain any wallet.
- **Fix:** both take the `Actor` and call `Authz.requireAdmin` first: a CLIENT or FREELANCER is
  403 FORBIDDEN and nothing is validated, posted or created; no actor headers is 401.
  `TransferApiIT` and `ApiClient.transfer` now send ADMIN headers (the k6 scripts, `run-bench.sh`
  and the other transfer tests already did). `RateLimiterIT`'s "no X-Actor-Id is not limited" case
  now expects 401 on every request, never 429, and no money moved.
- **Audit of every mutating endpoint** (`@PostMapping`; there are no `@PutMapping`,
  `@PatchMapping` or `@DeleteMapping`), with the role it requires and its `AuthorizationIT` test:

  | endpoint | allowed | AuthorizationIT |
  |---|---|---|
  | `POST /api/escrow/projects` | the named CLIENT, or ADMIN | `createProject` |
  | `POST /api/escrow/projects/{id}/fund` | the project's CLIENT | `fund` |
  | `POST /api/escrow/milestones/{id}/submit` | the project's FREELANCER | `submit` |
  | `POST /api/escrow/milestones/{id}/approve` | the project's CLIENT (SYSTEM internally) | `approve` |
  | `POST /api/escrow/milestones/{id}/cancel` | the project's CLIENT | `cancel` |
  | `POST /api/escrow/disputes` | the project's CLIENT or FREELANCER | `openDispute` |
  | `POST /api/escrow/disputes/{id}/resolve` | ADMIN | `resolveDispute` |
  | `POST /api/v1/deposits` | that CLIENT | `deposit` |
  | `POST /api/v1/payouts` | that FREELANCER, own wallet | `payout`, `payoutOnlyFromOwnWallet` |
  | `POST /api/v1/transfers` | ADMIN | `transfer` |
  | `POST /api/v1/accounts` | ADMIN | `createAccount` |
  | `POST /api/v1/admin/deposits/simulate` | ADMIN | `adminEndpoints` |
  | `POST /api/v1/admin/recon/run` | ADMIN | `adminEndpoints` |
  | `POST /api/escrow/users` | ADMIN (closed in #39) | `createUser` |
  | `POST /webhooks/razorpay` | no actor: authenticated by the HMAC signature | `DepositWebhookIT` (bad signature is 400) |

  Since #39 every row with a role also requires that the actor id is a real user whose stored role
  is the claimed one (`spoofedRoleIs401`, `unknownActorIs401`).
- **Regression:** `AuthorizationIT.transfer`, `AuthorizationIT.createAccount`.

### 39. Any caller could claim any role (role spoofing) — closed
- **Gap:** `ActorArgumentResolver` only parsed the headers. It never looked at `users`, so a
  CLIENT could send `X-Actor-Role: ADMIN` and pass every ADMIN check (transfers, account and user
  creation, dispute resolution, admin endpoints), and an id that belonged to no user at all was
  accepted too. Shown before the fix: client user 1 claiming ADMIN and the unknown id 987654321
  claiming ADMIN both got `200` from `GET /api/v1/admin/invariants`. On a fresh database the first
  client got id 1, so the "admin id 1" that tests and k6 sent was really that client.
  `POST /api/escrow/users` also had no actor check at all.
- **Fix:**
  - The resolver looks the id up in `users`: an unknown id, or a claimed role that isn't the stored
    one, is `401 UNAUTHENTICATED`. Found roles are cached; that is correct because `V11` makes a
    user's id and role immutable and forbids deleting users. Unknown ids are never cached.
  - `V11` seeds the bootstrap ADMIN, **id 1 `bootstrap-admin`** (README, AGENTS.md), moves the
    `users` id sequence past it, and refuses to run if id 1 already exists as a non-admin.
  - `POST /api/escrow/users` is ADMIN-only (and an ADMIN may create other ADMINs).
  - `ApiClient.BOOTSTRAP_ADMIN_ID`, k6's `BOOTSTRAP_ADMIN_ID` and `run-bench.sh` use id 1;
    `RateLimiterIT` now uses real users instead of random actor ids.
- **Regression:** `AuthorizationIT.createUser`, `spoofedRoleIs401` (CLIENT/FREELANCER ids claiming
  ADMIN, the admin claiming CLIENT, a freelancer claiming CLIENT; all 401, nothing changes),
  `unknownActorIs401`.

### 40. escrow-service's tests ran against a hand-written stub, not the provider — closed
- **Gap:** every payout test (saga, regressions, reconciliation, C1/C2) talked to an in-JVM stub
  that only resembled `mock-payout-provider`: it settled PROCESSING payouts the moment anyone asked,
  refused to cancel a PROCESSING payout, delayed every answer in C2, had no ASYNC mode, and let tests
  rewrite its records directly. Tests could pass against behaviour the real provider doesn't have.
- **Fix:** `AbstractIntegrationTest` starts the real `mock-payout-provider` once per test JVM, in
  its own Spring context on a random port, against the shared Postgres container (schema
  `provider`, its own Flyway), behind Toxiproxy as before. Tests drive it only through
  `test.ProviderControl` (its real HTTP API: modes, reset, by-key, settlements, a direct POST), plus
  the provider's test-only `PayoutRequestObserver`, which counts POSTs per key and lets
  `PayoutSagaRegressionIT` look at escrow's DB the instant a request arrives. The stub is deleted.
  - Two Boot apps in one JVM clashed: both modules kept migrations in `classpath:db/migration`
    (each Flyway saw the other's V0/V1; the provider's now live in `db/provider`), and
    `classpath:application.yml` resolved to escrow's file, so the provider context loads no config
    file and gets its settings explicitly, with Kafka/Redis auto-configuration excluded.
  - The provider's Boot plugin now writes the runnable jar as `-exec.jar` (its Dockerfile uses it),
    so the plain jar can be a test dependency; escrow-service's Dockerfile builds through the reactor,
    because Maven resolves test-scoped dependencies even with `-DskipTests` (the old one now failed).
- **Migrated:** `PayoutSagaIT` (TIMEOUT modes use `delayMs` 2.5s against the 2s read timeout; the
  "provider paid after all" case is a real late POST; new ASYNC tests settle SUCCESS and FAILED via
  the recovery job with seeds 158 and 3187), `PayoutSagaRegressionIT` (the observer also checks the
  `payout:reserve` entry is committed), `ReconciliationIT` (amount drift is an UPDATE on
  `provider.payouts`), C2 (RANDOM over TIMEOUT_THEN_* with 150ms, ASYNC and ERROR_500, keeping the
  "paid, answer lost" window; the 1:1 check uses settlements plus by-key), `AuthorizationIT`,
  `LedgerIdempotencyKeyIT`, `LoadPathApiIT`.
- **C1 never ran:** it was `@Disabled`, never touched Toxiproxy, and paid out from account 1 for
  freelancer 42. It now does what its javadoc says: 3s of latency, 100 payouts all UNKNOWN, recovery
  confirms all 100 with one POST each and no reverse entries.
- **Saga bugs found by the real provider: none.** Every payout test and C1/C2 passed against it
  unchanged in substance; the stub's differences hadn't been hiding a saga defect.

### 41. Reconciliation only looked one way, and deposits not at all — closed
- **Gap:** payout reconciliation walked *our* payouts and asked the provider about each one, so a
  payout the provider made that we never recorded (or recorded as FAILED and refunded: a double
  payout) could never be found. Deposit reconciliation was a stub that recorded "not yet
  reconciled". The invariant check ran but only logged. A re-run only ever added mismatches, so a
  fixed problem stayed listed.
- **Fix:** `ReconciliationService` compares both directions for one Asia/Kolkata day and never moves
  money:
  - **PAYOUT_PROVIDER:** our CONFIRMED payouts against the provider's `GET /v1/settlements`.
    `MISSING_AT_PROVIDER`, `MISSING_IN_LEDGER` (the detail names our state: UNKNOWN/PROCESSING is
    lag, FAILED/CANCELLED is a DOUBLE PAYOUT, ABSENT is a key we never issued), `AMOUNT_MISMATCH`.
  - **RAZORPAY:** our CAPTURED deposits (new `deposits.captured_at`, from the injected Clock)
    against `RazorpayGateway.fetchPayments` with status captured. "Credited" means the ledger has
    the `deposit:rzp:{paymentId}` entry. `MISSING_IN_LEDGER` (a missed webhook),
    `MISSING_AT_GATEWAY`, `AMOUNT_MISMATCH`.
  - **Midnight:** keys are matched over a ±1 day window and each pair is attributed to the external
    side's day, so "settled 23:59 IST, confirmed 00:01" is one match, not a mismatch on each day.
  - Each run records `InvariantChecker` violations as `LEDGER_INVARIANT`, replaces its own
    mismatches in one transaction on a re-run, and publishes
    `escrow.recon.mismatches{source,reason}` and `escrow.recon.runs{source,status}`. External data is
    fetched before any transaction is opened.
- **Also found:**
  - `RazorpaySdkGateway.fetchPayments` made one call (at most one page, 10 by default) and parsed the
    SDK's `List<Payment>` as a JSON object, so it would have thrown on the first real response. It
    now pages with `count=100`/`skip` until a short page and sends the day as unix seconds.
  - There was no `prod` profile. `application-prod.yml` now requires the Razorpay keys, and the SDK
    gateway refuses a missing or unresolved key: Spring resolves placeholders leniently in the
    gateway condition and would otherwise hand Razorpay the literal `${RAZORPAY_KEY_ID}`.
- **Test infrastructure:** escrow's test contexts and the in-process provider now share one
  `MutableClock`, so a test can move both to a chosen day; ReconciliationIT reconciles its own days
  in 2031 so other test classes' payouts and deposits never show up as noise.
- **Regression:** `ReconciliationIT` (clean day; stray provider payout; paid-but-FAILED double payout;
  amount drift; missing at provider; payout and deposit across midnight; re-run replaces; missed
  webhook; missing at gateway; deposit amount; LEDGER_INVARIANT; metrics),
  `RazorpaySdkGatewayPagingTest`, `RazorpayGatewayWiringTest`.

### 42. The batched outbox relay could mark an event published ahead of an earlier failed one
- **Gap:** since #14 the relay sends a batch asynchronously and marks every acknowledged row in one
  UPDATE. Point 1 held (a row was marked only after its own ack), but if row A of an aggregate
  failed and the later row B of the same aggregate succeeded, B was marked published and A retried
  afterwards, so the outbox recorded B as delivered before A.
- **Fix:** `OutboxBatchPublisher` decides what may be marked: per aggregate (the Kafka key), only
  the prefix of rows before the first failure in the batch. Later rows are held back unmarked and go
  again after A on a later poll (claims are `ORDER BY id`). A synchronous failure (the row can't be
  built or serialized) stops the aggregate before its later rows are even sent. A send not acked by
  the deadline (`escrow.outbox.relay.ack-timeout`, 5s) counts as failed.
- **Also fixed:** `enable.idempotence: true` sat directly under `spring.kafka.producer`, which isn't
  a Boot property, so it was never passed to the producer (idempotence was on only because Kafka 3+
  defaults to it). It now lives under `properties`, with `max.in.flight.requests.per.connection: 5`.
- **Still possible (documented, not fixed):** when a failure is reported asynchronously, B may
  already be on Kafka; it is re-sent after A and consumers dedupe by eventId, but a consumer can see
  B before A's retry. With idempotence and the same partition, the broker usually fails B's
  in-flight batch with A's, but that isn't guaranteed. Closing it means waiting for A's ack before
  sending B, which serializes a busy aggregate: `OutboxRelayThroughputIT` drains 5,000 events of ONE
  aggregate, about 3,400/s now, and would drop to one ack round trip per event.
- **Regression:** `OutboxBatchPublisherTest` (a KafkaTemplate over a MockProducer that fails chosen
  records asynchronously, synchronously, or never acks them; a 500-row randomized check that the
  published rows are exactly each aggregate's acked prefix), `OutboxProducerConfigIT` (effective
  `ProducerConfig`: idempotence on, acks=all, in-flight <= 5), `OutboxRelayThroughputIT` unchanged.

### 43. CI failed on c7fda31: two nondeterministic tests (both test bugs)
CI run 43627635569 (commit c7fda31) failed, passed and failed again on three attempts of the same
commit, in two different tests. Neither was a production race.
- **`RateLimiterIT.payoutsHaveTheStricterLimit`** (attempt 1): `Status expected:<429> but was:<422>`.
  MockMvc's printout of the six payout responses showed `X-RateLimit-Remaining` 4, 4, 3, 2, 1, 0: the
  test ran from 18:38:59.65 to 18:39:00.5 and straddled a boundary of the limiter's fixed 60 s window
  (keyed on the injected clock), so request 1 counted in one window and requests 2..6 in the next,
  and the 6th got through. Every limit test in the class assumed N+1 real-time requests fall in one
  window. **Fix:** `MutableClock.freezeAt(...)`; RateLimiterIT freezes the clock mid-window before
  each test (only `advance()` moves it), and `aBurstAcrossAWindowBoundaryIsCountedInTwoWindows`
  pins the fixed-window behaviour deliberately.
- **`OutboxBatchPublisherTest.asyncFailureHoldsBackTheRestOfThatAggregate`** (attempt 3): the
  later row x3 was never sent. `KafkaTemplate.doSend` checks `isDone()` on the producer's future
  right after `send()` and rethrows a failure it can already see, synchronously. The test's mock
  "async" failure returned a future completed on the common pool, so whether x2 failed
  asynchronously or synchronously depended on which thread won; when it looked synchronous the
  publisher correctly stopped the aggregate. **Fix:** the mock reports async failures only through
  the callback from its own thread and returns a future that never completes;
  `alreadyFailedFutureIsASynchronousFailure` covers the other case deterministically. The relay's
  behaviour is right in both cases.
- **Infrastructure, found while looping locally:** one of 30 CPU-limited RateLimiterIT runs errored
  in every test because `apache/kafka:latest` exited with code 126 before logging "RECOVERY to
  RUNNING", which failed AbstractIntegrationTest's static initializer. **Hardened:** every shared
  container (Postgres, Toxiproxy, Kafka, Redis) now has 3 startup attempts and a 3-minute startup
  timeout. Only the container start is retried, never an assertion.
- **Evidence (after the fixes):** RateLimiterIT 30/30 in fresh JVMs with `-XX:ActiveProcessorCount=2` and 30/30
  plain, the two loops running side by side on one Docker host, with 0 container start failures;
  OutboxBatchPublisherTest 30/30 plain and 30/30 with 2 processors; the full suite green alongside a
  loop. Before: CI 1 pass in 3 attempts of the same commit; locally 8/8 (the boundary case is rare).

### 44. Half the chaos suite only pretended to inject faults
An audit of P14 found C1 (fixed earlier, #40), C4, C5 and C6 were `@Disabled` templates that printed
"(Simulating: …)" and asserted against counters they incremented themselves; C4 wrote to a
`processed_events` table instead of calling the webhook. K1 couldn't fire: nothing read
`chaos.crash-point`, so `CHAOS_CRASH_POINT=AFTER_RESERVE` armed nothing and no container ever died
mid-saga. K2 killed a container named `escrow-service` (compose calls it
`milestone-escrow-ledger-escrow-service-1`), needed a local `k6`, and reported 0 operations. Only
C2 and C3 were real, and no scenario reconciled.
- **Fix:**
  - **C4** sends 600 signed webhooks (20 payments × 25 `payment.captured` + 5 `order.paid`)
    shuffled from 32 threads to the real endpoint.
  - **C5** freezes the Kafka broker for 30 s (`docker pause`) during 200 HTTP escrow operations.
  - **C6** races client approve, freelancer dispute and 4 auto-release threads on 100 milestones
    at the exact review-window boundary.
  - **K1/K2** are Python (`chaos/kill_mid_saga.py`, `chaos/kill_under_load.py`, shared
    `chaos/stack.py`). K1 really halts the JVM at each crash point and checks the container's exit
    code is 137. K2 runs `mixed.js` and `docker kill -s KILL`s the app every 15 s of uptime
    (checking 137 each time). It then requires exactly one journal entry per acknowledged
    operation under its business key.
  - **`ChaosCrashPointLoader`** (only under the `chaos` profile) arms the crash point from
    `chaos.crash-point`. Docker compose passes `SPRING_PROFILES_ACTIVE` and `CHAOS_CRASH_POINT`.
  - **Every scenario ends by reconciling its day with both sources (0 mismatches required)**
    and writes `chaos/results/<scenario>-<timestamp>.json` with operations, violations,
    mismatches and result. JUnit scenarios each get their own day on the shared clock, so earlier
    scenarios in the JVM can't pollute "today".
  - **`chaos/run_all.py`** runs everything and prints the table. The bash scripts are gone.
- **Regression:** `ChaosCrashPointLoaderTest`: the crash point is armed from config under the
  `chaos` profile, and not without it.

### 45. Kafka consumers on virtual threads froze the whole app after a kill -9 (found by chaos K2)
- **Symptom:** in K2, after the first `docker kill -s KILL`, the restarted app logged
  "Consumer thread failed to start" twice (30 s apart), then "Started EscrowServiceApplication in
  66 s", and then never answered `/actuator/health` (connections hung). Outbox and payouts froze.
- **Evidence:** a SIGQUIT thread dump showed the JVM's only two carrier threads,
  `ForkJoinPool-1-worker-1/2`, each "Carrying virtual thread" for the whole 586 s uptime with
  49–79 ms of CPU: blocked, not busy. Re-running with `-Djdk.tracePinnedThreads=full` printed the
  pinned stack: `KafkaMessageListenerContainer$ListenerConsumer.run` → `KafkaConsumer.poll` →
  `AbstractCoordinator$JoinGroupResponseHandler.handle <== monitors:1`.
- **Root cause:** with `spring.threads.virtual.enabled=true`, Boot runs Kafka listener containers
  on virtual threads. The Kafka client blocks inside `synchronized` code during a consumer-group
  rejoin, which pins the virtual thread to its carrier. escrow-service runs with `cpus: 2`, so it
  has 2 carriers, and it has 2 consumers (audit, notifications). After a kill -9 the rejoin waits
  for the dead members' session timeout, both carriers stay pinned, and no other virtual thread
  (Tomcat's request handling, schedulers, the consumers' own start) can run at all.
- **Fix:** a `ContainerCustomizer` gives the listener containers a platform-thread executor
  (`kafka-consumer-*`). HTTP and everything else keep virtual threads.
- **Regression:** `consumers/KafkaConsumerThreadsIT` requires every consumer loop to be a
  platform thread named `kafka-consumer-*` with virtual threads on; it fails on the old
  configuration (no platform consumer loop exists). K2 then passed: 4 kills, 3,513 acknowledged
  operations each with exactly one journal entry.

## Open issues (not fixed yet)
- **`RateLimitInterceptor` builds `Retry-After` from `Instant.now()`**, not the injected Clock.
- **The outbox relay's backoff isn't enforced.** `handlePublishFailure` computes a retry time but
  the outbox has no column for it and the claim doesn't filter on one, so a failing row is retried
  on the very next poll (every 200ms) with `attempts` only counting up.
- **The relay's claim lock ends before it publishes.** The `FOR UPDATE SKIP LOCKED` claim runs in
  its own transaction and commits before the sends, so two relay instances could claim the same
  rows: duplicates (deduped by consumers) and per-aggregate reordering. Safe with today's single
  instance per deployment; not with several.
## Scenario notes
- **C2:** RANDOM provider plus flapping `reset_peer` toxics (upstream, timeout 0: request lost;
  downstream, timeout 50ms against a 150ms provider: provider paid, response lost). The first
  recovery passes still run under fault.
- **C3:** 300±100ms latency, plus a 2s timeout toxic every 4–10s. The first version used 1–4s
  intervals, so every ~4s transaction overlapped an outage and nothing could succeed. Gave-up
  transfers are expected (5 client attempts take ~2.5s, shorter than a drop window plus
  reconnect) and must have moved no money.
