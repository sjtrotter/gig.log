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
encodes exactly as before. The optional `shadowDaily` list (census#104) carries, per candidate rule loaded that
day, zero visits included, coarse levels the phone computes (`ShadowLevels`): `visits` and `evaluated` as
`"0"`, `"1-4"`, `"5-19"` or `"20+"`, and each metric as `none`, `some`, `half_or_more` or `all` of the
visits where it could be measured, left out when there were none. `shadowDailyTruncated: true` says the
phone held more candidates than the list carries. `probeRefused` (census#102) names refused probes. Both
lists hold at most 32 entries, unique by `(generation, rule, digest)`, with 16-hex identifiers and rule ids
under the report's own platform. A report too large to send drops `shadowDaily` with its flag, then
`probeRefused`, then `parseShortfall` (`HealthReportSchema.encodeFitting`). `conformance/health-report-vectors.jsonl`
pins the verdicts both sides must reach.

## Matched skeletons

`uinode.matched.v1` (#1573, census#69) is the skeleton of a screen a recognition rule still matches while that rule
fails to read a required value on it. It is a `uinode.skeleton.v2` body plus a required top-level `ruleId`
(`UiSkeletonDto.ruleId`, `SkeletonSchema.SCHEMA_ID_MATCHED`), under its own schema id rather than a step of the
`SkeletonVersion` ladder, so the unrecognized-screen stream keeps its schema, sample keys and this directory's golden.
The rules both sides apply:

- `ruleId` is present exactly when the schema is `uinode.matched.v1`. A v1 or v2 screen item carrying the key at all,
  `null` included, is refused before the typed decode (`V2WireKeys`).
- `ruleId` is a rule of the item's own platform: at most 96 characters, the `WireTokenGrammars.ruleId` grammar and the
  `"<platform>."` prefix (`WireTokenGrammars.isRuleIdFor`, which the health report's shadow entries also use). The
  server's reason for a bad one is `bad_rule_id`.
- The body follows the v2 rules, and a matched item never projects to v1 (`toV1()` throws).
- The fingerprint covers the tree only, so a matched skeleton and the same layout sent as unrecognized share it.
- `CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS` lists the matched id last; the phone builds one only when the server's
  policy lists it.

`conformance/matched-vectors.jsonl` holds one vector per line, `{"name", "item", "contract", "server"}`: the contract
verdict (`accept` or `refuse`, checked by `MatchedSkeletonContractTest`) and the server's (`accepted` or its rejection
reason, checked by the server's validator test). Nothing builds or sends a matched skeleton until the app half of #1573
ships.

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
