"""
Shared helpers for the process-kill scenarios (K1, K2) and run_all.py: drive the docker compose
`app` stack the same way load/bench.py does, so everything runs on Windows and Linux (CI) alike.
"""
import datetime as dt
import json
import os
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESULTS = ROOT / "chaos" / "results"
APP = "http://localhost:8080"
PROVIDER = "http://localhost:9090"
ADMIN = {"X-Actor-Id": "1", "X-Actor-Role": "ADMIN"}   # bootstrap ADMIN (Flyway V11)
IST = dt.timezone(dt.timedelta(hours=5, minutes=30))


def log(msg):
    print(f"[{dt.datetime.now():%H:%M:%S}] {msg}", flush=True)


def sh(args, check=True, env=None, timeout=None):
    res = subprocess.run(args, cwd=ROOT, env=env, capture_output=True, text=True, timeout=timeout)
    if check and res.returncode != 0:
        raise RuntimeError(f"{' '.join(map(str, args))} failed ({res.returncode}): {res.stderr[-2000:]}")
    return res


def compose(*args, env=None, check=True):
    return sh(["docker", "compose", *args], env=env, check=check)


def psql(db, sql):
    return compose("exec", "-T", "postgres", "psql", "-U", "escrow", "-d", db, "-tAc", sql).stdout.strip()


def http(method, url, headers=None, body=None, timeout=60):
    """(status, parsed JSON or text). Connection failures raise."""
    data = None if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode())
    h = {"Content-Type": "application/json", **(headers or {})}
    if method == "POST" and data is None:
        data = b""
    req = urllib.request.Request(url, method=method, headers=h, data=data)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode()
            status = r.status
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        status = e.code
    try:
        return status, json.loads(raw) if raw else None
    except json.JSONDecodeError:
        return status, raw


def wait_until(what, check, seconds, interval=2):
    end = time.time() + seconds
    while time.time() < end:
        try:
            if check():
                return True
        except Exception:
            pass
        time.sleep(interval)
    log(f"   timed out waiting for {what}")
    return False


def ensure_infra():
    compose("up", "-d", "postgres", "zookeeper", "kafka", "kafka-init", "redis")


def build_images():
    log("building escrow-service and mock-payout-provider images")
    compose("--profile", "app", "build", "escrow-service", "mock-payout-provider")


def fresh_db(prefix):
    db = f"{prefix}_" + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d%H%M%S")
    psql("escrow", f"CREATE DATABASE {db}")
    return db


def drop_db(db):
    compose("--profile", "app", "stop", "escrow-service", check=False)
    psql("escrow", f'DROP DATABASE IF EXISTS "{db}" WITH (FORCE)')


def app_env(db, profiles="", crash_point="NONE"):
    return dict(os.environ, ESCROW_DB=db, RATELIMIT_ENABLED="false",
                SPRING_PROFILES_ACTIVE=profiles, CHAOS_CRASH_POINT=crash_point)


def start_app(env, reset_provider=True):
    compose("--profile", "app", "up", "-d", "--no-build", "--force-recreate", "escrow-service", "mock-payout-provider", env=env)
    wait_healthy()
    if reset_provider:
        http("POST", PROVIDER + "/admin/reset")
        http("POST", PROVIDER + "/admin/mode", body={"mode": "SUCCESS", "seed": 1})


def wait_healthy(seconds=240):
    ok = wait_until("escrow-service health", lambda: http("GET", APP + "/actuator/health")[1]["status"] == "UP", seconds)
    ok = ok and wait_until("provider health", lambda: http("GET", PROVIDER + "/actuator/health")[1]["status"] == "UP", 120)
    if not ok:
        print(compose("logs", "--tail", "80", "escrow-service", check=False).stdout)
        raise RuntimeError("app did not become healthy")


def container(service):
    for line in compose("ps", "-a", "--format", "{{.Service}}\t{{.Name}}").stdout.splitlines():
        svc, name = line.split("\t")
        if svc == service:
            return name
    raise RuntimeError(f"no container for {service}")


def container_state(name):
    out = sh(["docker", "inspect", "-f", "{{.State.Running}} {{.State.ExitCode}}", name]).stdout.split()
    return out[0] == "true", int(out[1])


def checks(db):
    """After a scenario: outbox drained, payouts settled, invariants, reconciliation for today."""
    drained = wait_until("outbox to drain", lambda: psql(db, "SELECT count(*) FROM outbox WHERE published_at IS NULL") == "0", 300)
    settled = wait_until("payouts to settle", lambda: psql(
        db, "SELECT count(*) FROM payouts WHERE state IN ('RESERVED','PROCESSING','UNKNOWN')") == "0", 300, interval=5)
    _, inv = http("GET", APP + "/api/v1/admin/invariants", ADMIN, timeout=300)
    violations = [f"{v['code']}: {v['detail']}" for v in inv["violations"]]
    if not drained:
        violations.append("outbox did not drain within 5 minutes")
    if not settled:
        violations.append("payouts still in flight after 5 minutes: "
                          + psql(db, "SELECT string_agg(id || '=' || state, ',') FROM payouts WHERE state IN ('RESERVED','PROCESSING','UNKNOWN')"))
    today = dt.datetime.now(IST).date().isoformat()
    http("POST", APP + f"/api/v1/admin/recon/run?date={today}", ADMIN, timeout=600)
    _, runs = http("GET", APP + f"/api/v1/admin/recon/runs?date={today}", ADMIN)
    mismatches = []
    for run in runs["runs"]:
        if run["status"] != "COMPLETED":
            mismatches.append(f"{run['entity_type']} recon run {run['status']}: {run.get('notes')}")
        if run["mismatch_count"]:
            _, mm = http("GET", APP + f"/api/v1/admin/recon/runs/{run['id']}/mismatches", ADMIN)
            for m in mm["mismatches"]:
                mismatches.append(f"{run['entity_type']} {m['mismatch_reason']} {m['entity_id']}")
    if len(runs["runs"]) != 2:
        mismatches.append(f"expected 2 recon runs for {today}, got {len(runs['runs'])}")
    return violations, mismatches


def write_result(scenario, started, operations, violations, mismatches, notes):
    RESULTS.mkdir(parents=True, exist_ok=True)
    stamp = dt.datetime.fromtimestamp(started, dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    result = {
        "scenario": scenario,
        "startedAt": dt.datetime.fromtimestamp(started, dt.timezone.utc).isoformat(),
        "durationMs": int((time.time() - started) * 1000),
        "operations": operations,
        "violations": len(violations), "violationDetails": violations,
        "mismatches": len(mismatches), "mismatchDetails": mismatches,
        "result": "PASS" if not violations and not mismatches else "FAIL",
        "notes": notes,
    }
    path = RESULTS / f"{scenario}-{stamp}.json"
    path.write_text(json.dumps(result, indent=2))
    log(f"   {scenario}: {result['result']} operations={operations} violations={len(violations)} "
        f"mismatches={len(mismatches)} -> {path.name}")
    return result
