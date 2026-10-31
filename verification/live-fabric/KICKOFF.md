# Live Fabric verification contract

This is an independent reproduction, not Tibor's unavailable original network. Scope is the exact current production build and existing E2E tests. All files written by this task stay in live-fabric; source and earlier work remain read-only. No commits, pushes or PRs.

1. Bind the source/pom snapshot and existing shaded jar by SHA-256. Copy tests into this directory.
2. Provision official Fabric 2.5.15 binaries/images and use pinned official fabric-samples configuration as a reviewed starting point. Use one Org1 with two peers, one Raft orderer, channel my-channel1, chaincode vdrchaincode and contract VdrContract. An Org1 peer endorsement suffices. Expose peer0 only at loopback port 7041 after a conflict check. Distinct container names and tdkproof ownership labels isolate this network.
3. Deploy the unchanged production jar with Java 17 in CCaaS mode. Record image, package, source and jar hashes.
4. Run all six existing @concurrency scenarios first. Frozen oracle: A0 sequential updates reach version 2; A1 same-key race and A2 self-guard race each produce one VALID and one MVCC_READ_CONFLICT; B0 sequential cross-check permits only the first update; B1u unguarded independent updates both produce VALID and version 1; B1 reciprocal guarded updates produce exactly one VALID and one MVCC_READ_CONFLICT, with versions 1/0. Network or endorsement failures do not satisfy the race oracle. Do not edit the tests to obtain a pass.
5. Run the full existing E2E suite only after focused success. Preserve logs, command lines, PIDs, exit codes and scenario inventory. Capture actual commit validation evidence.
6. Save logs then remove only explicitly recorded task-owned Docker container/network/image IDs. Compare unrelated service IDs and running state with the initial snapshot. No prune, global stop/down, or downloaded cleanup script.

The official sample's two-organization topology would add unnecessary deployment work. A single organization with two peers matches the stated endorsement model and tests the same read/write MVCC conflict mechanism. This does not test multi-organization endorsement or reconstruct the original environment.
