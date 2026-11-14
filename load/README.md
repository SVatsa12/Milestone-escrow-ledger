# Load tests and benchmarks

k6 scenarios against the docker compose `app` stack (escrow-service, the real
`mock-payout-provider` in SUCCESS mode, Postgres, Kafka, Redis) with fixed resources:
escrow-service 2 CPUs and 1 GB, Postgres 2 CPUs. k6 runs in the `grafana/k6:0.54.0` container on
the compose network, so nothing needs installing locally.

```bash
python load/bench.py                    # every scenario, 3 runs each (about 3.5 hours)
python load/bench.py saturation mixed   # only some groups: saturation sustainable hot-account replays mixed tuning
python load/bench_report.py             # results/v2/runs/*.json -> results/v2/summary.json + REPORT.md
BENCH_SMOKE=1 python load/bench.py      # 20 s pipeline check, results not reported
```

`run-bench.sh` is the older single-run harness that produced the 2026-11-14 numbers (see the end).

## Method

Every run is independent:
1. A fresh escrow database (`escrow_bench_<timestamp>`, dropped afterwards), escrow-service started
   with the run's Hikari pool and thread model, rate limiting off, and `mock-payout-provider` reset
   (no payouts) in SUCCESS mode. `setup()` uses the bootstrap ADMIN (id 1) to create 500 clients,
   50 freelancers and 200 projects × 3 milestones.
2. A 30 s warm-up (`mixed.js`, small data set, discarded).
3. The measured run, with `docker stats` sampled every ~2 s for escrow-service and Postgres.
4. Checks: wait for the outbox to drain and in-flight payouts to settle, then
   `GET /api/v1/admin/invariants` must report 0 violations, and reconciliation for today (IST) with
   both sources (`PAYOUT_PROVIDER`, `RAZORPAY`) must report 0 mismatches.

Every scenario runs **3 times**; each cell is the **median (min–max)**.
- **failed % counts dropped iterations as failures:** (failed requests + dropped) / (requests +
  dropped). k6 drops an iteration when it can't start a request the target rate asked for, so a
  row with drops is not "0% errors".
- **CPU %** is docker's per-core figure: the cap is **200%** for both containers. avg / peak are over
  the measured scenario only (not setup).
- **Expected statuses:** 409 (state conflict) and 422 (insufficient funds) are correct answers under
  load, so they don't count as failures.
- **Committed:** per-run JSON in `results/v2/runs/`, `results/v2/summary.json`,
  `results/v2/REPORT.md` and `results/v2/HARDWARE.txt`. Raw k6 output, stats samples and check
  responses stay in `results/raw/` (gitignored).

| file | contents |
|---|---|
| `lib/setup.js` | `setup()` through the APIs only. Shrink with `CLIENTS` / `FREELANCERS` / `PROJECTS`. |
| `transfers.js` | Uniform random transfers between the 500 wallets. Default: ramp 50 → 1000 req/s over 5 min ("saturation"). `RATE=250`: constant arrival rate ("sustainable"). |
| `hot-account.js` | Every transfer debits one shared wallet: 200 req/s for 2 min. |
| `replays.js` | 30% of requests resend an earlier key and body; `replay_honoured` must be 100%. |
| `mixed.js` | 60% reads, 20% transfers, 10% approve, 5% dispute, 5% payout (against the real provider) at 150 req/s. |

## Hardware
- **CPU:** 12th Gen Intel Core i5-12500H (12 cores / 16 threads)
- **RAM:** 15.7 GB
- **OS:** Windows 11 with Docker Desktop 28.4 (16 CPUs and 7.6 GB visible to Docker)
- **Container limits:** escrow-service `cpus=2, mem=1g`; Postgres `cpus=2`
- k6 ran on the same machine (unlimited container), so it competes for the host CPU too.

## Saturation (transfers, ramp 50 → 1000 req/s over 5 min, 2026-11-15)

| scenario | achieved rps | p50 ms | p95 ms | p99 ms | failed % (incl. dropped) | dropped iters | escrow CPU % avg / peak | postgres CPU % avg / peak | invariants ok | recon 0 mismatches |
|---|---|---|---|---|---|---|---|---|---|---|
| transfers ramp | 481.4 (480.6–485.7) | 6.5 (5.9–7.5) | 1720.7 (1697.0–1775.1) | 2209.7 (2147.7–2308.8) | 8.31 (7.48–8.46) | 13085 (11786–13329) | 122 (116–122) / 187 (180–190) | 117 (117–119) / 212 (212–217) | 3/3 | 3/3 |

Over the ramp's final minute, where it is saturated, average CPU was **escrow-service 127%
(126–127)** and **Postgres 192% (191–194)** of the 200% cap. The p50 stays low because most of the
ramp is below the knee; p95/p99 and the drops come from the saturated end.

## Sustainable (transfers, constant arrival rate, 3 min)

| rate | achieved rps | p50 ms | p95 ms | p99 ms | failed % (incl. dropped) | dropped iters | escrow CPU % avg / peak | postgres CPU % avg / peak | invariants ok | recon 0 mismatches |
|---|---|---|---|---|---|---|---|---|---|---|
| 200 req/s | 200.0 | 3.8 (3.3–4.3) | 7.4 (6.8–8.1) | 13.8 (13.1–17.1) | 0.00 | 0 | 71 (60–84) / 194 (177–203) | 46 (37–51) / 96 (96–155) | 3/3 | 3/3 |
| 250 req/s | 250.0 | 3.8 (3.5–4.0) | 7.8 (7.7–9.0) | 28.2 (21.7–37.0) | 0.00 | 0 | 84 (75–87) / 201 (198–202) | 53 (48–59) / 149 (138–161) | 3/3 | 3/3 |

## Other scenarios

| scenario | achieved rps | p50 ms | p95 ms | p99 ms | failed % (incl. dropped) | dropped iters | escrow CPU % avg / peak | postgres CPU % avg / peak | invariants ok | recon 0 mismatches |
|---|---|---|---|---|---|---|---|---|---|---|
| hot-account 200 req/s | 200.0 | 3.6 (3.6–3.9) | 19.4 (18.0–20.0) | 195.5 (183.2–309.3) | 0.00 | 0 | 67 (64–69) / 198 (197–202) | 44 (38–46) / 109 (96–164) | 3/3 | 3/3 |
| replays 200 req/s | 200.0 | 3.5 (3.1–3.6) | 7.4 (6.2–8.6) | 22.7 (17.2–24.7) | 0.00 | 0 | 61 (56–68) / 193 (189–197) | 33 (28–36) / 97 (84–110) | 3/3 | 3/3 |
| mixed 150 req/s | 150.0 | 1.7 (1.6–1.8) | 12.9 (12.6–15.0) | 22.8 (20.9–27.4) | 0.00 | 0 | 50 (47–55) / 196 (195–197) | 19 (18–21) / 52 (51–66) | 3/3 | 3/3 |

- **replays:** `replay_honoured` was 1.0 in every run (7,102–7,123 resends, 0 misses).
- **mixed:** payouts now go to the real provider and CONFIRM. Payout states after the three runs:
  CONFIRMED=943, 944, 947, with none FAILED, UNKNOWN or still in flight.
- **hot-account:** the tail (p99 ~195ms) is the `FOR UPDATE` row lock on one wallet; neither
  container is near its cap.

## Tuning (transfers at 250 req/s constant, 3 min, median of 3)

| config | achieved rps | p50 ms | p95 ms | p99 ms | failed % (incl. dropped) | dropped iters | escrow CPU % avg / peak | postgres CPU % avg / peak | invariants ok | recon 0 mismatches |
|---|---|---|---|---|---|---|---|---|---|---|
| pool 10, virtual threads | 250.0 | 4.1 (3.9–4.1) | 9.5 (9.3–12.3) | 51.5 (43.3–63.6) | 0.00 | 0 | 85 (81–88) / 200 (196–203) | 58 (53–61) / 149 (131–156) | 3/3 | 3/3 |
| pool 20, virtual threads | 250.0 | 4.0 (3.3–4.0) | 8.4 (6.8–10.6) | 36.5 (31.2–53.5) | 0.00 | 0 | 85 (73–88) / 200 | 53 (46–57) / 123 (108–138) | 3/3 | 3/3 |
| pool 40, virtual threads | 250.0 | 4.1 (3.9–4.3) | 9.5 (8.6–9.6) | 39.6 (27.4–110.0) | 0.00 | 0 | 85 (85–89) / 199 (196–204) | 58 (54–58) / 128 (126–177) | 3/3 | 3/3 |
| pool 10, platform threads | 250.0 | 3.4 (3.4–3.6) | 7.7 (7.1–7.8) | 54.0 (14.6–100.9) | 0.00 | 0 | 62 (59–64) / 203 (192–209) | 50 (48–50) / 118 (86–138) | 3/3 | 3/3 |
| pool 20, platform threads | 250.0 | 3.4 (3.1–3.9) | 7.7 (6.8–10.3) | 38.0 (30.0–77.2) | 0.00 | 0 | 60 (55–72) / 201 (199–203) | 50 (45–60) / 122 (121–140) | 3/3 | 3/3 |
| pool 40, platform threads | 250.0 | 3.7 (3.5–3.7) | 7.7 (7.6–8.2) | 24.6 (15.0–26.7) | 0.00 | 0 | 63 (62–64) / 199 (198–202) | 54 (52–54) / 155 (134–191) | 3/3 | 3/3 |

## Interpretation
Transfers are sustainable at **250 req/s** (p95 7.8ms, p99 28ms, nothing dropped, 3 of 3 runs), and
saturation tops out around **480 req/s**, where about 8% of the requested load is dropped. At
saturation **Postgres is the bottleneck**, at ~192% of its 200% CPU cap over the final minute against
~127% for escrow-service, so the earlier "CPU-bound app" reading was wrong: the app has headroom and
the database's two cores are what run out. At 250 req/s the Hikari pool size makes no
measurable difference, and platform threads match virtual threads on latency while using about a
quarter less escrow-service CPU (60–63% vs 85%).

**Caveats:**
- **Peaks are short.** Every scenario touches ~200% escrow CPU for a sample or two (GC, JIT, the
  relay draining a burst); the averages are what describe the load.
- **Shared host.** k6 runs on the same machine, unlimited, and competes for the host's CPU.
- **Rate limiting is off** (`RATELIMIT_ENABLED=false`): one admin actor drives every transfer, and
  the 30 req/min limit would turn this into a 429 test.
- **Reconciliation covers the warm-up too.** It reconciles today with both sources, so the warm-up's
  payouts are included; they also had to match.

## Previous results (2026-11-14, single runs, `run-bench.sh`)
Single runs with no CPU measurement, kept in `results/*.json` for history. They found the same
order of magnitude (transfers ramp 416 req/s, knee around 200–300 req/s). They also called the
system "CPU-bound" without data, and counted runs with dropped iterations as 0% errors. In those
runs `mock-payout-provider` had no endpoints yet, so every payout in `mixed.js` failed.

The outbox relay fix (chaos/FINDINGS.md #14) is visible there: before it, 28,778 and then 42,496
outbox rows were unpublished for over 5 minutes after a run (`I9_WARN`); after it, none.
