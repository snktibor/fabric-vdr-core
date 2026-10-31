A frozen conditional bridge from Java service observations to the unchanged atomic ledger.

In the integrated repository, supply its pinned toolchain and check the canonical dependencies first:

```sh
python3 /path/to/repo/verification/service-trace-bridge/scripts/check-integrated-deps.py
LEAN_TOOLCHAIN_DIR=/path/to/repo/verification/lean/tools/lean-4.23.0 python3 /path/to/repo/verification/service-trace-bridge/scripts/replay.py
```

The standalone command and sibling-directory default below apply to the original work layout. For a relocated package, set `LEAN_TOOLCHAIN_DIR` explicitly.

Run from any working directory:

```sh
python3 /path/to/service-trace-bridge/scripts/replay.py
```

The default toolchain is the sibling `lean-reconstruction/tools/lean-4.23.0`. For a relocated source package set `LEAN_TOOLCHAIN_DIR` to that pinned installation. The runner verifies the complete executable/runtime lock, all frozen definitions and dependency hashes, compiles every module into a new directory with `--trust=0`, checks the 12 exact frozen target types and audits all named/generated theorem dependencies. Outputs are [result.json](result.json), [theorem-inventory.json](theorem-inventory.json), [replay log](logs/replay.log) and [axiom log](logs/axioms.log). A failed replay replaces the result with a failure and removes the current theorem inventory.

The [contract](CONTRACT.md) fixes the scope. [BridgeModel.lean](BridgeModel.lean) defines the immutable trace, granular premises and raw interpreter. [BridgeSpec.lean](BridgeSpec.lean) holds the frozen targets; [BridgeProofs.lean](BridgeProofs.lean) proves them. [BridgeWitnesses.lean](BridgeWitnesses.lean) supplies codec-parameterized nonvacuity. [JML-PREMISES.md](JML-PREMISES.md) maps every premise to exact JML clauses or names the unproved boundary. [REPORT.md](REPORT.md) records completion and limits.

`capture-jml.py` is optional evidence collection in the original workspace. It reads the other agent's artifacts and production source, writes snapshots only here and does not run Java proofs. It is not needed for portable Lean replay. `check.sh` is a development helper using the existing build; only `replay.py` provides fresh replay evidence.
