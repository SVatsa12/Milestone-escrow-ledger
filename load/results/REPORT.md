## Benchmark

| scenario | target rps | achieved rps | p50 ms | p95 ms | p99 ms | error % | dropped iters | thresholds | invariants after |
|---|---|---|---|---|---|---|---|---|---|
| transfers | 50->1000 | 416.5 | 877.4 | 2437.2 | 3022.0 | 0.00 | 32543 | fail | 0 violations |
| hot-account | 200 | 199.1 | 80.1 | 653.2 | 1657.5 | 0.00 | 105 | fail | 0 violations |
| replays | 200 | 200 | 3.7 | 145.2 | 227.0 | 0.00 | 0 | pass | 0 violations |
| mixed | 150 | 150 | 1.4 | 13.8 | 74.0 | 0.00 | 0 | pass | 0 violations |
| transfers (knee-200) | 200->200 | 200 | 3.7 | 30.2 | 52.5 | 0.00 | 0 | pass | 0 violations |
| transfers (knee-300) | 300->300 | 297.8 | 15.8 | 514.6 | 1086.9 | 0.00 | 131 | fail | 0 violations |
| transfers (knee-400) | 400->400 | 375.2 | 158.9 | 1627.0 | 4053.3 | 0.00 | 1320 | fail | 0 violations |
| transfers (knee-500) | 500->500 | 262.9 | 1499.0 | 12538.0 | 16093.1 | 0.00 | 14201 | fail | 0 violations |
| transfers (knee-600) | 600->600 | 243.6 | 441.9 | 11398.3 | 18769.1 | 0.00 | 21384 | fail | 0 violations |

## Tuning experiment (transfers.js)

| config | target rps | achieved rps | p50 ms | p95 ms | p99 ms | error % | dropped iters | invariants after |
|---|---|---|---|---|---|---|---|---|
| pool10-vtfalse | 50->800 | 411.3 | 80.8 | 1358.3 | 1700.7 | 0.00 | 1642 | 0 violations |
| pool10-vttrue | 50->800 | 417.4 | 245.3 | 1041.0 | 1470.9 | 0.00 | 912 | 0 violations |
| pool20-vtfalse | 50->800 | 388 | 49.5 | 1882.0 | 2468.8 | 0.00 | 4408 | 0 violations |
| pool20-vttrue | 50->800 | 361.5 | 313.5 | 2407.2 | 3373.0 | 0.00 | 7623 | 0 violations |
| pool40-vtfalse | 50->800 | 418.4 | 7.1 | 818.0 | 1386.3 | 0.00 | 796 | 0 violations |
| pool40-vttrue | 50->800 | 414.8 | 53.4 | 1085.7 | 1420.2 | 0.00 | 1229 | 0 violations |
