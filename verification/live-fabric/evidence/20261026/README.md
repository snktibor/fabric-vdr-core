# Recorded independent Fabric run

All six concurrency scenarios and all 43 end-to-end scenarios passed on an isolated Fabric 2.5.15 network with two peers, one organization and one Raft orderer. The full run had no skipped scenarios. This is supporting runtime evidence, separate from the KeY and Lean proofs.

[results.json](results.json) records the outcomes and cleanup status. [deployment-manifest.json](deployment-manifest.json) binds the source hashes, exact jar, runtime versions and image digests. [class-correspondence.json](class-correspondence.json) checks all 42 application classes against the deployed jar. The complete [source hashes](source-hashes.json) include the unchanged test inputs.

The unguarded independent pair both committed. The reciprocal guarded pair produced one `VALID` and one `MVCC_READ_CONFLICT`. The [transaction outcomes](ledger-race-evidence.json) are checked against exported peer-block validation bytes; [read/write sets](rwset-evidence.json) show the reciprocal dependency. Both peers returned identical race blocks. The binary and decoded blocks are retained in [blocks](blocks/).

The [focused](focused-cucumber.json) and [full](full-cucumber.json) Cucumber reports retain individual scenario steps. This run used the existing tests without changing production or test sources. It does not cover all schedules, reproduce the unavailable original network or establish whole-chaincode verification.

Generated keys and live ledger stores were removed. Only selected public evidence is included here. The [runner guide](../../README.md) explains how to create a new isolated run; [wrapper checks](wrapper-tests.json) cover failure propagation without creating another network.
