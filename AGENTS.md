# AGENTS.md: Milestone Escrow Ledger

> Put this file in the repo root before running any prompt. Rename or copy it to whatever your coding tool reads automatically (`CLAUDE.md`, `.cursorrules`, `.github/copilot-instructions.md`, `GEMINI.md`). Every prompt in PROMPTS.md assumes the agent has read it.

## What this is
A milestone-based escrow service for freelancers and clients. The client pays a project's budget into escrow. Money is released one milestone at a time, either when the client approves the work or automatically when the client goes silent past the review window. A dispute freezes the milestone's money. A client can reclaim a milestone's money only after the freelancer misses the deadline plus a grace period. The double-entry ledger always balances to zero.

## Tech stack (do not substitute)
- Java 21, Spring Boot 3.5.16, Maven multi-module (`escrow-service`, `mock-payout-provider`)
- **Build and test only on JDK 21, the same as the runtime image.** `maven-enforcer-plugin` fails the build on any other JDK; point `JAVA_HOME` at a JDK 21. A newer JDK hides real bugs: JDK 24 stopped pinning virtual threads in `synchronized` code, so a production deadlock only showed in the JDK 21 container (chaos/FINDINGS.md #55, #56).
- PostgreSQL 16 with Flyway migrations. Use Spring `JdbcClient` for all ledger and escrow SQL. **No JPA/Hibernate in the money path.**
- Apache Kafka 4.x in KRaft mode, through Spring for Apache Kafka
- Redis 7, used only for rate limiting
- Razorpay Java SDK (`com.razorpay:razorpay-java`) in **test mode** for deposits
- Tests: JUnit 5, AssertJ, Testcontainers (Postgres, Kafka, Redis, Toxiproxy), jqwik for property tests, Awaitility for async assertions
- k6 for load tests, Toxiproxy for network faults
- Docker Compose for local runs. GitHub Actions for CI.

## Package layout (`escrow-service`)
```
com.svatsa.escrow
├── common        // Money helpers, Clock config, errors, ProblemDetail handler
├── ledger        // accounts, journal entries, postings, LedgerService
├── idempotency   // IdempotencyService, request hashing
├── escrow        // projects, milestones, state machine, EscrowService
├── dispute       // disputes, DisputeService
├── outbox        // outbox writer + relay
├── consumers     // audit + notification Kafka consumers
├── deposits      // Razorpay orders + webhook
├── payouts       // payout saga, provider client, recovery
├── recon         // reconciliation + InvariantChecker
├── ratelimit     // Redis rate limiter
└── chaos         // crash-point hooks (enabled only via config)
```

## NON-NEGOTIABLE INVARIANTS
These hold after every operation, every crash, and every chaos run. Any change that could break one must come with a test that proves it doesn't.

1. **Money is `long` paise.** Never `double`, `float`, or `BigDecimal` for stored amounts. DB columns are `BIGINT`. JSON fields end in `Paise` (`amountPaise`). Rupee formatting happens only for display.
2. **Every journal entry's postings sum to exactly 0** and every entry has at least 2 postings. A DEFERRABLE constraint trigger in Postgres enforces this, and the Java code checks it too.
3. **The sum of all postings across the whole ledger is 0.**
4. **Journal entries and postings are immutable.** DB triggers reject UPDATE and DELETE. Mistakes are fixed with a new reversing entry.
5. **`accounts.balance` is a cached projection.** Only `LedgerService.post(...)` changes it, in the same transaction as the postings. `InvariantChecker` proves `balance == SUM(postings)` for every account. No other code may write to `accounts.balance`.
6. **No account goes negative unless `allow_negative = true`.** Only `EXTERNAL_BANK` accounts allow it. Postgres enforces it with `CHECK (allow_negative OR balance >= 0)`, and the service checks it before writing.
7. **Lock accounts in ascending ID order** with `SELECT ... FOR UPDATE` before posting, so two opposite transfers can't deadlock.
8. **The same idempotency key never moves money twice.** Every money-moving entry carries a unique `idempotency_key`. API retries return the stored first response.
9. **Milestone state changes only through `MilestoneStateMachine`** using a conditional `UPDATE ... WHERE id = ? AND state = ?`. If 0 rows change, that's a conflict and nothing else in the transaction commits.
10. **Escrow conservation:** each project's ESCROW balance equals the sum of its milestones in `FUNDED`, `SUBMITTED`, or `APPROVED`. Its DISPUTE_HOLD balance equals the sum of its milestones in `DISPUTED`. PAYOUT_IN_FLIGHT equals the sum of payouts in `RESERVED`, `PROCESSING`, or `UNKNOWN`.
11. **DB and Kafka never disagree.** Domain events go into `outbox` in the same transaction as the state change and postings. Nothing calls `KafkaTemplate` except the outbox relay and the consumer dead-letter recoverer (`KafkaConsumerConfig`, which only re-publishes records a consumer could not process to `<topic>.DLT`; it never publishes domain events). `ArchitectureRulesTest` enforces this.
12. **Never retry a payout blindly.** A timeout means `UNKNOWN`. Only the recovery job, after a status check with the provider, decides what happens next.

## Account model
| type | code pattern | allow_negative | purpose |
|---|---|---|---|
| CLIENT_WALLET | `CLIENT_WALLET:{userId}` | no | client's spendable funds |
| FREELANCER_WALLET | `FREELANCER_WALLET:{userId}` | no | freelancer's earned funds |
| ESCROW | `ESCROW:{projectId}` | no | funded, not yet released |
| DISPUTE_HOLD | `DISPUTE_HOLD:{projectId}` | no | frozen by an open dispute |
| PAYOUT_IN_FLIGHT | `PAYOUT_IN_FLIGHT` | no | reserved for payouts in progress |
| PLATFORM_FEES | `PLATFORM_FEES` | no | platform revenue |
| EXTERNAL_BANK | `EXTERNAL_BANK:RAZORPAY`, `EXTERNAL_BANK:PAYOUTS` | **yes** | the outside world |

## Money flows (each one is exactly one journal entry)
| flow | postings | idempotency key |
|---|---|---|
| Deposit captured | EXTERNAL_BANK:RAZORPAY −A, CLIENT_WALLET +A | `deposit:rzp:{razorpayPaymentId}` |
| Fund project | CLIENT_WALLET −ΣA, ESCROW +ΣA | `fund:project:{projectId}` |
| Release (approve or auto) | ESCROW −A, FREELANCER_WALLET +(A−fee), PLATFORM_FEES +fee | `release:milestone:{milestoneId}` |
| Open dispute | ESCROW −A, DISPUTE_HOLD +A | `dispute-freeze:milestone:{milestoneId}` |
| Resolve dispute | DISPUTE_HOLD −A, FREELANCER_WALLET +(F−fee), PLATFORM_FEES +fee, CLIENT_WALLET +C (F+C=A) | `dispute-resolve:{disputeId}` |
| Refund after cancel | ESCROW −A, CLIENT_WALLET +A | `refund:milestone:{milestoneId}` |
| Payout reserve | FREELANCER_WALLET −A, PAYOUT_IN_FLIGHT +A | `payout:reserve:{payoutId}` |
| Payout confirm | PAYOUT_IN_FLIGHT −A, EXTERNAL_BANK:PAYOUTS +A | `payout:confirm:{payoutId}` |
| Payout reverse | PAYOUT_IN_FLIGHT −A, FREELANCER_WALLET +A | `payout:reverse:{payoutId}` |

Fee: `fee = amount * feeBps / 10_000` with integer floor division (`escrow.fee-bps`, default 500 = 5%). The freelancer gets `amount - fee`, so the split is always exact.

## Milestone state machine
```
DRAFT ──fund──▶ FUNDED ──submit──▶ SUBMITTED ──approve──▶ APPROVED ──release──▶ RELEASED
  │               │                    │         (auto-release after review window ─┘ via APPROVED)
  │               │                    └──dispute──▶ DISPUTED ──resolve──▶ RESOLVED
  │               └──cancel (after dueAt + grace)──▶ CANCELLED ──refund──▶ REFUNDED
  └──cancel──▶ CANCELLED (terminal, no money moved)
```
- Approve and release happen in one transaction. Both transitions are written to `milestone_transitions`.
- Either party may dispute while the milestone is `SUBMITTED`. Only an `ADMIN` resolves.
- Before `dueAt + grace`, the client cannot cancel a `FUNDED` milestone. This protects the freelancer.
- Cancel and refund happen in one transaction.

## Coding rules
- Constructor injection only. Records for DTOs and events. No Lombok.
- Every money-moving use case is one `@Transactional` service method. No `@Transactional` on controllers.
- Time always comes from an injected `java.time.Clock`. Never call `Instant.now()` directly, so tests can move time forward.
- Errors are RFC 9457 `ProblemDetail` with a stable `code`: `INSUFFICIENT_FUNDS`, `INVALID_STATE_TRANSITION`, `IDEMPOTENCY_KEY_REUSED`, `RATE_LIMITED`, `NOT_FOUND`, `UNAUTHENTICATED`, `FORBIDDEN`, `VALIDATION_FAILED`.
- Auth is a stub: `X-Actor-Id` and `X-Actor-Role` (`CLIENT|FREELANCER|ADMIN`) headers, checked in the service layer. Don't build real auth.
  - `ActorArgumentResolver` checks both against `users`: an unknown id, or a role that isn't the user's stored role, is `401 UNAUTHENTICATED` (so is a missing or malformed header). A wrong person or role for the action is `403 FORBIDDEN`.
  - **Bootstrap admin:** Flyway `V11` seeds user **id 1, `bootstrap-admin`, role `ADMIN`**. Creating users (`POST /api/v1/users`) is ADMIN-only, so id 1 creates everyone else. Send `X-Actor-Id: 1`, `X-Actor-Role: ADMIN`.
  - A user's id and role never change and users are never deleted (V11 triggers); the resolver's role cache relies on it.
- Money-moving POSTs need an `Idempotency-Key` header. If it's missing, return 400.
- Log with SLF4J, using structured key=value pairs. Never log secrets or full webhook bodies.
- Every new SQL migration is a new Flyway file. Never edit an applied migration.
- Integration tests use Testcontainers via `@ServiceConnection`. No H2 and no mocks for Postgres.
- Every chaos scenario asserts its fault actually happened and its race actually raced. A scenario
  that passes because the fault missed (no retries, no real kill, one side of a race always winning)
  proves nothing, so it must fail, and record the evidence in its result JSON (`violations` makes
  `run_all.py` report FAIL). Examples: C3 needs at least 20% of its calls to be retries; C6 needs
  each outcome at least 10% of milestones; K2 needs at least 3 kills that exited 137.
- Before any git commit, run git rev-parse --show-toplevel and confirm it is this project's folder. If not, stop.

## Commands
```
docker compose up -d                       # postgres, kafka, redis, toxiproxy, provider
./mvnw -pl escrow-service spring-boot:run
./mvnw verify                              # unit + integration
./mvnw verify -Pchaos                      # chaos suite (slow)
k6 run load/transfers.js
```

## Definition of done for any task
1. `./mvnw verify` is green.
2. New behaviour has tests, including at least one failure-path test.
3. `InvariantChecker` reports zero violations at the end of every integration test that moves money. The `@AfterEach` in `AbstractIntegrationTest` does this.
4. No invariant above was weakened to make a test pass. If one seems wrong, stop and explain instead of changing it.
