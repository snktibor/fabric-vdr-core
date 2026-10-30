#!/usr/bin/env bash
# Kills (down) or restarts (up) part of the 3org-raft network for RobustnessBench, and prints the
# containers it touched.
#
# Usage:  bench/fault.sh <down|up> <none|peer|org|leader|quorum>
#
#   peer    one endorsing peer of Org2
#   org     both peers of Org2: the 2-of-3 policy can still be met by Org1 and Org3
#   leader  the current Raft leader of my-channel1: 2 of 3 orderers stay, a new leader is elected
#   quorum  the leader and one follower: no Raft majority, beyond what Raft tolerates
#
# "down" sends SIGKILL (a crash, not a clean shutdown). It writes the touched containers to a state
# file, so "up" restarts the same ones even if the leader moved in between.
set -euo pipefail

ACTION="${1:?usage: $0 <down|up> <fault>}"
FAULT="${2:?usage: $0 <down|up> <fault>}"
CHANNEL="${CHANNEL:-my-channel1}"
STATE="${TMPDIR:-/tmp}/vdr-bench-fault-$FAULT"

orderers() { docker ps --format '{{.Names}}' | grep -E '^orderer[0-9]+\.' | sort; }

# Every orderer logs "Raft leader changed: <old> -> <new> channel=<ch> node=<own id>"; the leader is
# the orderer whose latest such line names itself as the new leader.
leader() {
  local o line
  for o in $(orderers); do
    line="$(docker logs "$o" 2>&1 | grep 'Raft leader changed' | grep "channel=$CHANNEL" | tail -1 || true)"
    [ -n "$line" ] || continue
    if [ "$(sed -E 's/.*-> ([0-9]+).*/\1/' <<<"$line")" = "$(sed -E 's/.*node=([0-9]+).*/\1/' <<<"$line")" ]; then
      echo "$o"
      return
    fi
  done
}

if [ "$FAULT" = none ]; then exit 0; fi

if [ "$ACTION" = up ]; then
  targets="$(cat "$STATE")"
  docker start $targets >/dev/null
  echo "$targets"
  exit 0
fi

case "$FAULT" in
  peer) targets="peer0.org2.example.com" ;;
  org) targets="peer0.org2.example.com peer1.org2.example.com" ;;
  leader | quorum)
    targets="$(leader)"
    [ -n "$targets" ] || { echo "No Raft leader found in the orderer logs" >&2; exit 1; }
    if [ "$FAULT" = quorum ]; then
      targets="$targets $(orderers | grep -vx "$targets" | head -1)"
    fi
    ;;
  *) echo "Unknown fault: $FAULT" >&2; exit 1 ;;
esac
echo "$targets" >"$STATE"
docker kill $targets >/dev/null
echo "$targets"
