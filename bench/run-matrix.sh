#!/usr/bin/env bash
# Runs the scalability matrix on one Fablo topology and tears the network down, also on failure.
#
# Usage:  bench/run-matrix.sh <1org-solo|3org-raft>
#
# The defaults are the full matrix. Every axis can be shrunk through the environment, e.g. the
# smoke test:
#   WARMUP=5 DURATION=15 REPS=1 W1_K=0,4 W2_C=1,4 W3_R=0,2 bench/run-matrix.sh 1org-solo
#
#   WORKLOADS  workloads to run            (default "W1 W2 W3")
#   W1_K W1_C  guards / clients of W1      (default 0,1,2,4,8,16 / 4)
#   W2_K W2_C  guards / clients of W2      (default 2 / 1,4,16,32)
#   W3_C W3_R  clients / writes per s, W3  (default 4 / 0,0.5,1,2,5)
#   WARMUP DURATION REPS                   (default 10 / 60 / 3, seconds and count)
#   FABLO      Fablo launcher              (default "fablo" on PATH)
#   RESULTS_DIR  absolute output root      (default <repo>/results)
set -euo pipefail

TOPOLOGY="${1:?usage: $0 <1org-solo|3org-raft>}"
BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$BENCH_DIR")"
CONFIG="$BENCH_DIR/fablo/$TOPOLOGY.json"
WORK_DIR="$BENCH_DIR/work/$TOPOLOGY"
OUT="${RESULTS_DIR:-$REPO_DIR/results}/$TOPOLOGY/$(date +%Y%m%d-%H%M%S)"
FABLO="${FABLO:-fablo}"
IMAGE="vdr-chaincode:bench"

[ -f "$CONFIG" ] || { echo "No such topology: $CONFIG" >&2; exit 1; }
# Every Fablo network here uses the same *.example.com container names, so a network that is
# already there would clash with ours; it is not ours to stop.
if [ -n "$(docker ps -aq --filter name=example.com)" ]; then
  echo "A Fabric network (*.example.com containers) already exists. Stop it first; not touching it." >&2
  exit 1
fi

mkdir -p "$WORK_DIR" "$OUT"
cd "$WORK_DIR"   # Fablo puts fablo-target/ into the current directory

teardown() {
  echo "==> Tearing the $TOPOLOGY network down"
  "$FABLO" prune || true
  local left
  left="$(docker ps -aq --filter name=example.com)"
  [ -z "$left" ] || docker rm -f $left >/dev/null
}
trap teardown EXIT
trap 'exit 130' INT TERM

echo "==> Building the chaincode image $IMAGE"
(cd "$REPO_DIR" && mvn -q -B clean package -DskipTests)
docker build -q -t "$IMAGE" "$REPO_DIR"

echo "==> Starting $TOPOLOGY"
"$FABLO" up "$CONFIG"

{
  echo "date_utc: $(date -u +%FT%TZ)"
  echo "topology: $TOPOLOGY"
  echo "uname: $(uname -a)"
  echo "host_cpus: $(getconf _NPROCESSORS_ONLN)"
  if command -v free >/dev/null; then
    echo "host_memory_bytes: $(free -b | awk '/^Mem:/{print $2}')"
  else
    echo "host_memory_bytes: $(sysctl -n hw.memsize)"
  fi
  echo "docker_engine: $(docker version --format '{{.Server.Version}}')"
  echo "docker_cpus_memory_bytes: $(docker info --format '{{.NCPU}} {{.MemTotal}}')"
  echo "java: $(java -version 2>&1 | head -1)"
  echo "chaincode: $(grep -o '"version": "[^"]*"' "$CONFIG" | head -1), image $IMAGE $(docker image inspect -f '{{.Id}}' "$IMAGE"), commit $(git -C "$REPO_DIR" rev-parse HEAD)"
  echo "containers:"
  docker ps --filter name=example.com --format '  {{.Names}} {{.Image}}' | sort
} > "$OUT/environment.txt"

# The slf4j level also quiets Maven's own INFO lines; warnings, errors and bench: lines still show.
bench() {
  (cd "$REPO_DIR" && mvn -B test -Dtest=ScalabilityBench -Djacoco.skip=true \
    -Dorg.slf4j.simpleLogger.defaultLogLevel=warn \
    -Dvdr.e2e.cryptoPath="$WORK_DIR/fablo-target/fabric-config/crypto-config/peerOrganizations/org1.example.com" \
    -Dbench.topology="$TOPOLOGY" -Dbench.out="$OUT" \
    -Dbench.warmup="${WARMUP:-10}" -Dbench.duration="${DURATION:-60}" -Dbench.reps="${REPS:-3}" \
    "$@") 2>&1 | tee -a "$OUT/bench.log"
}

for workload in ${WORKLOADS:-W1 W2 W3}; do
  echo "==> $workload on $TOPOLOGY"
  case "$workload" in
    W1) bench -Dbench.workload=W1 -Dbench.k="${W1_K:-0,1,2,4,8,16}" -Dbench.c="${W1_C:-4}" ;;
    W2) bench -Dbench.workload=W2 -Dbench.k="${W2_K:-2}" -Dbench.c="${W2_C:-1,4,16,32}" ;;
    W3) bench -Dbench.workload=W3 -Dbench.c="${W3_C:-4}" -Dbench.r="${W3_R:-0,0.5,1,2,5}" ;;
    *) echo "Unknown workload: $workload" >&2; exit 1 ;;
  esac
done

echo "==> Results in $OUT"
