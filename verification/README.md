# Verification artifacts

This directory packages the October 26 verification work. The production slice and its runners were adapted to the current `io.github.snktibor.vdr` namespace on October 31.

| Artifact | Checked scope | Reproduce from the repository root |
|---|---|---|
| [KeY](key/README.md) | 15 contracts on the exact current version helper, exception and record sources | `python3 verification/key/run.py` |
| [Lean protocol](lean/README.md) | 14 contracts in an independent protocol reconstruction | `bash verification/lean/scripts/setup-toolchain.sh` then `bash verification/lean/scripts/verify.sh` |
| [Ledger refinement](ledger-refinement/README.md) | Bounded application counters, ledger revisions and atomic extra writes | `RECONSTRUCTION_DIR="$PWD/verification/lean" python3 verification/ledger-refinement/scripts/replay.py --check-package` |
| [Service trace bridge](service-trace-bridge/README.md) | 12 conditional trace-to-ledger targets | `LEAN_TOOLCHAIN_DIR="$PWD/verification/lean/tools/lean-4.23.0" python3 verification/service-trace-bridge/scripts/replay.py` |
| [Isolated Fabric harness](live-fabric/README.md) | Runtime scenarios and captured ledger evidence | Follow its snapshot instructions or manually dispatch its workflow |

The full Java service and its connection to the Lean model remain unproved. The private service JML clause is an intended obligation, not an accepted contract. The bridge's frozen Java snapshots, clause catalogs and the October 26 Fabric evidence retain their historical bytes; they are not current-source certification. The original Lean model is now public in [argos-formal](https://github.com/snktibor/argos-formal), but equivalence with this independent reconstruction remains open.

The rebase preserves main's refusal of `Long.MAX_VALUE` updates through the checked helper and additionally rejects negative stored versions before mutation. MAX-1 still succeeds. The machine-readable failure code remains `FAILED_PRECONDITION`; the exhausted-version message now comes from the helper.

CI freshly proves/replays the KeY slice and the Lean artifacts. The Fabric workflow runs synthetic harness checks on pull requests; a live network requires manual dispatch. Local October 31 validation did not rerun the live network.
