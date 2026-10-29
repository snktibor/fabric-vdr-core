# Scalability measurement

Closed-loop load generator for the guarded revocation update, run against two Fablo topologies.

| Workload | What varies | Fixed |
|---|---|---|
| W1, cost of guards | `k` ∈ {0, 1, 2, 4, 8, 16} guards (`k` = 0 is the unguarded `updateRevRegEntry`) | `c` = 4, every client revokes in its own registry, the guarded registries are never written |
| W2, load | `c` ∈ {1, 4, 16, 32} clients | `k` = 2 |
| W3, contention | a background writer updates one hot registry H at `r` ∈ {0, 0.5, 1, 2, 5} writes/s | `k` = 1 (every client guards H), `c` = 4 |

Each point gets 10 s warm-up, 60 s measured, 3 repetitions. Registries, DIDs and genesis entries
are created before the timed window. Every write carries a fresh DID-JWT.

The orderer cuts a block after `BATCH_TIMEOUT` (default 250 ms; Fablo's own value is 2 s) or after
10 transactions, whichever comes first. In W3 each client waits a uniform random 0 to
`BENCH_W3_JITTERMS` ms (default: the batch timeout) before it reads H, so the clients do not all
read H right after a block commits. W1 and W2 clients do not wait. A reference run repeats W1 at
`k` ∈ {0, 4, 16} on `1org-solo` with Fablo's 2 s.

Files:

- `src/test/java/com/example/vdr/bench/ScalabilityBench.java` is the load generator. Its name matches neither
  `*Test` nor `*IT`, so `mvn test` and `mvn -Pe2e verify` never run it.
- `bench/fablo/1org-solo.json` is the root `fablo-config.json` (1 org, 2 peers, Solo).
  `bench/fablo/3org-raft.json` has 3 orgs × 2 peers, 3 Raft orderers, and a 2-of-3 endorsement policy.
  Both differ from the root config only in the chaincode `directory` and in the image tag
  `vdr-chaincode:bench`, so a bench build never replaces the image the root test network uses.
- `bench/run-matrix.sh <topology>` builds the chaincode image, generates the topology, sets
  `BatchTimeout` in its `configtx.yaml`, brings it up, runs W1–W3, and writes
  `results/<topology>/<timestamp>-bt<batch timeout>/`. It tears the network down on exit, also
  after a failure or Ctrl-C. It refuses to start if any `*.example.com` container already exists.

Output in `results/<topology>/<timestamp>-bt<batch timeout>/`:

- `transactions.csv` has one row per transaction submitted in a measured window. Columns: run id,
  topology, batch timeout (ms), workload, k, c, r, repetition, client (`c<i>` or `writer`), submit and final time (epoch ms),
  outcome, validation code (`VALID`, `MVCC_READ_CONFLICT`, …), and error code. The error code is
  `FAILED_PRECONDITION` when a stale guard was refused at endorsement.
- `summary.csv` has one row per point and repetition. It holds committed client tx/s and the p50/p95/p99
  latency of committed client transactions in ms. It also has the client counts by outcome, the
  `FAILED_PRECONDITION` count, and the writer's committed and total writes. It has the same
  leading columns as `transactions.csv`.
- `environment.txt` holds the batch timeout, `uname -a`, CPU and memory, the Docker engine version, the container
  images, and the chaincode version, image id and commit.
- `bench.log` is the Maven and bench console output, with the jitter seeds of each W3 point.

## 1. Prepare a fresh Ubuntu 22.04 or 24.04 VM

Recommended size: 8 vCPU, 16 GB RAM, 50 GB disk. One machine runs everything, so there is no
network cost between nodes.

```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk-headless maven git curl
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER" && newgrp docker      # or log out and back in
sudo curl -Lf https://github.com/hyperledger-labs/fablo/releases/download/2.6.0/fablo.sh \
  -o /usr/local/bin/fablo && sudo chmod +x /usr/local/bin/fablo
java -version && mvn -v && docker run --rm hello-world && fablo version
```

You do not need Node. Fablo runs from its own Docker image, and `fablo up` pulls the Fabric 2.5.15
images.

## 2. Copy the code (the repositories are private, so no `git clone` from GitHub)

The bench worktree has everything it needs: the chaincode sources and `bench/`.

On the Mac:

```bash
cd ~/Documents/Projects/vdr-network/fabric-vdr-core-bench
git bundle create /tmp/vdr-bench.bundle bench/scalability
scp /tmp/vdr-bench.bundle USER@VM:
```

On the VM:

```bash
git clone -b bench/scalability ~/vdr-bench.bundle fabric-vdr-core-bench
cd fabric-vdr-core-bench
```

Without git: `scp -r` the `fabric-vdr-core-bench` folder, leaving out `target/` and `bench/work/`.

## 3. Run

Smoke test first, about 4 minutes:

```bash
WARMUP=5 DURATION=15 REPS=1 WORKLOADS="W1 W3" W1_K=0,16 W3_R=0,2,5 BATCH_TIMEOUT=250ms bench/run-matrix.sh 1org-solo
```

Then the full matrix on both topologies at 250 ms, and the 2 s reference. It runs unattended, so it
survives a dropped SSH session:

```bash
nohup sh -c 'BATCH_TIMEOUT=250ms bench/run-matrix.sh 1org-solo; BATCH_TIMEOUT=250ms bench/run-matrix.sh 3org-raft; WORKLOADS=W1 W1_K=0,4,16 BATCH_TIMEOUT=2s bench/run-matrix.sh 1org-solo' > run.log 2>&1 &
tail -f run.log
```

Expected runtime: about 2 hours 15 minutes. Each topology takes about 1 hour: 45 measured runs
× 70 s ≈ 53 min, plus about 3 min to build, start, provision and tear down. The reference takes
about 14 minutes: 9 runs × 70 s plus the start. The first run on a fresh VM also pulls the Fabric
images and the Maven dependencies, about 10 more minutes.

## 3b. Robustness runs (fault injection, 3org-raft only)

`WORKLOADS=F` runs `RobustnessBench`: the W2 load (`k` = 2, `c` = 4) plus a stream of mutually
guarded pairs, while `bench/fault.sh` kills part of the network 30 s into the measured window and
restarts it 30 s later. Faults: `none` (baseline), `peer` (peer0 of Org2), `org` (both Org2 peers),
`leader` (the current Raft leader), `quorum` (the leader and one follower: beyond what Raft
tolerates), and `solo` (guarded updates endorsed by Org1 alone, then by Org1 + Org2 as control).
Each run is 10 s warm-up + 120 s measured; 3 repetitions take about 35 minutes.

```bash
nohup env WORKLOADS=F bench/run-matrix.sh 3org-raft > run-faults.log 2>&1 &
python3 bench/analyze-faults.py results/3org-raft/<timestamp>-bt250ms    # after it finishes
```

Smoke test, about 6 minutes: `WORKLOADS=F REPS=1 F_DURATION=90 F_FAULTS=none,leader,solo bench/run-matrix.sh 3org-raft`.
Keep `F_DURATION` above 60, since the fault starts at 30 s and the restart comes at 60 s.

Extra output in the results directory: `pairs.csv` (one row per pair: both outcomes and whether
each registry advanced on the ledger; a pair where both advanced breaks the at-most-one guarantee),
`faults.csv` (fault and restart times, the containers touched, the versions of the pair registries
before and after), `solo.csv`. `analyze-faults.py` writes `faults-summary.csv` and `timeline.csv`.

## 4. Copy the results back

On the Mac:

```bash
scp -r USER@VM:fabric-vdr-core-bench/results ~/Documents/Projects/vdr-network/fabric-vdr-core-bench/
```

`bench/aggregate.py results <output dir>` writes one CSV per workload: the median and the min-max
spread over the repetitions of each point. It skips the robustness directories.

## 5. Tear down

`run-matrix.sh` already pruned its network. To make sure, and to free the disk:

```bash
docker ps -a --filter name=example.com            # should be empty
(cd bench/work/1org-solo && fablo prune); (cd bench/work/3org-raft && fablo prune)
docker system prune -af --volumes
```

Then release the VM and its disk in the BME Cloud (or tell Martin you are done with his machine).
