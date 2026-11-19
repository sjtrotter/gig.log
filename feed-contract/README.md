# feed-contract/ — the signed feed format and its one verifier (#1563)

This plain-JVM included build owns the format of every signed feed (fuel prices, yearly constants, recognition rules)
and the code that checks it: the bounded reader, the pin registry (`FeedTrust`), the verifier (`VerifiedManifest.verify`
and `VerifiedFeedFile.verify`, the only way to get either type), the acceptance
function and the freshness label. The decision record is ADR-0013. No network code and no Android dependency live
here; the transport, the cache and the consumers are the app's.

Apache-2.0, so the PolyForm-Shield app and the AGPL `dashbuddy-census` server check signatures with the same code.
The app includes it with `includeBuild("feed-contract")`, and `:domain` declares
`api("cloud.trotter.feed:feed-contract:0.0.0-local")`. Run its tests through the root `feedContractTest` task;
`:domain:test` runs them too. Like `census-contract/`, its settings read `../gradle/libs.versions.toml`, so include
it from a complete gig.log checkout pinned to a SHA.

## Files on the site

Each feed has a directory holding one envelope and its payloads, each payload named by its own sha256:

```json
{ "manifest": "<the manifest JSON, as one string>", "key": "<key id>", "sig": "<base64 DER signature>" }
```

```json
{
  "format_version": 1,
  "source": "gig.log",
  "feed": "fuel",
  "sequence": 1794350700000,
  "issued_at": 1794350700000,
  "contract": 1,
  "files": [ { "name": "prices", "sha256": "<64 lowercase hex>", "size": 6123 } ]
}
```

- **Signature:** ECDSA P-256 with SHA-256, base64 DER, over the exact UTF-8 bytes of the `manifest` string
  (`matchers/tools/sign-ruleset.sh` makes one). `key` names which of the epoch's two keys signed; the key itself
  always comes from the pins for the source and feed the client asked for.
- **Key id:** the first 16 hex characters of sha256 over the DER public key (SubjectPublicKeyInfo, the canonical
  encoding; any other encoding is refused).
- **Manifest digest** (`FEED_DIGEST` for the bundled rules): lowercase hex sha256 over the manifest bytes.
- **Units:** every time is Unix milliseconds; a value below 10^12 is refused. `sequence` equals `issued_at`.
- **Reading:** a tokenizer pre-pass runs over the raw bytes before any tree is built. It refuses duplicate keys at any
  level, nesting deeper than 4 and numbers that are not finite. Unknown top-level manifest keys are ignored (the key
  `advisory` is reserved, ADR-0013 §6); an unknown `format_version` is refused; a file entry has exactly `name`,
  `sha256` and `size`.
- **Byte caps** (`FeedLimits`, the one owner), counted on the decompressed stream: envelope 32 KB, fuel 64 KB,
  constants 16 KB, each rules file 1 MB, a rules generation 4 MB, at most 16 files. A file's size and sha256 are
  checked against the signed manifest before its bytes go anywhere.
- **Payloads:** fuel and constants use the same pre-pass at depth 4 (`FeedFiles.parsePayload`). Rules payloads keep
  the rule compiler's own bounds. File names: fuel `prices`, constants `values`; a rules name goes through the
  caller's platform lookup, and a name nothing resolves is ignored.

## Acceptance (client rules)

`FeedAcceptance.decide(state, candidate, request)` is one pure function. Every rule below is a client rule, so no
publisher can widen it:

- `source` and `feed` equal the request. A custom source's id is `custom:<key id>`, which can never be `gig.log`.
- Clock admission: refuse `issued_at` more than 24 h past `max(wall clock, build time)`, reason `ClockBehind`.
  Nothing a feed sends moves that bound.
- High-water, one per source and feed: above is a candidate; equal with the same digest is a no-op; equal with a
  different digest or below is refused. Recovery never lowers it. For rules the effective high-water is
  `max(FEED_SEQUENCE, committed)`; equal sequence plus `FEED_DIGEST` is the silent no-op, and a committed rules
  generation at or below `FEED_SEQUENCE` does not run.
- An unsupported `contract` keeps last-good and does not move the high-water.
- Freshness is a label, never a refusal: current while the age is within fuel 21 days, constants 400 days, rules
  90 days. Only an accepted generation renews it.

## The pin file

[`pins/feed-pins.json`](pins/feed-pins.json) holds the pinned public keys of every gig.log feed. It is the one copy:
the app's `FeedTrust` reads it from the classpath (`/feed-pins.json`), and every workflow that verifies a signature
reads this path. One epoch per feed, keyed by the feed's wire name:

```json
{
  "format_version": 1,
  "feeds": {
    "fuel": { "epoch": 1, "primary": "<base64 DER SubjectPublicKeyInfo>", "backup": "<base64 DER SubjectPublicKeyInfo>" }
  }
}
```

Each entry needs both keys and they must differ. New trust for a gig.log feed comes only from an app release that
changes this file; a manifest never names a successor key. The file pins gig.log's fuel key; the rules and constants
keys come with their publishers (#641, #695). A user pin (a custom source) is a primary key and an optional backup, filed under `custom:<key id of the
primary>`, never under `gig.log` and never for rules. The pin-file reader is private: production reads only the
shipped file.
