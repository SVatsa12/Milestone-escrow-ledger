#!/usr/bin/env bash
# Run the P15 benchmark: start the app stack with fixed resources (escrow-service 2 CPUs / 1 GB,
# postgres 2 CPUs; see docker-compose.yml), warm up, run each k6 scenario, check invariants
# after each one, then build load/results/REPORT.md.
#
#   load/run-bench.sh            # main benchmark (all four scenarios)
#   load/run-bench.sh knee       # transfers at constant 200..600 req/s to locate the knee
#   load/run-bench.sh tune       # tuning experiment: Hikari 10/20/40 and virtual threads on/off
#
# k6 runs in the grafana/k6 container on the compose network, so nothing is installed locally.
# Rate limiting is turned off for the run: the scenarios drive one admin actor far past the
# 30 req/min limit, and we want to measure the ledger, not the 429 path.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
RESULTS="$ROOT/load/results"
mkdir -p "$RESULTS"

K6_IMAGE="${K6_IMAGE:-grafana/k6:0.54.0}"
NETWORK="${NETWORK:-$(basename "$ROOT" | tr '[:upper:]' '[:lower:]')_escrow-network}"
BASE_URL="http://escrow-service:8080"
HOST_URL="http://localhost:8080"
WARMUP_SEC="${WARMUP_SEC:-30}"
export RATELIMIT_ENABLED=false

# Git Bash on Windows rewrites /paths in docker args; keep them literal and use a Windows path for -v.
export MSYS_NO_PATHCONV=1
LOAD_DIR="$ROOT/load"
if command -v cygpath >/dev/null 2>&1; then LOAD_DIR="$(cygpath -w "$LOAD_DIR")"; fi

# Every app start gets a brand-new database, so each measurement begins from an empty ledger and
# the invariant check after a scenario covers only that run's data. Every database this script
# creates is dropped on exit (success, failure or Ctrl-C); the results are already on disk.
CREATED_DBS=()

fresh_db() {
  export ESCROW_DB="escrow_bench_$(date -u +%Y%m%d%H%M%S)"
  docker compose exec -T postgres psql -U escrow -d escrow -qc "CREATE DATABASE $ESCROW_DB" >/dev/null
  CREATED_DBS+=("$ESCROW_DB")
  echo "   database: $ESCROW_DB"
}

cleanup() {
  local status=$?
  set +e
  if (( ${#CREATED_DBS[@]} > 0 )); then
    echo "== cleanup: stopping escrow-service and dropping ${#CREATED_DBS[@]} bench database(s)"
    # The app points at a bench database; stop it before that database disappears.
    docker compose --profile app stop escrow-service >/dev/null 2>&1
    for db in "${CREATED_DBS[@]}"; do
      docker compose exec -T postgres psql -U escrow -d escrow -qc "DROP DATABASE IF EXISTS \"$db\" WITH (FORCE)" >/dev/null 2>&1 \
        && echo "   dropped $db" || echo "   could not drop $db (drop it by hand)"
    done
  fi
  exit "$status"
}
trap cleanup EXIT

start_app() {
  echo "== starting app stack (HIKARI_POOL=${HIKARI_POOL:-20} VIRTUAL_THREADS=${VIRTUAL_THREADS:-true})"
  fresh_db
  docker compose --profile app up -d --build --force-recreate escrow-service mock-payout-provider >/dev/null
  for _ in $(seq 1 90); do
    if curl -fs "$HOST_URL/actuator/health" | grep -q '"UP"'; then echo "   escrow-service is UP"; return 0; fi
    sleep 2
  done
  echo "escrow-service did not become healthy" >&2
  docker compose logs --tail 80 escrow-service >&2
  exit 1
}

# A run past the knee leaves an outbox backlog; the relay draining it would steal CPU from the
# next scenario and skew its numbers. Start each scenario from an empty outbox (max 5 min).
wait_outbox_drained() {
  for _ in $(seq 1 150); do
    local pending
    pending=$(docker compose exec -T postgres psql -U escrow -d "$ESCROW_DB" -tAc \
      "SELECT count(*) FROM outbox WHERE published_at IS NULL" 2>/dev/null || echo "?")
    [[ "$pending" == "0" ]] && return 0
    sleep 2
  done
  echo "   (outbox still has $pending unpublished rows; continuing anyway)"
}

k6_run() { # script label [extra -e args...]
  local script="$1" label="$2"; shift 2
  local name="${script%.js}"
  wait_outbox_drained
  echo "== k6 $script [$label]"
  docker run --rm --network "$NETWORK" -v "$LOAD_DIR:/load" -w / "$K6_IMAGE" run --quiet \
    -e BASE_URL="$BASE_URL" -e RESULTS_DIR=/load/results -e LABEL="$label" "$@" \
    --summary-export "/load/results/$name-$label.summary.json" \
    "/load/$script" || echo "   (k6 exited non-zero: a threshold failed; results are still recorded)"
  curl -s "$HOST_URL/api/v1/admin/invariants" > "$RESULTS/$name-$label.invariants.json" || true
  echo "   invariants: $(cat "$RESULTS/$name-$label.invariants.json")" | cut -c1-200
}

warm_up() {
  echo "== warm-up ${WARMUP_SEC}s (mixed.js at 50 req/s, small setup, results discarded)"
  docker run --rm --network "$NETWORK" -v "$LOAD_DIR:/load" -w / "$K6_IMAGE" run --quiet \
    -e BASE_URL="$BASE_URL" -e RESULTS_DIR=/tmp -e LABEL=warmup -e RATE=50 -e DURATION_SEC="$WARMUP_SEC" \
    -e CLIENTS=20 -e FREELANCERS=5 -e PROJECTS=10 /load/mixed.js >/dev/null || true
}

hardware() {
  {
    echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "host: $(uname -srm)"
    if command -v powershell >/dev/null 2>&1; then
      powershell -NoProfile -Command "(Get-CimInstance Win32_Processor | Select-Object -First 1).Name; 'cores: ' + (Get-CimInstance Win32_Processor | Measure-Object -Property NumberOfCores -Sum).Sum; 'logical: ' + (Get-CimInstance Win32_Processor | Measure-Object -Property NumberOfLogicalProcessors -Sum).Sum; 'ram GB: ' + [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1GB,1)" 2>/dev/null | tr -d '\r'
    fi
    echo "docker: $(docker info --format '{{.NCPU}} CPUs, {{.MemTotal}} bytes visible to Docker')"
    echo "limits: escrow-service cpus=2 mem=1g; postgres cpus=2"
  } > "$RESULTS/HARDWARE.txt"
  cat "$RESULTS/HARDWARE.txt"
}

docker compose up -d postgres zookeeper kafka kafka-init redis >/dev/null
hardware

if [[ "${1:-bench}" == "knee" ]]; then
  # The ramp's average hides where it breaks; hold constant rates and watch p95.
  export HIKARI_POOL=20 VIRTUAL_THREADS=true
  start_app; warm_up
  for rate in ${KNEE_RATES:-200 300 400 500 600}; do
    k6_run transfers.js "knee-${rate}" -e START_RPS="$rate" -e TARGET_RPS="$rate" -e DURATION_SEC="${KNEE_DURATION_SEC:-60}"
  done
elif [[ "${1:-bench}" == "tune" ]]; then
  # Shorter ramp than the main run so six configs fit in a reasonable time.
  for vt in true false; do
    for pool in 10 20 40; do
      export HIKARI_POOL=$pool VIRTUAL_THREADS=$vt
      start_app; warm_up
      k6_run transfers.js "tune-pool${pool}-vt${vt}" -e DURATION_SEC="${TUNE_DURATION_SEC:-120}" -e TARGET_RPS="${TUNE_TARGET_RPS:-800}"
    done
  done
else
  export HIKARI_POOL=20 VIRTUAL_THREADS=true
  start_app; warm_up
  for s in transfers.js hot-account.js replays.js mixed.js; do
    k6_run "$s" bench
  done
fi

# Relative paths: a Windows python cannot open /c/... paths (MSYS_NO_PATHCONV is set above).
python load/report.py load/results > load/results/REPORT.md
cat load/results/REPORT.md
