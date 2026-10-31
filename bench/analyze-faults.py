#!/usr/bin/env python3
"""Summarises the robustness runs (WORKLOADS=F) of one results directory.

Usage:  bench/analyze-faults.py results/3org-raft/<timestamp> [<out dir>]
        bench/analyze-faults.py --selftest

Writes faults-summary.csv (one row per run) and timeline.csv (committed client transactions per
second, relative to the moment the fault started) into <out dir>, by default the run directory, and
prints the totals per fault. `just figures` passes an out dir under out/, so it never writes into
results/. Standard library only.
"""
import csv
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path

def read(path):
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def summarise_run(fault, txs, pairs):
    """fault: a faults.csv row; txs: its client transactions; pairs: its pairs.csv rows."""
    m0, down, up, end = (int(fault[k]) for k in ("measure_from_ms", "down_ms", "up_ms", "end_ms"))
    commits = sorted(int(t["final_ms"]) for t in txs if t["outcome"] == "COMMITTED")

    def tps(lo, hi):
        return sum(lo <= c < hi for c in commits) / ((hi - lo) / 1000)

    def longest_gap(lo, hi):
        points = [lo] + [c for c in commits if lo <= c <= hi] + [hi]
        return max(b - a for a, b in zip(points, points[1:])) / 1000

    first_after_up = next((c for c in commits if c >= up), None)
    failed = Counter(t["outcome"] for t in txs if t["outcome"] != "COMMITTED" and down <= int(t["submit_ms"]) < up)
    kinds = Counter((p["a_advanced"] == "true") + (p["b_advanced"] == "true") for p in pairs)
    during = Counter(
        (p["a_advanced"] == "true") + (p["b_advanced"] == "true") for p in pairs if down <= int(p["start_ms"]) < up
    )
    ledger_ok = sum(p["a_advanced"] == "true" for p in pairs) == int(fault["a_final"]) - int(fault["a_initial"]) and sum(
        p["b_advanced"] == "true" for p in pairs
    ) == int(fault["b_final"]) - int(fault["b_initial"])
    return {
        "run_id": fault["run_id"],
        "fault": fault["fault"],
        "repetition": fault["repetition"],
        "touched": fault["touched"],
        "tps_before": round(tps(m0, down), 3),
        "tps_during": round(tps(down, up), 3),
        "tps_after": round(tps(up, end), 3),
        # Longest stretch without a commit: before the fault it is the batching rhythm, after it
        # the outage a client sees.
        "stall_before_s": round(longest_gap(m0, down), 1),
        "stall_s": round(longest_gap(down, end), 1),
        "first_commit_after_up_s": "" if first_after_up is None else (first_after_up - up) / 1000,
        "failed_during": ";".join(f"{k}:{v}" for k, v in sorted(failed.items())),
        "pairs": len(pairs),
        "pairs_one": kinds[1],
        "pairs_none": kinds[0],
        "pairs_both": kinds[2],
        "pairs_during": sum(during.values()),
        "pairs_during_one": during[1],
        "pairs_during_both": during[2],
        "ledger_matches_pairs": ledger_ok,
    }


def timeline(fault, txs):
    down = int(fault["down_ms"])
    lo, hi = (int(fault["measure_from_ms"]) - down) // 1000, (int(fault["end_ms"]) - down) // 1000
    per_s = Counter((int(t["final_ms"]) - down) // 1000 for t in txs if t["outcome"] == "COMMITTED")
    return [(s, per_s[s]) for s in range(lo, hi)]


def main(directory, out_dir=None):
    d = Path(directory)
    out = Path(out_dir) if out_dir else d
    out.mkdir(parents=True, exist_ok=True)
    faults = read(d / "faults.csv")
    txs, pairs = defaultdict(list), defaultdict(list)
    for t in read(d / "transactions.csv"):
        if t["workload"].startswith("F-") and t["client"] != "writer":
            txs[t["run_id"]].append(t)
    for p in read(d / "pairs.csv"):
        pairs[p["run_id"]].append(p)

    rows = [summarise_run(f, txs[f["run_id"]], pairs[f["run_id"]]) for f in faults]
    with open(out / "faults-summary.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    with open(out / "timeline.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["run_id", "fault", "repetition", "t_s", "committed"])
        for fr in faults:
            for s, n in timeline(fr, txs[fr["run_id"]]):
                w.writerow([fr["run_id"], fr["fault"], fr["repetition"], s, n])

    by_fault = defaultdict(list)
    for r in rows:
        by_fault[r["fault"]].append(r)
    print("fault    reps  tps_before  during/before  stall_before_s  stall_s  pairs one/none/BOTH  ledger")
    for fault, rs in by_fault.items():
        mean = lambda key: statistics.mean(float(r[key]) for r in rs if r[key] != "")  # noqa: E731
        print(
            f"{fault:8} {len(rs):4}  {mean('tps_before'):10.2f}  "
            f"{statistics.mean(r['tps_during'] / r['tps_before'] for r in rs if r['tps_before']):13.2f}  "
            f"{max(r['stall_before_s'] for r in rs):14.1f}  {max(r['stall_s'] for r in rs):7.1f}  "
            f"{sum(r['pairs'] for r in rs):5} {sum(r['pairs_one'] for r in rs)}/{sum(r['pairs_none'] for r in rs)}/"
            f"{sum(r['pairs_both'] for r in rs)}  {all(r['ledger_matches_pairs'] for r in rs)}"
        )
    solo = d / "solo.csv"
    if solo.exists():
        counts = Counter((r["endorsing_orgs"], r["validation_code"], r["advanced"]) for r in read(solo))
        for (orgs, code, adv), n in sorted(counts.items()):
            print(f"solo  {orgs:16} {code:32} advanced={adv:5} {n}")


def selftest():
    # 10 s before the fault at 2 tx/s, a 6 s stall, then 2 tx/s again; one pair of each kind.
    down, m0 = 100_000, 90_000
    fault = {"run_id": "r", "fault": "leader", "repetition": "1", "touched": "o0", "measure_from_ms": m0,
             "down_ms": down, "up_ms": down + 10_000, "end_ms": down + 30_000,
             "a_initial": 0, "b_initial": 0, "a_final": 2, "b_final": 1}
    commits = [m0 + 500 * i for i in range(20)] + [down + 6_000 + 500 * i for i in range(48)]
    txs = [{"final_ms": c, "submit_ms": c - 100, "outcome": "COMMITTED"} for c in commits]
    txs.append({"final_ms": down + 1_000, "submit_ms": down + 900, "outcome": "NETWORK_FAILURE"})
    pairs = [{"start_ms": down - 5_000, "a_advanced": "true", "b_advanced": "false"},
             {"start_ms": down + 1_000, "a_advanced": "false", "b_advanced": "false"},
             {"start_ms": down + 7_000, "a_advanced": "true", "b_advanced": "true"}]
    r = summarise_run(fault, txs, pairs)
    assert r["tps_before"] == 2.0, r
    assert (r["stall_before_s"], r["stall_s"]) == (0.5, 6.0), r
    assert r["first_commit_after_up_s"] == 0.0, r
    assert r["failed_during"] == "NETWORK_FAILURE:1", r
    assert (r["pairs_one"], r["pairs_none"], r["pairs_both"], r["pairs_during_both"]) == (1, 1, 1, 1), r
    assert r["ledger_matches_pairs"] is True, r
    assert timeline(fault, txs)[10] == (0, 0) and timeline(fault, txs)[0] == (-10, 2)
    print("selftest ok")


if __name__ == "__main__":
    if sys.argv[1:] == ["--selftest"]:
        selftest()
    elif len(sys.argv) in (2, 3):
        main(*sys.argv[1:])
    else:
        sys.exit(__doc__)
