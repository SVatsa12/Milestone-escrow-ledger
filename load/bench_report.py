#!/usr/bin/env python3
"""
Turns load/results/v2/runs/*.json (written by bench.py) into load/results/v2/summary.json and
load/results/v2/REPORT.md: one row per scenario/config, every number the median of its runs with
the min-max range.

"failed %" counts DROPPED iterations as failures: (failed requests + dropped iterations) /
(requests + dropped iterations). k6 drops an iteration when it can't start it on schedule, i.e. a
request the target rate asked for and the system never got. A row with drops is not "0% errors".
"""
import json
import statistics
import sys
from pathlib import Path

V2 = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parent / "results" / "v2")


RAW = V2.parent / "raw" / "v2"


def enrich(f, r):
    """CPU over the run's final 60 s (the saturated end of a ramp), from the raw docker stats
    samples; written back into the run JSON so the committed data stands on its own."""
    if "lastMinute" in r["cpu"]["escrow"] or not (RAW / f"{r['run']}.stats.tsv").exists():
        return r
    samples = [line.split(chr(9)) for line in (RAW / f"{r['run']}.stats.tsv").read_text().splitlines()]
    end = max(float(t) for t, _, _ in samples)
    names = sorted({n for _, n, _ in samples})
    for svc in ("escrow", "postgres"):
        name = next(n for n in names if ("escrow-service" in n if svc == "escrow" else "postgres" in n))
        vals = [float(c) for t, n, c in samples if n == name and float(t) >= end - 60]
        r["cpu"][svc]["lastMinute"] = {"avg": round(statistics.mean(vals), 1), "peak": round(max(vals), 1), "samples": len(vals)}
    f.write_text(json.dumps(r, indent=2))
    return r


def runs():
    out = {}
    for f in sorted((V2 / "runs").glob("*.json")):
        r = enrich(f, json.loads(f.read_text()))
        out.setdefault(r["label"], []).append(r)
    return out


def failed_pct(r):
    total = r["requests"] + r["droppedIterations"]
    failed = (r["errorRate"] or 0) * r["requests"] + r["droppedIterations"]
    return 100.0 * failed / total if total else 0.0


def stat(values):
    values = [v for v in values if v is not None]
    if not values:
        return None
    return {"median": statistics.median(values), "min": min(values), "max": max(values), "n": len(values)}


def summarize(rs):
    return {
        "runs": len(rs),
        "achievedRps": stat([r["achievedRps"] for r in rs]),
        "p50": stat([r["p50"] for r in rs]),
        "p95": stat([r["p95"] for r in rs]),
        "p99": stat([r["p99"] for r in rs]),
        "httpErrorPct": stat([100.0 * (r["errorRate"] or 0) for r in rs]),
        "droppedIterations": stat([r["droppedIterations"] for r in rs]),
        "failedPct": stat([failed_pct(r) for r in rs]),
        "escrowCpuAvg": stat([r["cpu"]["escrow"]["avg"] for r in rs]),
        "escrowCpuPeak": stat([r["cpu"]["escrow"]["peak"] for r in rs]),
        "postgresCpuAvg": stat([r["cpu"]["postgres"]["avg"] for r in rs]),
        "postgresCpuPeak": stat([r["cpu"]["postgres"]["peak"] for r in rs]),
        "escrowCpuLastMinute": stat([r["cpu"]["escrow"].get("lastMinute", {}).get("avg") for r in rs]),
        "postgresCpuLastMinute": stat([r["cpu"]["postgres"].get("lastMinute", {}).get("avg") for r in rs]),
        "invariantsClean": sum(1 for r in rs if r["checks"]["invariantViolations"] == 0),
        "reconClean": sum(1 for r in rs if r["checks"]["reconClean"]),
        "outboxDrained": sum(1 for r in rs if r["checks"]["outboxDrained"]),
        "replayHonoured": stat([r.get("replayHonoured") for r in rs]),
        "payoutStates": [r.get("payoutStates") for r in rs],
        "thresholdsPassed": sum(1 for r in rs if r["thresholdsPassed"]),
    }


def cell(s, fmt="{:.1f}"):
    if s is None:
        return "n/a"
    m, lo, hi = (fmt.format(s[k]) for k in ("median", "min", "max"))
    return m if lo == hi else f"{m} ({lo}–{hi})"


def cpu(s_avg, s_peak):
    return f"{cell(s_avg, '{:.0f}')} / {cell(s_peak, '{:.0f}')}"


HEADER = ("| {first} | achieved rps | p50 ms | p95 ms | p99 ms | failed % (incl. dropped) | dropped iters | "
          "escrow CPU % avg / peak | postgres CPU % avg / peak | invariants ok | recon 0 mismatches |\n"
          "|---|---|---|---|---|---|---|---|---|---|---|")


def row(name, s):
    return (f"| {name} | {cell(s['achievedRps'])} | {cell(s['p50'])} | {cell(s['p95'])} | {cell(s['p99'])} | "
            f"{cell(s['failedPct'], '{:.2f}')} | {cell(s['droppedIterations'], '{:.0f}')} | "
            f"{cpu(s['escrowCpuAvg'], s['escrowCpuPeak'])} | {cpu(s['postgresCpuAvg'], s['postgresCpuPeak'])} | "
            f"{s['invariantsClean']}/{s['runs']} | {s['reconClean']}/{s['runs']} |")


def saturation_cpu(data):
    s = data.get("transfers-ramp")
    if not s:
        return ""
    return ("CPU % over the ramp's final minute (where it is saturated), avg: escrow-service "
            f"{cell(s['escrowCpuLastMinute'], '{:.0f}')}, postgres {cell(s['postgresCpuLastMinute'], '{:.0f}')} "
            "(cap 200 each)." + chr(10))


def main():
    data = {label: summarize(rs) for label, rs in runs().items()}
    (V2 / "summary.json").write_text(json.dumps(data, indent=2), encoding="utf-8")

    def section(title, labels, first="scenario", names=None):
        lines = [f"## {title}", "", HEADER.format(first=first)]
        for label in labels:
            if label in data:
                lines.append(row((names or {}).get(label, label), data[label]))
        return "\n".join(lines) + "\n"

    tune = [f"tune-pool{p}-vt{v}" for v in ("on", "off") for p in (10, 20, 40)]
    tune_names = {f"tune-pool{p}-vt{v}": f"pool {p}, {'virtual' if v == 'on' else 'platform'} threads"
                  for v in ("on", "off") for p in (10, 20, 40)}
    out = [
        "Every cell: median of 3 runs (min–max). CPU % is docker stats per core: the cap is 200%.\n",
        section("Saturation (transfers, ramp 50 → 1000 req/s over 5 min)", ["transfers-ramp"]),
        saturation_cpu(data),
        section("Sustainable (transfers, constant arrival rate, 3 min)", ["transfers-200", "transfers-250"],
                names={"transfers-200": "200 req/s", "transfers-250": "250 req/s"}, first="rate"),
        section("Other scenarios", ["hot-account-200", "replays-200", "mixed-150"],
                names={"hot-account-200": "hot-account 200 req/s", "replays-200": "replays 200 req/s",
                       "mixed-150": "mixed 150 req/s"}),
        section("Tuning (transfers at 250 req/s constant, 3 min)", tune, first="config", names=tune_names),
    ]
    if "mixed-150" in data:
        out.append("Payout states after each mixed run: " + "; ".join(" ".join(p or []) for p in data["mixed-150"]["payoutStates"]) + "\n")
    if "replays-200" in data and data["replays-200"]["replayHonoured"]:
        out.append(f"replay_honoured in replays: {cell(data['replays-200']['replayHonoured'], '{:.4f}')}\n")
    (V2 / "REPORT.md").write_text("\n".join(out), encoding="utf-8")
    print("\n".join(out))


if __name__ == "__main__":
    main()
