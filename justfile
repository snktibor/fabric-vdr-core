# The commands behind the README's "Reproduce" section and the CI. The recipes that need a live
# Fabric network bring up the 1org-solo topology from bench/fablo and tear it down on exit.

set shell := ["bash", "-euo", "pipefail", "-c"]

# argos-formal, which holds the Lean model and its proofs, at the commit the paper cites.
formal_rev := "ae57a657d58a401d433a9475e4b5fc91e71baf37"
formal_dir := env("ARGOS_FORMAL_DIR", "out/argos-formal")
image := "vdr-chaincode:bench"
fablo := env("FABLO", "fablo")

default:
    @just --list

# The unit tests and the formatting check. No network, no Docker.
test:
    mvn -B spotless:check test

# Download argos-formal at formal_rev, unless formal_dir already holds it.
formal-src:
    #!/usr/bin/env bash
    set -euo pipefail
    [ -f "{{formal_dir}}/lakefile.toml" ] && exit 0
    git init -q "{{formal_dir}}"
    git -C "{{formal_dir}}" fetch -q --depth 1 https://github.com/snktibor/argos-formal.git {{formal_rev}}
    git -C "{{formal_dir}}" checkout -q FETCH_HEAD

# The Lean proofs (`lake build --wfail`), then the check that all eight mutations break them.
formal: formal-src
    cd "{{formal_dir}}" && lake build --wfail && ./mutations/check.sh

# The E2E suite (43 Cucumber scenarios, A0-B1 among them) on a live 1org-solo network.
e2e:
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -n "$(docker ps -aq --filter name=example.com)" ]; then
      echo "A Fabric network (*.example.com containers) already exists. Stop it first; not touching it." >&2
      exit 1
    fi
    mvn -q -B clean package -DskipTests
    docker build -q -t "{{image}}" .
    work="$PWD/bench/work/e2e"
    mkdir -p "$work"
    trap 'cd "$work" && {{fablo}} prune || true; left="$(docker ps -aq --filter name=example.com)"; [ -z "$left" ] || docker rm -f $left >/dev/null' EXIT
    (cd "$work" && {{fablo}} up "{{justfile_directory()}}/bench/fablo/1org-solo.json")
    mvn -B -Pe2e verify \
      -Dvdr.e2e.cryptoPath="$work/fablo-target/fabric-config/crypto-config/peerOrganizations/org1.example.com"

# Scalability W1-W3 on both topologies and the W1 reference at a 2 s batch timeout into results/ (~2 h); --smoke: 4 min into out/.
benchmark *flags:
    #!/usr/bin/env bash
    set -euo pipefail
    if [[ " {{flags}} " == *" --smoke "* ]]; then
      RESULTS_DIR="$PWD/out/results-smoke" WARMUP=5 DURATION=15 REPS=1 W1_K=0,4 W2_C=1,4 W3_R=0,2 \
        bench/run-matrix.sh 1org-solo
    else
      bench/run-matrix.sh 1org-solo
      bench/run-matrix.sh 3org-raft
      WORKLOADS=W1 W1_K=0,4,16 BATCH_TIMEOUT=2s bench/run-matrix.sh 1org-solo
    fi

# Fault injection on 3org-raft into results/, then the Raft faults with a 2 s commit-status deadline (~50 min).
robustness:
    WORKLOADS=F bench/run-matrix.sh 3org-raft
    WORKLOADS=F F_FAULTS=leader,quorum COMMIT_STATUS_S=2 bench/run-matrix.sh 3org-raft

# The committed measurements, one row per point and repetition (columns: bench/README.md).
results:
    #!/usr/bin/env bash
    set -euo pipefail
    shopt -s nullglob
    files=(results/*/*/summary.csv)
    if [ ${#files[@]} -eq 0 ]; then
      echo "No measurements committed under results/ yet; see results/README.md."
      exit 0
    fi
    for f in "${files[@]}"; do
      echo "== $f"
      cut -d, -f3- "$f" | column -t -s,
    done

# The CSVs behind the paper's scalability and robustness figures, from the committed runs, into
# out/figures/data/ in the layout of the paper's figures/data/. Python only; results/ stays untouched.
figures:
    #!/usr/bin/env bash
    set -euo pipefail
    out=out/figures/data
    rm -rf "$out" out/figures/faults-60s out/figures/faults-2s
    mkdir -p "$out/scalability" "$out/robustness"
    python3 bench/aggregate.py results "$out/scalability"
    python3 bench/analyze-faults.py results/3org-raft/20261029-005129-bt250ms out/figures/faults-60s
    python3 bench/analyze-faults.py results/3org-raft/20261029-110821-bt250ms out/figures/faults-2s
    cp out/figures/faults-60s/faults-summary.csv "$out/robustness/faults-deadline-60s.csv"
    cp out/figures/faults-2s/faults-summary.csv "$out/robustness/faults-deadline-2s.csv"
    python3 bench/bin-timeline.py \
      out/figures/faults-60s/timeline.csv:F-org-rep2:org \
      out/figures/faults-2s/timeline.csv:F-leader-rep1:leader \
      out/figures/faults-2s/timeline.csv:F-quorum-rep1:quorum > "$out/robustness/timeline-5s.csv"
    echo "Wrote $(find "$out" -name '*.csv' | wc -l) CSVs under $out"

# What the reproduce image runs: everything that needs no Fabric network.
check: test formal results figures

# Everything, live network included; `just reproduce --smoke` shortens the measurement.
reproduce *flags: check e2e (benchmark flags)
