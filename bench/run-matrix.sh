#!/usr/bin/env bash
# Runs the scalability matrix on one Fablo topology and tears the network down, also on failure.
#
# Usage:  bench/run-matrix.sh <1org-solo|3org-raft>
#
# The defaults are the full matrix. Every axis can be shrunk through the environment, e.g. the
# smoke test:
#   WARMUP=5 DURATION=15 REPS=1 W1_K=0,4 W2_C=1,4 W3_R=0,2 bench/run-matrix.sh 1org-solo
#
#   WORKLOADS  workloads to run            (default "W1 W2 W3"; F, the robustness runs, only on
#              3org-raft and only when named: WORKLOADS=F)
#   W1_K W1_C  guards / clients of W1      (default 0,1,2,4,8,16 / 4)
#   W2_K W2_C  guards / clients of W2      (default 2 / 1,4,16,32)
#   W3_C W3_R  clients / writes per s, W3  (default 4 / 0,0.5,1,2,5)
#   F_FAULTS F_DURATION                    (default none,peer,org,leader,quorum,solo / 120)
#   COMMIT_STATUS_S  the clients' commit-status deadline in seconds (default 60)
#   WARMUP DURATION REPS                   (default 10 / 60 / 3, seconds and count)
#   BATCH_TIMEOUT  orderer BatchTimeout    (default 250ms; the 2 s reference: BATCH_TIMEOUT=2s)
#   BENCH_W3_JITTERMS  max random wait of a W3 client before it reads H (default: the batch timeout)
#   FABLO      Fablo launcher              (default "fablo" on PATH)
set -euo pipefail

TOPOLOGY="${1:?usage: $0 <1org-solo|3org-raft>}"
BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$BENCH_DIR")"
CONFIG="$BENCH_DIR/fablo/$TOPOLOGY.json"
WORK_DIR="$BENCH_DIR/work/$TOPOLOGY"
BATCH_TIMEOUT="${BATCH_TIMEOUT:-250ms}"
OUT="$REPO_DIR/results/$TOPOLOGY/$(date +%Y%m%d-%H%M%S)-bt$BATCH_TIMEOUT"
FABLO="${FABLO:-fablo}"
IMAGE="vdr-chaincode:bench"

[ -f "$CONFIG" ] || { echo "No such topology: $CONFIG" >&2; exit 1; }
[[ "$BATCH_TIMEOUT" =~ ^([0-9]+)(ms|s)$ ]] \
  || { echo "BATCH_TIMEOUT must look like 250ms or 2s: $BATCH_TIMEOUT" >&2; exit 1; }
BATCH_TIMEOUT_MS="${BASH_REMATCH[1]}"
[ "${BASH_REMATCH[2]}" = ms ] || BATCH_TIMEOUT_MS=$((BATCH_TIMEOUT_MS * 1000))
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

echo "==> Starting $TOPOLOGY with BatchTimeout $BATCH_TIMEOUT"
# Fablo hardcodes BatchTimeout: 2s. Generate, patch configtx.yaml, then "up" without a config,
# which starts from the existing fablo-target/ instead of regenerating it.
"$FABLO" generate "$CONFIG"
CONFIGTX="$WORK_DIR/fablo-target/fabric-config/configtx.yaml"
lines="$(grep -c '^ *BatchTimeout: ' "$CONFIGTX")"
sed -i.orig -E "s/^( *BatchTimeout: ).*/\1$BATCH_TIMEOUT/" "$CONFIGTX" && rm "$CONFIGTX.orig"
if [ "$lines" -lt 1 ] || [ "$(grep -c "^ *BatchTimeout: $BATCH_TIMEOUT\$" "$CONFIGTX")" != "$lines" ]; then
  echo "Setting BatchTimeout in $CONFIGTX failed:" >&2
  grep -n 'BatchTimeout' "$CONFIGTX" >&2
  exit 1
fi
"$FABLO" up

{
  echo "date_utc: $(date -u +%FT%TZ)"
  echo "topology: $TOPOLOGY"
  echo "batch_timeout: $BATCH_TIMEOUT"
  echo "commit_status_deadline_s: ${COMMIT_STATUS_S:-60}"
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
  (cd "$REPO_DIR" && mvn -B test -Dtest="${BENCH_CLASS:-ScalabilityBench}" -Djacoco.skip=true \
    -Dorg.slf4j.simpleLogger.defaultLogLevel=warn \
    -Dvdr.e2e.cryptoPath="$WORK_DIR/fablo-target/fabric-config/crypto-config/peerOrganizations/org1.example.com" \
    -Dbench.topology="$TOPOLOGY" -Dbench.out="$OUT" -Dbench.batchTimeoutMs="$BATCH_TIMEOUT_MS" \
    -Dbench.commitStatusSeconds="${COMMIT_STATUS_S:-60}" \
    -Dbench.warmup="${WARMUP:-10}" -Dbench.duration="${DURATION:-60}" -Dbench.reps="${REPS:-3}" \
    "$@") 2>&1 | tee -a "$OUT/bench.log"
}

for workload in ${WORKLOADS:-W1 W2 W3}; do
  echo "==> $workload on $TOPOLOGY"
  case "$workload" in
    W1) bench -Dbench.workload=W1 -Dbench.k="${W1_K:-0,1,2,4,8,16}" -Dbench.c="${W1_C:-4}" ;;
    W2) bench -Dbench.workload=W2 -Dbench.k="${W2_K:-2}" -Dbench.c="${W2_C:-1,4,16,32}" ;;
    W3) bench -Dbench.workload=W3 -Dbench.c="${W3_C:-4}" -Dbench.r="${W3_R:-0,0.5,1,2,5}" ;;
    F)
      [ "$TOPOLOGY" = 3org-raft ] || { echo "F needs 3org-raft (Org2 peers, Raft orderers)" >&2; exit 1; }
      DURATION="${F_DURATION:-120}" BENCH_CLASS=RobustnessBench \
        bench -Dbench.faults="${F_FAULTS:-none,peer,org,leader,quorum,solo}" ;;
    *) echo "Unknown workload: $workload" >&2; exit 1 ;;
  esac
done

echo "==> Results in $OUT"
