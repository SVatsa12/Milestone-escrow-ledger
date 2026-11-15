#!/usr/bin/env python3
"""
K1: kill escrow-service mid-saga, for real.

For each crash point (AFTER_RESERVE, AFTER_PROVIDER_CALL):
  1. fresh database, escrow-service started with SPRING_PROFILES_ACTIVE=chaos and
     CHAOS_CRASH_POINT=<point>; mock-payout-provider reset, SUCCESS mode
  2. a freelancer with funds requests a payout; the JVM halts at the crash point, and the container
     must actually be dead with exit code 137 (docker inspect)
  3. restart with the crash point off; wait (<= 5 min) until no payout is RESERVED / PROCESSING /
     UNKNOWN: the recovery job, not a retry, decides
  4. pass: invariants ok; the payout agrees with the provider (CONFIRMED <-> SUCCESS,
     CANCELLED <-> CANCELLED tombstone, FAILED <-> FAILED); reconciliation for today with both
     sources at 0 mismatches
Writes chaos/results/K1-kill-mid-saga-<point>-<timestamp>.json; exits non-zero on any failure.

    python chaos/kill_mid_saga.py [AFTER_RESERVE|AFTER_PROVIDER_CALL ...] [--no-build]
"""
import sys
import time
import uuid

from stack import (ADMIN, APP, PROVIDER, app_env, build_images, checks, container, container_state,
                   drop_db, ensure_infra, fresh_db, http, log, psql, start_app, wait_until, write_result)

EXPECTED = {
    # Crashed after T1, before the provider was ever called: the provider never sees the key, the
    # recovery job tombstones it after 2 minutes and reverses the reservation.
    "AFTER_RESERVE": ("CANCELLED", "CANCELLED"),
    # Crashed after the provider paid, before T2: recovery asks the provider and confirms.
    "AFTER_PROVIDER_CALL": ("CONFIRMED", "SUCCESS"),
}
AMOUNT = 5_000


def run(point):
    started = time.time()
    log(f"== K1 {point}")
    db = fresh_db("escrow_k1")
    violations, notes = [], {"crashPoint": point}
    try:
        start_app(app_env(db, profiles="chaos", crash_point=point))
        app = container("escrow-service")

        # A freelancer with money: created by the bootstrap admin, funded with a simulated deposit.
        _, user = http("POST", APP + "/api/escrow/users", ADMIN, {"name": f"k1-{uuid.uuid4().hex[:8]}", "role": "FREELANCER"})
        freelancer = user["id"]
        wallet = int(psql(db, f"SELECT id FROM accounts WHERE code = 'FREELANCER_WALLET:{freelancer}'"))
        status, _ = http("POST", APP + "/api/v1/admin/deposits/simulate", {**ADMIN, "Idempotency-Key": f"k1-seed-{uuid.uuid4()}"},
                         {"clientAccountId": wallet, "amountPaise": 100_000})
        assert status == 201, f"seed deposit -> {status}"

        # The payout: the JVM halts mid-saga, so this request never gets an answer.
        actor = {"X-Actor-Id": str(freelancer), "X-Actor-Role": "FREELANCER", "Idempotency-Key": f"k1-{uuid.uuid4()}"}
        try:
            status, body = http("POST", APP + "/api/v1/payouts", actor, {"amountPaise": AMOUNT}, timeout=30)
            violations.append(f"the payout request was answered ({status} {body}): the crash point didn't fire")
        except Exception as e:
            notes["clientSaw"] = type(e).__name__
        dead = wait_until("the container to die", lambda: not container_state(app)[0], 60, interval=1)
        running, exit_code = container_state(app)
        notes["containerExitCode"] = exit_code
        log(f"   container {app} running={running} exitCode={exit_code}")
        if not dead or exit_code != 137:
            violations.append(f"expected the container to be dead with exit code 137, got running={running} exitCode={exit_code}")

        payout_id = psql(db, "SELECT id FROM payouts ORDER BY created_at DESC LIMIT 1")
        notes["payoutId"] = payout_id
        notes["stateAtCrash"] = psql(db, f"SELECT state FROM payouts WHERE id = '{payout_id}'") if payout_id else None
        notes["providerAtCrash"] = http("GET", PROVIDER + f"/v1/payouts/by-key/{payout_id}")[1] if payout_id else None

        # Restart with the crash point off; only the recovery job may finish the saga.
        restarted = time.time()
        start_app(app_env(db, profiles="chaos", crash_point="NONE"), reset_provider=False)
        inv_violations, mismatches = checks(db)
        violations += inv_violations
        notes["recoverySeconds"] = round(time.time() - restarted)

        if not payout_id:
            violations.append("no payout row: the reservation was lost")
        else:
            state = psql(db, f"SELECT state FROM payouts WHERE id = '{payout_id}'")
            pstatus, precord = http("GET", PROVIDER + f"/v1/payouts/by-key/{payout_id}")
            provider_state = precord.get("status") if pstatus == 200 else "NOT_FOUND"
            notes["finalState"], notes["providerFinal"] = state, provider_state
            expected_state, expected_provider = EXPECTED[point]
            if (state, provider_state) != (expected_state, expected_provider):
                violations.append(f"payout {payout_id} is {state} / provider {provider_state}, "
                                  f"expected {expected_state} / {expected_provider}")
            entries = psql(db, f"SELECT string_agg(type, ',' ORDER BY id) FROM journal_entries WHERE reference_id = '{payout_id}'")
            notes["journalEntries"] = entries
            balance = int(psql(db, f"SELECT balance FROM accounts WHERE id = {wallet}"))
            expected_balance = 100_000 - (AMOUNT if expected_state == "CONFIRMED" else 0)
            if balance != expected_balance:
                violations.append(f"freelancer wallet {balance}, expected {expected_balance}")
        return write_result(f"K1-kill-mid-saga-{point}", started, 1, violations, mismatches, notes)
    except Exception as e:
        return write_result(f"K1-kill-mid-saga-{point}", started, 1, violations + [f"scenario error: {e}"], [], notes)
    finally:
        drop_db(db)


def main(argv):
    points = [a for a in argv if not a.startswith("--")] or list(EXPECTED)
    ensure_infra()
    if "--no-build" not in argv:
        build_images()
    results = [run(p) for p in points]
    return 0 if all(r["result"] == "PASS" for r in results) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
