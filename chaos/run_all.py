#!/usr/bin/env python3
"""
Runs the whole chaos suite and prints one table:

    scenario | operations | violations | mismatches | result

  Part A: ./mvnw -B verify -Pchaos (C1-C6, JUnit + Testcontainers + Toxiproxy); each scenario writes
          chaos/results/C<n>-...json, which is where this script reads its row from
  Part B: chaos/kill_mid_saga.py (K1, both crash points) and chaos/kill_under_load.py (K2) against
          the docker compose app stack

A scenario passes only with 0 violations AND 0 reconciliation mismatches. Exits non-zero if any
scenario failed or didn't report.

    python chaos/run_all.py
"""
import json
import subprocess
import sys
import time
from pathlib import Path

from stack import ROOT, RESULTS, build_images, ensure_infra, log

EXPECTED_JUNIT = ["C1-provider-latency", "C2-provider-connection-reset", "C3-db-latency-and-drops",
                  "C4-duplicate-webhooks", "C5-kafka-outage", "C6-state-machine-race"]
EXPECTED_KILL = ["K1-kill-mid-saga-AFTER_RESERVE", "K1-kill-mid-saga-AFTER_PROVIDER_CALL", "K2-kill-under-load"]


def results_since(started):
    """The newest result file per scenario written after `started`."""
    latest = {}
    for f in RESULTS.glob("*.json"):
        if f.stat().st_mtime < started:
            continue
        r = json.loads(f.read_text())
        prev = latest.get(r["scenario"])
        if prev is None or f.stat().st_mtime > prev[0]:
            latest[r["scenario"]] = (f.stat().st_mtime, r, f.name)
    return {k: (v[1], v[2]) for k, v in latest.items()}


def main():
    started = time.time()
    mvnw = str(ROOT / ("mvnw.cmd" if sys.platform == "win32" else "mvnw"))
    log("== Part A: ./mvnw -B verify -Pchaos")
    log_path = ROOT / "chaos" / "results" / "raw-junit.log"
    with open(log_path, "w") as out:
        junit = subprocess.run([mvnw, "-B", "verify", "-Pchaos"], cwd=ROOT, stdout=out, stderr=subprocess.STDOUT)
    log(f"   maven exit code {junit.returncode} (log: {log_path.relative_to(ROOT)})")

    log("== Part B: K1, K2")
    ensure_infra()
    build_images()
    for script in ("kill_mid_saga.py", "kill_under_load.py"):
        subprocess.run([sys.executable, str(ROOT / "chaos" / script), "--no-build"], cwd=ROOT / "chaos")

    found = results_since(started)
    rows, ok = [], junit.returncode == 0
    for name in EXPECTED_JUNIT + EXPECTED_KILL:
        if name not in found:
            rows.append((name, "-", "-", "-", "MISSING"))
            ok = False
            continue
        r, _ = found[name]
        rows.append((name, r["operations"], r["violations"], r.get("mismatches", "-"), r["result"]))
        ok = ok and r["result"] == "PASS"

    widths = [max(len(str(x)) for x in col) for col in zip(("scenario", "operations", "violations", "mismatches", "result"), *rows)]
    line = lambda cells: " | ".join(str(c).ljust(w) for c, w in zip(cells, widths))
    print()
    print(line(("scenario", "operations", "violations", "mismatches", "result")))
    print("-+-".join("-" * w for w in widths))
    for row in rows:
        print(line(row))
    print(f"\nOVERALL: {'PASS' if ok else 'FAIL'}"
          + ("" if junit.returncode == 0 else f" (maven exited {junit.returncode})"), flush=True)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
