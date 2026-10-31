# Publication audit, October 31, 2026

The 34 gitleaks findings introduced by [the verification commit](https://github.com/snktibor/fabric-vdr-core/commit/db8f8028eb077653cbef3c7c7a0eeaae394e2ee7) are individually accepted in the root `.gitleaksignore`. No path-wide or rule-wide suppression is used.

## SHA-256 values

All 18 `generic-api-key` findings are 64-hex SHA-256 values in the Lean toolchain locks or the archived source, class and deployment manifests. They identify public files; they are not API credentials.

## Recorded test JWTs

The 16 `jwt`/`jwt-base64` findings represent eight tokens, two per retained block. They are ES256 DID-JWTs produced for the isolated E2E network. Their validity was 300 seconds, and all expired on October 25, 2026 UTC:

| Archived block | Token lines | Expiration UTC |
|---|---|---|
| `live-fabric/evidence/20261026/blocks/15.json` | 130, 298 | 23:21:24 |
| `live-fabric/evidence/20261026/blocks/21.json` | 131, 300 | 23:21:37 |
| `live-fabric/evidence/20261026/blocks/43.json` | 130, 298 | 23:22:23 |
| `live-fabric/evidence/20261026/blocks/54.json` | 138, 314 | 23:22:45 |

The nine relevant historical source files byte-match `source-hashes.json` at [the pre-reconstruction revision](https://github.com/snktibor/fabric-vdr-core/commit/347abd876a47caae5ee4517e216e21481dee78fa): `CryptoUtils`, `DidEntityHandler`, `ScenarioContext`, `TransactionExecutor`, `IndependentRegistryFixture`, `ConcurrencySteps`, `HooksSteps`, `E2EConfig` and `JwtBinding`. The source-manifest digest matches `deployment-manifest.json`.

In those sources, `DidEntityHandler.create` generates a fresh DID and P-256 key pair, holds the pair in the in-memory scenario map, and registers only its public JWK. `CryptoUtils.generateEcKeyPair` uses `KeyPairGenerator` on `secp256r1`; `buildDidJwt` signs with that scenario key. The concurrency fixture passes this same pair to the submitted transactions. These ephemeral DID keys are separate from Fabric MSP/TLS keys.

For all eight tokens, the audit recomputed the transaction digest from the exact block method/payload arguments and the nonce-write key from issuer/JTI. Each issuer matches a DID read, each key ID equals the issuer plus `#key-1`, and all eight JTIs are distinct. Binary-block hashes and validation flags match `ledger-race-evidence.json`.

The retained blocks do not include issuer DID registrations/public JWKs, so this audit cannot independently recheck JWT signatures. The disposable-key classification rests on the source-bound harness provenance and matching transaction evidence. It is not a claim of independent signature verification.

## Private-key and publication checks

Inspection of all 150 tracked or unignored verification files at the verification commit, including 5,064 decoded base64 JSON strings, found no private-key PEM markers, private JWK components or key-material filenames. Ignored local runtime/build trees are not publication inputs. The recorded `sanitization.json` reports generated `organizations` and `data` directories absent after cleanup; this audit does not claim absolute proof of key destruction.

The private paper URL and machine-specific paths have been removed from the publication tree. Original frozen semantic contracts are unchanged; their availability statements describe the reconstruction date. The public original Lean model is linked separately from the historical paper provenance.

The three existing documentation public-key exceptions from [the publication-hygiene PR](https://github.com/snktibor/fabric-vdr-core/pull/3) are retained verbatim so the allowlist composes with that cleanup. No local backup-stash fingerprints are allowlisted or pushed. A scanner traversing all local refs also sees those backup duplicates; the published branch history is checked separately.
