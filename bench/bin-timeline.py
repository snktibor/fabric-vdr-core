#!/usr/bin/env python3
"""Committed client transactions per second in 5 s bins, for the runs that fig:robustness shows.

Usage:  bench/bin-timeline.py <timeline.csv>:<run_id>:<series> ... > timeline-5s.csv

timeline.csv is what bench/analyze-faults.py writes: committed transactions per
second, relative to the moment the fault started.
"""
import csv
import sys
from collections import Counter

BIN = 5

print("series,t_s,tps")
for arg in sys.argv[1:]:
    path, run, series = arg.split(":")
    bins = Counter()
    with open(path, newline="") as f:
        for row in csv.DictReader(f):
            if row["run_id"] == run:
                bins[int(row["t_s"]) // BIN * BIN] += int(row["committed"])
    ts = sorted(bins)
    for t in ts:
        print(f"{series},{t},{bins[t] / BIN:.1f}")
    print(f"{series},{ts[-1] + BIN},{bins[ts[-1]] / BIN:.1f}")  # closes the last step of the const plot
