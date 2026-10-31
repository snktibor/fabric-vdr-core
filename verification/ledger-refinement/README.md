# Concrete ledger refinement artifact

Run from any directory:

```sh
python3 /path/to/ledger-refinement/scripts/replay.py
```

The runner reuses the sibling reconstruction's pinned Lean 4.23.0 installation. It installs nothing and writes only under `ledger-refinement/`. It verifies all frozen inputs and all pinned executable/runtime hashes, freshly compiles every source module into a new directory with `--trust=0`, checks exact target types, audits every theorem constant in both namespaces, and emits `result.json`, `SUCCESS`, `theorem-inventory.json`, `THEOREMS.md` and logs. A failed replay removes the previous success marker before checking anything.

Set `RECONSTRUCTION_DIR` to reuse dependencies in another directory, such as the parent repository's existing `verification/lean`, and `LEAN_TOOLCHAIN_DIR` for the existing pinned installation:

```sh
RECONSTRUCTION_DIR=/path/to/code/verification/lean LEAN_TOOLCHAIN_DIR=/path/to/lean-4.23.0 python3 /path/to/code/verification/ledger-refinement/scripts/replay.py --check-package
```

The frozen labels `../lean-reconstruction/...` resolve through `RECONSTRUCTION_DIR`; their expected hashes remain unchanged. Other parent traversal and absolute seal paths are rejected. Dependency hashing and compilation both use that selected directory. No duplicate source tree or symlink is required in the repository.

Pass `--check-package` to the same script to replay the generated package in a new directory, verify every archived file hash, then deliberately corrupt the relocated frozen model and confirm that replay fails and removes its previous success marker. Evidence is written to `artifacts/portability.json`; the original frozen input is never changed.

`artifacts/ledger-refinement-source.tar.gz` contains source files, unchanged dependency sources, frozen seals, the toolchain lock and successful evidence. It contains no Lean toolchain, compiled object files or Java binaries. `artifacts/package.json` gives its digest and every archived file digest. Extract it anywhere and point the same runner at an existing matching Lean installation:

```sh
LEAN_TOOLCHAIN_DIR=/absolute/path/to/lean-4.23.0 python3 ledger-refinement-package/ledger-refinement/scripts/replay.py
```

The complete runtime/executable lock is checked even with that override. Supply the Linux x86-64 release identified by the lock; the runner does not download or silently select another version.

Read [REPORT.md](REPORT.md) for status and boundaries, [CONTRACT.md](CONTRACT.md) for the initial semantic choices, [CORRECTION.md](CORRECTION.md) for the preserved/refuted precedence error, and [ATOMIC-CONTRACT.md](ATOMIC-CONTRACT.md) for atomic nonce and other extra-key writes. The implementation-facing result is `LedgerRefinement.Atomic.no_bifurcation`; it is a theorem about a concrete ledger semantic model, not an automatically verified Java/Fabric implementation theorem.
