#!/usr/bin/env python3
"""Aggregates every summary.csv under a results directory over the repetitions of each point.

Usage:  bench/aggregate.py <results dir> <output dir>
        bench/aggregate.py --selftest

A point is (topology, batch_timeout_ms, workload, k, c, r). For each point it writes the median and
the min and max over the repetitions of committed client tx/s, of the p50, p95 and p99 latency of
committed client transactions, and of the share of rejected guarded transactions:

    rejected = (mvcc_conflict + failed_precondition) / client transactions

mvcc_conflict counts transactions invalidated at commit for a stale read set (MVCC_READ_CONFLICT),
failed_precondition counts stale guards refused at endorsement, and client transactions is the sum of
the outcome columns. The background writer is not a client. Network failures, unknown commit
statuses and other failures are summed over the repetitions and reported separately.

Writes one CSV per workload (W1.csv, W2.csv, W3.csv) into the output directory. Directories without a
summary.csv (the robustness runs) are skipped. Standard library only.
"""
import csv
import statistics
import sys
from collections import defaultdict
from pathlib import Path

KEY = ("topology", "batch_timeout_ms", "workload", "k", "c", "r")
OUTCOMES = ("committed", "mvcc_conflict", "endorsement_rejected", "commit_rejected", "network_failure",
            "commit_status_unknown", "other_failure")
METRICS = ("committed_tps", "p50_ms", "p95_ms", "p99_ms", "rejected_share")


def rejected_share(row):
    total = sum(int(row[o]) for o in OUTCOMES)
    return (int(row["mvcc_conflict"]) + int(row["failed_precondition"])) / total if total else None


def aggregate(rows):
    points = defaultdict(list)
    for row in rows:
        points[tuple(row[k] for k in KEY)].append(row)
    out = []
    for key, reps in sorted(points.items(), key=lambda p: (p[0][:3], *(float(v) for v in p[0][3:]))):
        agg = dict(zip(KEY, key), reps=len(reps))
        for metric in METRICS:
            if metric == "rejected_share":
                values = [v for v in map(rejected_share, reps) if v is not None]
            else:
                # A latency of -1 means the repetition committed nothing; it has no latency.
                values = [float(r[metric]) for r in reps if float(r[metric]) >= 0]
            for name, f in (("median", statistics.median), ("min", min), ("max", max)):
                agg[f"{metric}_{name}"] = round(f(values), 4) if values else ""
        agg["client_tx"] = sum(int(r[o]) for r in reps for o in OUTCOMES)
        agg["rejected"] = sum(int(r["mvcc_conflict"]) + int(r["failed_precondition"]) for r in reps)
        agg["network_failure"] = sum(int(r["network_failure"]) for r in reps)
        agg["commit_status_unknown"] = sum(int(r["commit_status_unknown"]) for r in reps)
        agg["other_failed"] = sum(
            int(r["endorsement_rejected"]) - int(r["failed_precondition"]) + int(r["commit_rejected"])
            + int(r["other_failure"]) for r in reps)
        agg["writer_committed"] = sum(int(r["writer_committed"]) for r in reps)
        agg["writer_total"] = sum(int(r["writer_total"]) for r in reps)
        out.append(agg)
    return out


def main(results, out_dir):
    rows = []
    for summary in sorted(Path(results).rglob("summary.csv")):
        with open(summary, newline="") as f:
            rows += list(csv.DictReader(f))
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    by_workload = defaultdict(list)
    for agg in aggregate(rows):
        by_workload[agg["workload"]].append(agg)
    for workload, aggs in sorted(by_workload.items()):
        with open(out_dir / f"{workload}.csv", "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(aggs[0]))
            w.writeheader()
            w.writerows(aggs)
        print(f"{workload}: {len(aggs)} points -> {out_dir / f'{workload}.csv'}")


def selftest():
    def row(rep, tps, p95, mvcc, fp, nf=0):
        r = dict(topology="t", batch_timeout_ms="250", workload="W3", k="1", c="4", r="5.0",
                 repetition=str(rep), committed_tps=tps, p50_ms="10", p95_ms=p95, p99_ms="30",
                 failed_precondition=str(fp), writer_committed="1", writer_total="2")
        r.update({o: "0" for o in OUTCOMES})
        r.update(committed="6", mvcc_conflict=str(mvcc), endorsement_rejected=str(fp), network_failure=str(nf))
        return r
    (a,) = aggregate([row(1, "1.0", "20", 2, 2, nf=1), row(2, "3.0", "-1", 0, 0), row(3, "2.0", "40", 4, 0)])
    assert a["reps"] == 3 and a["committed_tps_median"] == 2.0, a
    assert (a["committed_tps_min"], a["committed_tps_max"]) == (1.0, 3.0), a
    assert (a["p95_ms_median"], a["p95_ms_min"]) == (30.0, 20.0), a      # the -1 repetition is left out
    assert (a["rejected_share_min"], a["rejected_share_max"]) == (0.0, 0.4), a  # 4/11 and 4/10
    assert a["rejected"] == 8 and a["network_failure"] == 1 and a["other_failed"] == 0, a
    print("selftest ok")


if __name__ == "__main__":
    if sys.argv[1:] == ["--selftest"]:
        selftest()
    elif len(sys.argv) == 3:
        main(sys.argv[1], sys.argv[2])
    else:
        sys.exit(__doc__)
