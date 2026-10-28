# Scalability results

The measurements the paper reports live here, one folder per run. `bench/run-matrix.sh <topology>`
(or `just benchmark` for both topologies) writes each run to `results/<topology>/<YYYYMMDD-HHMMSS>/`:

- `summary.csv`, one row per point and repetition: committed tx/s, p50/p95/p99 latency, counts by outcome;
- `transactions.csv`, one row per transaction in a measured window;
- `environment.txt`, the machine, the Docker engine, the images and the commit the run used.

The columns are described in [`bench/README.md`](../bench/README.md). `bench.log` is not committed
(`*.log` is ignored). GitHub refuses files over 100 MB, so gzip a larger `transactions.csv`.

`just results` prints every `summary.csv` in this folder; the reviewer image runs it too.

| Topology | Run | Machine | Commit | Used in the paper |
|---|---|---|---|---|
| | not run yet | | | |

Smoke runs (`just benchmark --smoke`, and the CI) write to `out/results-smoke/` and never here.
