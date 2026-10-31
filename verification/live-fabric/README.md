# Independent live Fabric reproduction

Run `python3 reproduce.py`. This creates a new timestamped `runs/` directory with its own Docker project. It runs the six existing concurrency scenarios, then the full existing E2E suite only if those pass. It exports validated peer blocks, checks source/jar classes, captures logs, cleans recorded resources, removes generated crypto, and runs `finalize.py`. Exit 0 requires validated `results.json` and `REPORT.md` after cleanup. Any test, evidence, capture, cleanup, sanitization or aggregation failure returns nonzero. `driver-exitcode.txt` records the outcome.

The harness needs Linux amd64, Docker with Compose, Python 3 with PyYAML, curl, tar, a JDK supporting compiler release 17 and Maven 3.9+. Fabric 2.5.15 binaries, official peer/orderer images and the Java 17 chaincode base are pinned by version/hash/digest and provisioned locally. No global install is used. Review the Python scripts and configuration before running. Only labeled, recorded resources are removed. Gateway port 7041 and orderer-admin port 17053 must be free on loopback. Do not run multiple harnesses concurrently because the existing tests fix the Gateway port.

## Repository and build inputs

- `TDK_PROOF_REPO`: absolute repository path. The integrated location `verification/live-fabric` defaults to its repository root. The original work-area location retains its local default. Set this explicitly after other relocation.
- `TDK_PROOF_BUILD`: optional build directory. Defaults to the original repository parent's `work/tibor-lean-grind/java-production/build`. It supplies `latest-verification.txt`, `apache-maven-3.9.9/bin/mvn` and an optional `m2` seed.
- `TDK_PROOF_SNAPSHOT`: optional absolute **project directory** containing `pom.xml`, `src/` and the successfully packaged `target/chaincode.jar`. This bypasses `latest-verification.txt`. Without it, the pointer contains the absolute verification-run directory, and its `project/` child supplies that layout.
- `MAVEN_EXECUTABLE`: optional absolute Maven executable path. It overrides the Maven under `TDK_PROOF_BUILD`.
- `TDK_PROOF_M2_SEED`: optional read-only Maven cache seed copied into the new run. Defaults to `TDK_PROOF_BUILD/m2` when available. An absent seed starts an empty task-local cache and downloads dependencies from Maven Central.

The snapshot's complete src tree and pom.xml must exactly match the current repository. The snapshot jar is deployed unchanged. Freshly compiled application classes must exactly match its entries. Dockerfile and entrypoint.sh are read from the current repository; the Dockerfile copy only pins its existing Java 17 base digest.

A source-only checkout does not need the original machine's build directory. Supply an existing local Maven executable and create an isolated snapshot:

```sh
python3 verification/live-fabric/prepare_snapshot.py --repo /absolute/repository --maven /absolute/apache-maven/bin/mvn
```

The command copies src/pom into a new owned `runs/build-.../project`, builds there with `package -DskipTests`, records command/PID/log/exit, and prints `TDK_PROOF_SNAPSHOT`, `MAVEN_EXECUTABLE` and `TDK_PROOF_M2_SEED` values. Export those values and set `TDK_PROOF_REPO`, then run `python3 verification/live-fabric/reproduce.py`. It never builds in or changes the source repository. Keep the JDK/Maven runtime available through PATH. No global Maven repository is written.

## Evidence and limits

`results.json`, `REPORT.md`, source/deployment/class manifests, Cucumber/Failsafe reports, race transaction IDs/codes, binary/decoded peer blocks and command logs are written inside each run. Runtime version strings come from the actual peer/orderer containers. Both peers must return identical race blocks. The finalizer derives jar hash, class count and block numbers from that run's evidence and checks original container survival. Cached official images remain; custom task containers/network/image and any sanitizer are removed.

This is an independent single-organization, two-peer reproduction, separate from the repository's Fablo network. It is not a whole-service proof. CCaaS is plaintext inside the isolated network; Gateway and Fabric traffic use TLS. The exact existing test oracle is in KICKOFF.md. Generated private keys and live ledger stores are deleted after evidence export. Runtime directories are ignored by Git. Copy source/scripts/docs and public evidence selectively; never include generated crypto.

`python3 test_runner.py` tests wrapper failure propagation, nested process timeout cleanup and a build override without Docker. The combined focused/full tests phase has a 30-minute budget within the workflow's 45-minute job limit. The original real network run passed six focused and 43 full scenarios; the improved wrapper was checked with these synthetic cases instead of repeating the whole network run.

The committed `evidence/20261026` files are historical. Their source and class hashes predate the package rename and the October 31 rebase; they do not certify the current jar. Fresh runs use the current `io.github.snktibor.vdr` namespace.
