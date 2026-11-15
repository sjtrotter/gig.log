# census-contract/ — the census wire contract (#1173)

This plain-JVM included build owns the skeleton DTO/schema, fingerprint, hash,
id/class/kind grammars, text/case folds, anonymous-wrapper set, sensitive-marker
DATA, and the conformance golden. The phone's UiNode scan logic stays in
`:core:pipeline`; customer-PII policy (`PiiShapes`) stays in `:domain`.

Apache-2.0 lets both the PolyForm-Shield app and the AGPL `dashbuddy-census` server
consume the same contract. This resolves ADR-0011 open question 1 on 2026-10-01.

The app includes `includeBuild("census-contract")` in its root settings and
`:domain` declares `api("cloud.trotter.census:contract:0.0.0-local")`. Gradle
automatically substitutes the included build for those coordinates, exposing the
contract transitively to the app's other modules. Run its tests through the root
`censusContractTest` lifecycle task.

The server pins a gig.log checkout and uses
`includeBuild("../DashBuddy/census-contract")` plus the same dependency coordinates.
Keep the checkout's `gradle/libs.versions.toml` beside the build: its settings
import that shared catalog. Pin the checkout revision so app/server contract and
conformance data evolve together.

To regenerate `conformance/skeletons.jsonl.gz` from the app's committed snapshot
corpus, run from the gig.log root:

```bash
./gradlew :app:testDebugUnitTest --tests "*CensusGoldenExportTest*" -DexportCensusGolden=true
./gradlew :app:testDebugUnitTest --tests "*CensusGoldenExportTest*"
```

The export deliberately fails after writing: review the decompressed JSONL diff,
then rerun without the flag and commit the gzip artifact. Each fixture has one
path-sorted record containing either its refusal reason or its fingerprint,
sorted distinct hashes, and embedded skeleton. The server's ingest suite replays
this golden; it contains no plaintext text slots.

## Health report

[`cloud.trotter.census.contract.health`](src/main/kotlin/cloud/trotter/census/contract/health/)
owns the daily health report (`POST /v1/health` item): `HealthReportDto`, its bounds and its
checks (`HealthReportSchema`), and the version and rule-id grammars (`WireTokenGrammars`). The
phone encodes through `HealthReportSchema.encode` and refuses an oversized report with `fits`;
the server accepts through `check`, passing its own day window and platform allowlist. The
optional `parseShortfall` map (census#64) carries, per rule id, the number of shortfalls among
that rule's last `PARSE_SAMPLE` (20) eligible attempts of the UTC day, `0..20`. A rule with
fewer than 20 eligible attempts that day is left out, so the most the field says about volume
is "this rule had at least 20 eligible attempts today": it carries no attempt total. Its rule
ids are at most 96 characters (legacy `ruleCounts` keeps its own bound). A report without it
encodes exactly as before. The optional `shadow` and `probeRefused` lists (census#102) carry shadow-rule
tallies and refused probes: at most 32 entries each, unique by `(generation, rule, digest)`, 16-hex
identifiers, rule ids under the report's own platform, and each metric `0..20` over its own sample, left
out when unavailable. A report too large to send drops `shadow`, then `probeRefused`, then
`parseShortfall` (`HealthReportSchema.encodeFitting`). `conformance/health-report-vectors.jsonl`
pins the verdicts both sides must reach.

## Consuming from another build

`census-contract/settings.gradle.kts` reads the version catalog at `../gradle/libs.versions.toml`, so the build must be included from a COMPLETE gig.log checkout (`includeBuild("<path>/DashBuddy/census-contract")`), never vendored as a bare directory. The `dashbuddy-census` server pins that checkout to a SHA in CI.

## Wire authentication

[`cloud.trotter.census.contract.auth`](src/main/kotlin/cloud/trotter/census/contract/auth/)
owns the install ID/secret grammars, bearer format, authentication header names,
and byte-exact request signing shared by Android and `dashbuddy-census`.
Secrets are canonical unpadded base64url; callers supply the random bytes.
HMAC uses the decoded key, while the stored credential digest hashes the encoded
secret string as UTF-8.

[`RequestSignerTest`](src/test/kotlin/cloud/trotter/census/contract/auth/RequestSignerTest.kt)
locks the server's worked vector: key bytes `00 01 … 1f`, `POST /v1/nonce`,
timestamp `1790899200`, and an empty body produce
`v1=bcf991bb04da013f724bd200abe7e8352c68dbd7155aacbcb6a1a1dff94cbefd`.
The canonical string has three LF separators, strips the path's query, and ends
with the lowercase SHA-256 body digest without a trailing newline.
