#!/usr/bin/env python3
"""
K2: kill -9 escrow-service again and again under load.

  1. fresh database, app started (real mock-payout-provider in SUCCESS mode, rate limiting off)
  2. load/mixed.js (k6 in its container) for 2 minutes with ACK_LOG=1: every 2xx is logged as
     "ACK <kind> <Idempotency-Key> [milestoneId]". Kills start once the measured scenario runs
     ("MAIN STARTED"), not during setup().
  3. kill loop: let the app take load for 15 s, `docker kill -s KILL` it (the exit code must be
     137), `docker start` it, wait until it is healthy again, repeat until k6 finishes
  4. pass: outbox drained, payouts settled, invariants ok, reconciliation for today (both sources)
     at 0 mismatches, and every acknowledged operation has exactly ONE journal entry:
       transfer   transfer:{key}
       approve    release:milestone:{milestoneId}
       dispute    dispute-freeze:milestone:{milestoneId}
       payout     payout:reserve:{payoutId}   (payoutId from payout_idempotency by key)
Writes chaos/results/K2-kill-under-load-<timestamp>.json; exits non-zero on any failure.

    python chaos/kill_under_load.py [--no-build]
"""
import os
import re
import subprocess
import sys
import threading
import time

from stack import (APP, ROOT, app_env, build_images, checks, container, container_state, drop_db,
                   ensure_infra, fresh_db, http, log, psql, sh, start_app, wait_until, write_result)

K6_IMAGE = os.environ.get("K6_IMAGE", "grafana/k6:0.54.0")
NETWORK = ROOT.name.lower() + "_escrow-network"
LOAD_SECONDS = 120
KILL_AFTER_SECONDS = 15
# k6 prints console.log as msg="ACK ...": the key stops at whitespace or the closing quote.
ACK = re.compile(r'ACK (transfer|approve|dispute|payout) ([^\s"]+)(?: (\d+))?')


def verify_acks(db, acks):
    """One violation per acknowledged operation that doesn't have exactly one journal entry."""
    tab, nl = chr(9), chr(10)
    rows = "".join(f"{kind}{tab}{key}{tab}{target or ''}{nl}" for kind, key, target in acks)
    # COPY's inline data and its "\." terminator must start at column 0.
    copy = "CREATE TEMP TABLE acks (kind text, key text, target text);\nCOPY acks FROM STDIN;\n" + rows + "\\.\n"
    sql = copy + """
        WITH expected AS (
            SELECT a.kind, a.key,
                   CASE a.kind
                       WHEN 'transfer' THEN 'transfer:' || a.key
                       WHEN 'approve'  THEN 'release:milestone:' || a.target
                       WHEN 'dispute'  THEN 'dispute-freeze:milestone:' || a.target
                       WHEN 'payout'   THEN 'payout:reserve:' || pi.payout_id
                   END AS ledger_key
            FROM acks a
            LEFT JOIN payout_idempotency pi ON a.kind = 'payout' AND pi.idempotency_key = a.key
        )
        SELECT e.kind || ' ' || e.key || ' -> ' || COALESCE(e.ledger_key, '(no payout)') || ' has '
               || (SELECT count(*) FROM journal_entries j WHERE j.idempotency_key = e.ledger_key) || ' journal entries'
        FROM expected e
        WHERE e.ledger_key IS NULL
           OR (SELECT count(*) FROM journal_entries j WHERE j.idempotency_key = e.ledger_key) <> 1;
    """
    res = subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "escrow", "-d", db, "-tA",
                          "-v", "ON_ERROR_STOP=1", "-f", "-"],
                         cwd=ROOT, input=sql, capture_output=True, text=True)
    if res.returncode != 0:
        return [f"ack verification failed: {res.stderr[-500:]}"]
    return [f"acknowledged {line}" for line in res.stdout.splitlines() if line.strip() and not line.startswith(("CREATE", "COPY"))]


def main(argv):
    ensure_infra()
    if "--no-build" not in argv:
        build_images()
    started = time.time()
    db = fresh_db("escrow_k2")
    violations, mismatches, notes = [], [], {}
    acks = []
    try:
        start_app(app_env(db))
        app = container("escrow-service")

        k6 = subprocess.Popen(
            ["docker", "run", "--rm", "--network", NETWORK, "-v", f"{ROOT / 'load'}:/load", "-w", "/", K6_IMAGE, "run",
             "-e", "BASE_URL=http://escrow-service:8080", "-e", "RESULTS_DIR=/tmp", "-e", "LABEL=k2",
             "-e", f"DURATION_SEC={LOAD_SECONDS}", "-e", "ACK_LOG=1",
             "-e", "CLIENTS=100", "-e", "FREELANCERS=20", "-e", "PROJECTS=60", "/load/mixed.js"],
            cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1,
            env=dict(os.environ, MSYS_NO_PATHCONV="1"))
        main_started = threading.Event()

        def read_output():
            for line in k6.stdout:
                if "MAIN STARTED" in line:
                    main_started.set()
                m = ACK.search(line)
                if m:
                    acks.append((m.group(1), m.group(2), m.group(3)))
        reader = threading.Thread(target=read_output, daemon=True)
        reader.start()

        if not main_started.wait(600):
            raise RuntimeError("k6 never reached the measured scenario")
        log("   k6 main scenario running; starting the kill loop")
        kills = []
        while k6.poll() is None:
            time.sleep(KILL_AFTER_SECONDS)
            if k6.poll() is not None:
                break
            sh(["docker", "kill", "-s", "KILL", app], check=False)
            dead = wait_until("the container to die", lambda: not container_state(app)[0], 30, interval=0.5)
            running, code = container_state(app)
            kills.append(code)
            log(f"   kill #{len(kills)}: exitCode={code}")
            if not dead or code != 137:
                violations.append(f"kill #{len(kills)}: expected exit code 137, got running={running} exitCode={code}")
            sh(["docker", "start", app])
            wait_until("escrow-service to come back", lambda: http("GET", APP + "/actuator/health", timeout=5)[1]["status"] == "UP", 240)
        reader.join(timeout=30)
        notes["kills"] = len(kills)
        notes["killExitCodes"] = sorted(set(kills))
        notes["k6ExitCode"] = k6.returncode
        if len(kills) < 3:
            violations.append(f"only {len(kills)} kills happened; the scenario proved little")

        violations_after, mismatches = checks(db)
        violations += violations_after

        # Every acknowledged operation: exactly one journal entry under its business key. Checked in
        # one query over all acks (loaded with COPY), not one psql round trip per ack.
        by_kind = {}
        for kind, _, _ in acks:
            by_kind[kind] = by_kind.get(kind, 0) + 1
        notes["acknowledged"] = by_kind
        if not acks:
            violations.append("no acknowledged operations were recorded")
        else:
            violations += verify_acks(db, acks)
        notes["payoutStates"] = psql(db, "SELECT string_agg(state || '=' || n, ' ') FROM (SELECT state, count(*) n FROM payouts GROUP BY state) s")
        result = write_result("K2-kill-under-load", started, len(acks), violations, mismatches, notes)
    except Exception as e:
        result = write_result("K2-kill-under-load", started, len(acks), violations + [f"scenario error: {e}"], mismatches, notes)
    finally:
        drop_db(db)
    return 0 if result["result"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
