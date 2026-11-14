# Load tests and benchmarks (P15)

k6 scenarios against the docker compose `app` stack, with fixed resources:
escrow-service gets 2 CPUs and 1 GB, Postgres gets 2 CPUs. k6 runs in the
`grafana/k6:0.54.0` container on the compose network, so nothing needs installing locally.

```bash
load/run-bench.sh          # main benchmark: transfers, hot-account, replays, mixed
load/run-bench.sh knee     # transfers at constant 200..600 req/s to locate the knee
load/run-bench.sh tune     # Hikari pool 10/20/40 x virtual threads on/off, on transfers.js
python load/report.py load/results > load/results/REPORT.md
```

Each app start gets a brand-new database (`escrow_bench_<timestamp>`), so every
measurement starts from an empty ledger. An exit trap stops escrow-service and drops every
database the run created, whether it ends normally, fails or is interrupted. The results are
already on disk by then.

Each scenario waits until the outbox is empty before it starts. Each one is followed by
`GET /api/v1/admin/invariants`, saved as `<scenario>-<label>.invariants.json`.

| file | contents |
|---|---|
| `lib/setup.js` | `setup()`, through the APIs only: 500 client wallets funded by simulated deposits, 50 freelancers, 200 projects × 3 milestones funded and submitted. Shrink with `CLIENTS` / `FREELANCERS` / `PROJECTS`. |
| `transfers.js` | Uniform random transfers between the 500 wallets, ramping 50 → 1000 req/s over 5 min. Unique Idempotency-Key per request. |
| `hot-account.js` | Every transfer debits one shared wallet: 200 req/s for 2 min. |
| `replays.js` | 30% of requests resend an earlier key and body. They must come back as `Idempotent-Replayed: true` with the stored first response (`replay_honoured` must be 100%). |
| `mixed.js` | 60% reads, 20% transfers, 10% approve, 5% dispute, 5% payout at 150 req/s. Also K2's load generator (`ACK_LOG=1` logs every acknowledged key). |
| `report.py` | Turns `results/*.result.json` into the tables below. |

**Metrics:**
- `transfer_latency` (trend), `insufficient_funds` (counter) and `replayed` (counter).
- Numbers cover only the `main` scenario. setup() traffic is excluded, and achieved rps is
  requests ÷ scenario duration.
- `--summary-export` keeps the raw k6 summary next to each result.

**Expected statuses:** 409 (state conflict, e.g. approving an already-released milestone) and
422 (insufficient funds) are correct answers under load. k6 is told they're expected, so they
don't count toward `http_req_failed`.

## Hardware
- **CPU:** 12th Gen Intel Core i5-12500H (12 cores / 16 threads)
- **RAM:** 15.7 GB
- **OS:** Windows 11 with Docker Desktop (16 CPUs and 7.6 GB visible to Docker)
- **Container limits:** escrow-service `cpus=2, mem=1g`; Postgres `cpus=2`
- k6 ran on the same machine (unlimited container), so it competes for the host CPU too.

Raw output is in `results/HARDWARE.txt`.

## Benchmark (2026-11-14, Hikari 20, virtual threads on)

| scenario | target rps | achieved rps | p50 ms | p95 ms | p99 ms | error % | dropped iters | thresholds | invariants after |
|---|---|---|---|---|---|---|---|---|---|
| transfers | 50→1000 | 416.5 | 877.4 | 2437.2 | 3022.0 | 0.00 | 32543 | fail | 0 violations |
| hot-account | 200 | 199.1 | 80.1 | 653.2 | 1657.5 | 0.00 | 105 | fail | 0 violations |
| replays | 200 | 200 | 3.7 | 145.2 | 227.0 | 0.00 | 0 | pass | 0 violations |
| mixed | 150 | 150 | 1.4 | 13.8 | 74.0 | 0.00 | 0 | pass | 0 violations |
| transfers (knee-200) | 200 | 200 | 3.7 | 30.2 | 52.5 | 0.00 | 0 | pass | 0 violations |
| transfers (knee-300) | 300 | 297.8 | 15.8 | 514.6 | 1086.9 | 0.00 | 131 | fail | 0 violations |
| transfers (knee-400) | 400 | 375.2 | 158.9 | 1627.0 | 4053.3 | 0.00 | 1320 | fail | 0 violations |
| transfers (knee-500) | 500 | 262.9 | 1499.0 | 12538.0 | 16093.1 | 0.00 | 14201 | fail | 0 violations |
| transfers (knee-600) | 600 | 243.6 | 441.9 | 11398.3 | 18769.1 | 0.00 | 21384 | fail | 0 violations |

## Tuning experiment (transfers.js, ramp 50 → 800 req/s over 2 min)

| config | achieved rps | p50 ms | p95 ms | p99 ms | error % | dropped iters | invariants after |
|---|---|---|---|---|---|---|---|
| pool 10, platform threads | 411.3 | 80.8 | 1358.3 | 1700.7 | 0.00 | 1642 | 0 violations |
| pool 10, virtual threads | 417.4 | 245.3 | 1041.0 | 1470.9 | 0.00 | 912 | 0 violations |
| pool 20, platform threads | 388.0 | 49.5 | 1882.0 | 2468.8 | 0.00 | 4408 | 0 violations |
| pool 20, virtual threads | 361.5 | 313.5 | 2407.2 | 3373.0 | 0.00 | 7623 | 0 violations |
| pool 40, platform threads | 418.4 | 7.1 | 818.0 | 1386.3 | 0.00 | 796 | 0 violations |
| pool 40, virtual threads | 414.8 | 53.4 | 1085.7 | 1420.2 | 0.00 | 1229 | 0 violations |

## Interpretation
Transfers stay fast up to about **200–250 req/s** (p95 30ms at 200), and that is the
sustainable rate. Past it, latency climbs steeply (p95 515ms at 300). Throughput peaks at
**about 375–420 req/s**, and beyond that it *falls* (263 at 500, 244 at 600) as queued requests
pile up.

Every pool size and thread model lands in the same ~360–420 req/s band, so neither the
connection pool nor platform-vs-virtual threads is the limit. That points at CPU on the 2-CPU
containers: request handling, the outbox relay and the Kafka producer share the app's two
cores, and Postgres has two for commits. This is inferred, not measured; CPU wasn't profiled.
Hot-account is a different story. Its throughput holds at 200 req/s, but every transfer
serialises on one wallet's `FOR UPDATE` row lock, so the median stays low while the tail
grows. That is lock contention, not saturation.

Throughout, error rate was 0% and every run ended with 0 invariant violations, including the
collapse at 500–600 req/s. Overload makes the service slow, never wrong.

**Caveats:**
- **Single runs.** Pool 20 came out worst in both thread modes, but it's the default config
  and was also used in the main run at 416 req/s. Treat ±10% as noise.
- **Hot-account in this run.** It ran right after the transfers ramp, before the drain-wait
  existed, so it shared CPU with the relay clearing that backlog. The run before the relay fix
  (below) had p50 6.3ms / p95 174ms. Re-run it on its own for a clean number.
- **Knee steps share one database.** Each step starts with an empty outbox but a larger ledger.
- **Rate limiting is off** for these runs (`RATELIMIT_ENABLED=false`). One admin actor drives
  every request, and the 30 req/min limit would turn the benchmark into a 429 test.
- **Payouts in `mixed.js`.** They go to `mock-payout-provider`, which has no endpoints yet, so
  every payout gets a 4xx and is correctly reversed (FAILED). Their latency includes that call.

## Thresholds (tuned after the first run)
- `http_req_failed{scenario:main} < 1%`. Actual: 0% in every run.
- `transfer_latency p(95) < 150ms`. This bar applies at the sustainable rate, about 200 req/s,
  where p95 was 30ms. `transfers.js` and the 300+ knee steps deliberately go past the knee, so
  they're expected to fail it. Replays (145ms) and mixed (14ms) pass. Hot-account fails because
  of lock contention, which is the point of that scenario.
- `replay_honoured == 100%` in `replays.js`: every resend returned the stored first response.

## Before vs after the outbox relay fix
The first full run, kept in `results/before-relay-fix/`, ended with 28,778 and then 42,496
outbox rows unpublished for more than 5 minutes (`I9_WARN`). The relay sent one event at a time
and topped out around 140 events/s. With batched async sends it drains about 1,700 events/s
(`OutboxRelayThroughputIT`) and the warning is gone. The cost is a little request throughput:
the relay now does its work during the run instead of leaving a backlog for later.

| scenario | achieved rps before → after | p95 ms before → after | invariants after (before → after) |
|---|---|---|---|
| transfers | 443.3 → 416.5 | 2283 → 2437 | 0 → 0 |
| replays | 200 → 200 | 99.5 → 145.2 | **1 (outbox backlog)** → 0 |
| mixed | 150 → 150 | 17.3 → 13.8 | **1 (outbox backlog)** → 0 |
