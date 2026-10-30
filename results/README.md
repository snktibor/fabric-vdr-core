# Scalability results

The measurements the paper reports live here, one folder per run. `bench/run-matrix.sh <topology>`
(or `just benchmark` for both topologies) writes each run to `results/<topology>/<YYYYMMDD-HHMMSS>-bt<batch timeout>/`:

- `summary.csv`, one row per point and repetition: committed tx/s, p50/p95/p99 latency, counts by outcome;
- `transactions.csv`, one row per transaction in a measured window;
- `environment.txt`, the machine, the Docker engine, the images and the commit the run used;
- in a robustness run (`WORKLOADS=F`), also `pairs.csv`, `faults.csv` and `solo.csv`.

The columns are described in [`bench/README.md`](../bench/README.md). `bench.log` is not committed
(`*.log` is ignored). GitHub refuses files over 100 MB, so gzip a larger `transactions.csv`.

`just results` prints every `summary.csv` in this folder; the reproduce image runs it too.
`bench/aggregate.py results <dir>` turns the scalability runs into the CSVs behind the paper's
scalability figure, and `bench/analyze-faults.py <run>` summarises a robustness run.

| Topology | Run | Machine | Commit | Used in the paper |
|---|---|---|---|---|
| `1org-solo` | `20261028-225051-bt250ms` | BME Cloud VM, 8 vCPU, 16 GB | `38e76f5` | scalability, W1 to W3 |
| `1org-solo` | `20261029-003934-bt2s` | same | `38e76f5` | W1 at Fabric's default 2 s batch timeout |
| `3org-raft` | `20261028-234453-bt250ms` | same | `38e76f5` | scalability, W1 to W3 |
| `3org-raft` | `20261029-005129-bt250ms` | same | `38e76f5` | robustness, every fault, 60 s commit-status deadline |
| `3org-raft` | `20261029-110821-bt250ms` | same | `2728a55` | robustness, the two Raft faults with a 2 s deadline |

Smoke runs (`just benchmark --smoke`, and the CI) write to `out/results-smoke/` and never here.
