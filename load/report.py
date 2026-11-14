#!/usr/bin/env python3
"""Turn load/results/*.result.json into markdown tables.

Usage: python load/report.py [results_dir] > load/results/REPORT.md

Each k6 run writes <scenario>-<label>.result.json (via handleSummary in lib/common.js);
run-bench.sh writes <scenario>-<label>.invariants.json from GET /api/v1/admin/invariants.
Labels starting with "tune-" go in the tuning table; everything else is the main benchmark.
"""
import json
import sys
from pathlib import Path

SCENARIO_ORDER = ["transfers", "hot-account", "replays", "mixed"]


def ms(v):
    return "n/a" if v is None else f"{v:.1f}"


def invariants(results_dir: Path, scenario: str, label: str) -> str:
    f = results_dir / f"{scenario}-{label}.invariants.json"
    if not f.exists():
        return "not checked"
    try:
        data = json.loads(f.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return "unreadable"
    if "violations_count" not in data:
        return f"check failed: {data.get('error', '?')}"
    n = data["violations_count"]
    return "0 violations" if n == 0 else f"**{n} violations**"


def load(results_dir: Path):
    rows = []
    for f in sorted(results_dir.glob("*.result.json")):
        r = json.loads(f.read_text(encoding="utf-8"))
        r["invariants"] = invariants(results_dir, r["scenario"], r["label"])
        rows.append(r)
    order = {s: i for i, s in enumerate(SCENARIO_ORDER)}
    rows.sort(key=lambda r: (r["label"], order.get(r["scenario"], 99)))
    return rows


def main_table(rows):
    out = ["| scenario | target rps | achieved rps | p50 ms | p95 ms | p99 ms | error % | dropped iters | thresholds | invariants after |",
           "|---|---|---|---|---|---|---|---|---|---|"]
    for r in rows:
        name = r["scenario"] if r["label"] == "bench" else f'{r["scenario"]} ({r["label"]})'
        out.append("| {scenario} | {target} | {achieved} | {p50} | {p95} | {p99} | {err:.2f} | {dropped} | {th} | {inv} |".format(
            scenario=name, target=r["targetRps"], achieved=r["achievedRps"],
            p50=ms(r["p50"]), p95=ms(r["p95"]), p99=ms(r["p99"]),
            err=(r["errorRate"] or 0) * 100, dropped=int(r["droppedIterations"]),
            th="pass" if r["thresholdsPassed"] else "fail", inv=r["invariants"]))
    return "\n".join(out)


def tuning_table(rows):
    out = ["| config | target rps | achieved rps | p50 ms | p95 ms | p99 ms | error % | dropped iters | invariants after |",
           "|---|---|---|---|---|---|---|---|---|"]
    for r in rows:
        out.append("| {cfg} | {target} | {achieved} | {p50} | {p95} | {p99} | {err:.2f} | {dropped} | {inv} |".format(
            cfg=r["label"][len("tune-"):], target=r["targetRps"], achieved=r["achievedRps"],
            p50=ms(r["p50"]), p95=ms(r["p95"]), p99=ms(r["p99"]),
            err=(r["errorRate"] or 0) * 100, dropped=int(r["droppedIterations"]), inv=r["invariants"]))
    return "\n".join(out)


def main():
    results_dir = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).parent / "results")
    rows = load(results_dir)
    bench = [r for r in rows if not r["label"].startswith("tune-")]
    tune = [r for r in rows if r["label"].startswith("tune-")]
    print("## Benchmark\n")
    print(main_table(bench) if bench else "_no benchmark results_")
    print("\n## Tuning experiment (transfers.js)\n")
    print(tuning_table(tune) if tune else "_no tuning results_")


if __name__ == "__main__":
    main()
