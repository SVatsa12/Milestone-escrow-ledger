Every cell: median of 3 runs (min–max). CPU % is docker stats per core: the cap is 200%.

## Saturation (transfers, ramp 50 → 1000 req/s over 5 min)

| scenario | achieved rps | p50 ms | p95 ms | p99 ms | failed % (incl. dropped) | dropped iters | escrow CPU % avg / peak | postgres CPU % avg / peak | invariants ok | recon 0 mismatches |
|---|---|---|---|---|---|---|---|---|---|---|
| transfers-ramp | 481.4 (480.6–485.7) | 6.5 (5.9–7.5) | 1720.7 (1697.0–1775.1) | 2209.7 (2147.7–2308.8) | 8.31 (7.48–8.46) | 13085 (11786–13329) | 122 (116–122) / 187 (180–190) | 117 (117–119) / 212 (212–217) | 3/3 | 3/3 |

CPU % over the ramp's final minute (where it is saturated), avg: escrow-service 127 (126–127), postgres 192 (191–194) (cap 200 each).

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

## Tuning (transfers at 250 req/s constant, 3 min)

| config | achieved rps | p50 ms | p95 ms | p99 ms | failed % (incl. dropped) | dropped iters | escrow CPU % avg / peak | postgres CPU % avg / peak | invariants ok | recon 0 mismatches |
|---|---|---|---|---|---|---|---|---|---|---|
| pool 10, virtual threads | 250.0 | 4.1 (3.9–4.1) | 9.5 (9.3–12.3) | 51.5 (43.3–63.6) | 0.00 | 0 | 85 (81–88) / 200 (196–203) | 58 (53–61) / 149 (131–156) | 3/3 | 3/3 |
| pool 20, virtual threads | 250.0 | 4.0 (3.3–4.0) | 8.4 (6.8–10.6) | 36.5 (31.2–53.5) | 0.00 | 0 | 85 (73–88) / 200 | 53 (46–57) / 123 (108–138) | 3/3 | 3/3 |
| pool 40, virtual threads | 250.0 | 4.1 (3.9–4.3) | 9.5 (8.6–9.6) | 39.6 (27.4–110.0) | 0.00 | 0 | 85 (85–89) / 199 (196–204) | 58 (54–58) / 128 (126–177) | 3/3 | 3/3 |
| pool 10, platform threads | 250.0 | 3.4 (3.4–3.6) | 7.7 (7.1–7.8) | 54.0 (14.6–100.9) | 0.00 | 0 | 62 (59–64) / 203 (192–209) | 50 (48–50) / 118 (86–138) | 3/3 | 3/3 |
| pool 20, platform threads | 250.0 | 3.4 (3.1–3.9) | 7.7 (6.8–10.3) | 38.0 (30.0–77.2) | 0.00 | 0 | 60 (55–72) / 201 (199–203) | 50 (45–60) / 122 (121–140) | 3/3 | 3/3 |
| pool 40, platform threads | 250.0 | 3.7 (3.5–3.7) | 7.7 (7.6–8.2) | 24.6 (15.0–26.7) | 0.00 | 0 | 63 (62–64) / 199 (198–202) | 54 (52–54) / 155 (134–191) | 3/3 | 3/3 |

Payout states after each mixed run: CONFIRMED=943; CONFIRMED=944; CONFIRMED=947

replay_honoured in replays: 1.0000
