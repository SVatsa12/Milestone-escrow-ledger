#!/usr/bin/env python3
"""
Sustainable-rate benchmark with CPU evidence (supersedes run-bench.sh's single runs).

Every run is independent:
  1. fresh escrow database, app (re)started with the run's Hikari pool / thread model,
     mock-payout-provider reset (no payouts) and in SUCCESS mode, rate limiting off
  2. 30 s warm-up (mixed.js, small data set, discarded)
  3. the measured k6 run, with `docker stats` sampled every ~2 s for escrow-service and postgres
  4. checks: wait for the outbox to drain and in-flight payouts to settle, then
     GET /api/v1/admin/invariants (must be 0 violations) and reconciliation for today (IST) with
     both sources (PAYOUT_PROVIDER, RAZORPAY), which must report 0 mismatches
Each run writes load/results/v2/runs/<run>.json; raw k6 output, stats samples and check
responses go to load/results/raw/v2/ (gitignored). bench_report.py turns the runs into tables.

    python load/bench.py                 # everything (about three hours)
    python load/bench.py saturation mixed  # only these groups
Runs whose JSON already exists are skipped, so an interrupted benchmark resumes where it stopped.
"""
import datetime as dt
import json
import os
import re
import statistics
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUNS_DIR = ROOT / "load" / "results" / "v2" / "runs"
RAW_DIR = ROOT / "load" / "results" / "raw" / "v2"
K6_IMAGE = os.environ.get("K6_IMAGE", "grafana/k6:0.54.0")
NETWORK = ROOT.name.lower() + "_escrow-network"
APP = "http://localhost:8080"
PROVIDER = "http://localhost:9090"
ADMIN = {"X-Actor-Id": "1", "X-Actor-Role": "ADMIN"}   # bootstrap ADMIN (Flyway V11)
IST = dt.timezone(dt.timedelta(hours=5, minutes=30))
REPEATS = 3

# group, label, script, k6 env, duration of the measured (main) scenario, hikari pool, virtual threads
PLAN = []
for r in range(1, REPEATS + 1):
    PLAN.append(("saturation", "transfers-ramp", "transfers.js", {}, 300, 20, True, r))
for r in range(1, REPEATS + 1):
    for rate in (200, 250):
        PLAN.append(("sustainable", f"transfers-{rate}", "transfers.js", {"RATE": rate, "DURATION_SEC": 180}, 180, 20, True, r))
for r in range(1, REPEATS + 1):
    PLAN.append(("hot-account", "hot-account-200", "hot-account.js", {}, 120, 20, True, r))
    PLAN.append(("replays", "replays-200", "replays.js", {}, 120, 20, True, r))
    PLAN.append(("mixed", "mixed-150", "mixed.js", {}, 120, 20, True, r))
for r in range(1, REPEATS + 1):
    for vt in (True, False):
        for pool in (10, 20, 40):
            PLAN.append(("tuning", f"tune-pool{pool}-vt{'on' if vt else 'off'}", "transfers.js",
                         {"RATE": 250, "DURATION_SEC": 180}, 180, pool, vt, r))


if os.environ.get("BENCH_SMOKE"):
    # Pipeline check only: one 20 s run of each kind, written to v2-smoke (not reported).
    RUNS_DIR = ROOT / "load" / "results" / "raw" / "v2-smoke"
    PLAN = [("saturation", "transfers-ramp", "transfers.js", {"DURATION_SEC": 20, "TARGET_RPS": 300}, 20, 20, True, 1),
            ("sustainable", "transfers-200", "transfers.js", {"RATE": 200, "DURATION_SEC": 20}, 20, 20, True, 1),
            ("mixed", "mixed-150", "mixed.js", {"DURATION_SEC": 20}, 20, 20, True, 1),
            ("tuning", "tune-pool10-vtoff", "transfers.js", {"RATE": 250, "DURATION_SEC": 20}, 20, 10, False, 1)]


def log(msg):
    print(f"[{dt.datetime.now():%H:%M:%S}] {msg}", flush=True)


def sh(args, check=True, env=None, capture=True, timeout=None):
    res = subprocess.run(args, cwd=ROOT, env=env, capture_output=capture, text=True, timeout=timeout)
    if check and res.returncode != 0:
        raise RuntimeError(f"{' '.join(map(str, args))} failed ({res.returncode}): {res.stderr[-2000:]}")
    return res


def compose(*args, env=None, check=True):
    return sh(["docker", "compose", *args], env=env, check=check)


def psql(db, sql):
    return compose("exec", "-T", "postgres", "psql", "-U", "escrow", "-d", db, "-tAc", sql).stdout.strip()


def http(method, url, headers=None, timeout=60):
    req = urllib.request.Request(url, method=method, headers=headers or {}, data=b"" if method == "POST" else None)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        body = r.read().decode()
        return r.status, (json.loads(body) if body else None)


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


# --- app lifecycle --------------------------------------------------------------------------------

CREATED_DBS = []


def start_app(pool, vt):
    db = "escrow_bench_" + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d%H%M%S")
    psql("escrow", f"CREATE DATABASE {db}")
    CREATED_DBS.append(db)
    env = dict(os.environ, ESCROW_DB=db, HIKARI_POOL=str(pool), VIRTUAL_THREADS=str(vt).lower(),
               RATELIMIT_ENABLED="false")
    compose("--profile", "app", "up", "-d", "--no-build", "--force-recreate", "escrow-service", "mock-payout-provider", env=env)
    ok = wait_until("escrow-service health", lambda: http("GET", APP + "/actuator/health")[1]["status"] == "UP", 240)
    ok = ok and wait_until("provider health", lambda: http("GET", PROVIDER + "/actuator/health")[1]["status"] == "UP", 120)
    if not ok:
        print(compose("logs", "--tail", "80", "escrow-service", check=False).stdout)
        raise RuntimeError("app did not start")
    # The provider keeps its schema in the shared escrow database: start every run empty.
    http("POST", PROVIDER + "/admin/reset")
    req = urllib.request.Request(PROVIDER + "/admin/mode", method="POST", data=b'{"mode":"SUCCESS","seed":1}',
                                 headers={"Content-Type": "application/json"})
    urllib.request.urlopen(req, timeout=30).read()
    return db


def k6(script, label, env, quiet_out=None):
    load_dir = str(ROOT / "load")
    args = ["docker", "run", "--rm", "--network", NETWORK, "-v", f"{load_dir}:/load", "-w", "/", K6_IMAGE, "run", "--quiet",
            "-e", "BASE_URL=http://escrow-service:8080", "-e", "RESULTS_DIR=/load/results/raw/v2", "-e", f"LABEL={label}"]
    for k, v in env.items():
        args += ["-e", f"{k}={v}"]
    if quiet_out is None:
        args += ["--summary-export", f"/load/results/raw/v2/{script[:-3]}-{label}.summary.json"]
    args.append(f"/load/{script}")
    res = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, env=dict(os.environ, MSYS_NO_PATHCONV="1"))
    return res


def warm_up():
    k6("mixed.js", "warmup", {"RATE": 50, "DURATION_SEC": 30, "CLIENTS": 20, "FREELANCERS": 5, "PROJECTS": 10,
                              "RESULTS_DIR": "/tmp"}, quiet_out=True)


# --- docker stats sampler -------------------------------------------------------------------------

class Sampler(threading.Thread):
    """`docker stats --no-stream` in a loop: it takes ~2 s per call (two reads to compute CPU%)."""

    def __init__(self, containers):
        super().__init__(daemon=True)
        self.containers = containers
        self.samples = []   # (epoch, name, cpu%)
        self.stop = threading.Event()

    def run(self):
        while not self.stop.is_set():
            t0 = time.time()
            res = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.Name}}\t{{.CPUPerc}}", *self.containers],
                                 capture_output=True, text=True)
            now = time.time()
            for line in res.stdout.splitlines():
                name, cpu = line.split("\t")
                self.samples.append((now, name, float(cpu.strip().rstrip("%") or 0)))
            time.sleep(max(0.0, 2.0 - (time.time() - t0)))


def container_names():
    names = compose("ps", "--format", "{{.Service}}\t{{.Name}}").stdout.splitlines()
    m = dict(line.split("\t") for line in names if "\t" in line)
    return m["escrow-service"], m["postgres"]


# --- one run --------------------------------------------------------------------------------------

def metric(summary, name, stat):
    m = summary.get("metrics", {}).get(name)
    return None if m is None else m.get(stat)


def run_one(group, label, script, env, duration, pool, vt, rep):
    run_id = f"{label}-r{rep}"
    out = RUNS_DIR / f"{run_id}.json"
    if out.exists():
        log(f"skip {run_id} (done)")
        return
    log(f"== {run_id}: {script} {env} pool={pool} vt={vt}")
    db = start_app(pool, vt)
    warm_up()

    escrow_c, pg_c = container_names()
    sampler = Sampler([escrow_c, pg_c])
    sampler.start()
    k6_label = f"{label}-r{rep}"
    started = time.time()
    res = k6(script, k6_label, env)
    ended = time.time()
    sampler.stop.set()
    sampler.join()
    (RAW_DIR / f"{run_id}.k6.log").write_text(res.stdout + "\n--- stderr ---\n" + res.stderr)

    # CPU over the measured scenario only: setup() runs first, the main scenario is the last
    # `duration` seconds of the k6 run (teardown and the summary take well under a second).
    main_from = ended - duration
    cpu = {}
    for svc, cname in (("escrow", escrow_c), ("postgres", pg_c)):
        vals = [c for (t, n, c) in sampler.samples if n == cname and t >= main_from]
        cpu[svc] = {"avg": round(statistics.mean(vals), 1) if vals else None,
                    "peak": round(max(vals), 1) if vals else None, "samples": len(vals)}
    with open(RAW_DIR / f"{run_id}.stats.tsv", "w") as f:
        for t, n, c in sampler.samples:
            f.write(f"{t:.1f}\t{n}\t{c}\n")

    name = script[:-3]
    summary = json.loads((RAW_DIR / f"{name}-{k6_label}.summary.json").read_text())
    result = json.loads((RAW_DIR / f"{name}-{k6_label}.result.json").read_text())

    # --- checks after the scenario ---------------------------------------------------------------
    drained = wait_until("outbox drain", lambda: psql(db, "SELECT count(*) FROM outbox WHERE published_at IS NULL") == "0", 600)
    settled = wait_until("payouts to settle", lambda: psql(
        db, "SELECT count(*) FROM payouts WHERE state IN ('RESERVED','PROCESSING','UNKNOWN')") == "0", 300)
    _, inv = http("GET", APP + "/api/v1/admin/invariants", ADMIN, timeout=300)
    today = dt.datetime.now(IST).date().isoformat()
    http("POST", APP + f"/api/v1/admin/recon/run?date={today}", ADMIN, timeout=600)
    _, runs = http("GET", APP + f"/api/v1/admin/recon/runs?date={today}", ADMIN)
    (RAW_DIR / f"{run_id}.checks.json").write_text(json.dumps({"invariants": inv, "recon": runs}, indent=2, default=str))
    recon = {r["entity_type"]: {"status": r["status"], "mismatches": r["mismatch_count"]} for r in runs["runs"]}
    payout_states = psql(db, "SELECT state || '=' || count(*) FROM payouts GROUP BY state ORDER BY state").split()

    record = {
        "run": run_id, "group": group, "label": label, "repeat": rep, "script": script, "env": env,
        "hikariPool": pool, "virtualThreads": vt, "durationSec": duration,
        "startedAt": dt.datetime.fromtimestamp(started, dt.timezone.utc).isoformat(),
        "k6ExitCode": res.returncode,
        "requests": result["requests"], "achievedRps": result["achievedRps"],
        "p50": result["p50"], "p95": result["p95"], "p99": result["p99"],
        "errorRate": result["errorRate"], "droppedIterations": result["droppedIterations"],
        "transferP95": result.get("transferP95"), "thresholdsPassed": result["thresholdsPassed"],
        "replayHonoured": metric(summary, "replay_honoured", "value"),
        "cpu": cpu,
        "checks": {
            "outboxDrained": drained, "payoutsSettled": settled,
            "invariantViolations": inv["violations_count"],
            "reconDate": today, "recon": recon,
            "reconClean": all(v["status"] == "COMPLETED" and v["mismatches"] == 0 for v in recon.values()) and len(recon) == 2,
        },
        "payoutStates": payout_states,
    }
    out.write_text(json.dumps(record, indent=2))
    log(f"   {run_id}: {result['achievedRps']} rps p95={result['p95']:.1f} dropped={result['droppedIterations']} "
        f"cpu escrow avg/peak={cpu['escrow']['avg']}/{cpu['escrow']['peak']} pg={cpu['postgres']['avg']}/{cpu['postgres']['peak']} "
        f"invariants={inv['violations_count']} recon={recon} payouts={payout_states}")


def hardware():
    lines = [f"date: {dt.datetime.now(dt.timezone.utc):%Y-%m-%dT%H:%M:%SZ}"]
    ps = subprocess.run(["powershell", "-NoProfile", "-Command",
                         "(Get-CimInstance Win32_Processor | Select-Object -First 1).Name; "
                         "'cores: ' + (Get-CimInstance Win32_Processor | Measure-Object -Property NumberOfCores -Sum).Sum; "
                         "'logical: ' + (Get-CimInstance Win32_Processor | Measure-Object -Property NumberOfLogicalProcessors -Sum).Sum; "
                         "'ram GB: ' + [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1GB,1); "
                         "(Get-CimInstance Win32_OperatingSystem).Caption"], capture_output=True, text=True)
    lines += [l.strip() for l in ps.stdout.splitlines() if l.strip()]
    lines.append("docker: " + sh(["docker", "info", "--format", "{{.NCPU}} CPUs, {{.MemTotal}} bytes, server {{.ServerVersion}}"]).stdout.strip())
    lines.append("limits: escrow-service cpus=2 mem=1g; postgres cpus=2 (docker stats CPU% is per core: 200% = the cap)")
    lines.append(f"k6: {K6_IMAGE} on the same host, unlimited")
    (RUNS_DIR.parent / "HARDWARE.txt").write_text("\n".join(lines) + "\n")
    log("hardware: " + " | ".join(lines))


def main():
    groups = set(sys.argv[1:])
    RUNS_DIR.mkdir(parents=True, exist_ok=True)
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    compose("up", "-d", "postgres", "zookeeper", "kafka", "kafka-init", "redis")
    log("building escrow-service and mock-payout-provider images")
    compose("--profile", "app", "build", "escrow-service", "mock-payout-provider")
    hardware()
    try:
        for step in PLAN:
            if groups and step[0] not in groups:
                continue
            try:
                run_one(*step)
            except Exception as e:
                log(f"   RUN FAILED {step[1]}-r{step[7]}: {e}")
            finally:
                # Each run's database is only needed for its own checks.
                while CREATED_DBS:
                    db = CREATED_DBS.pop()
                    compose("--profile", "app", "stop", "escrow-service", check=False)
                    psql("escrow", f'DROP DATABASE IF EXISTS "{db}" WITH (FORCE)')
    finally:
        compose("--profile", "app", "stop", "escrow-service", "mock-payout-provider", check=False)
        log("done")


if __name__ == "__main__":
    main()
