# The signed feed format

gig.log publishes signed feeds on its GitHub Pages site, `https://gig.log.trotter.cloud/feeds/`. This page is for
anyone who reads a feed or runs one. The code that owns the format is `feed-contract/` (its README states the reader's
limits exactly, and its verifier is the one the app runs); the Python publisher in `tools/feeds/` makes the same
bytes, and a cross-language vector in `feed-contract/src/test/resources/vectors/fuel/` pins that. The decision record
is ADR-0013.

## Files on the site

```
/feeds/README.md
/feeds/<feed>/envelope.json      the signed manifest
/feeds/<feed>/<sha256>.json      each payload, named by its own lowercase hex sha256
```

The feeds are `fuel`, `constants` and `rules`. Each feed directory keeps the current payloads and the previous
generation's, so a client that read the old envelope a moment before a deploy still finds its files.

## Envelope and manifest

```json
{ "manifest": "<the manifest JSON, as one string>", "key": "<key id>", "sig": "<base64 DER signature>" }
```

```json
{"format_version":1,"source":"gig.log","feed":"fuel","sequence":1794938400000,"issued_at":1794938400000,"contract":1,"files":[{"name":"prices","sha256":"<64 hex>","size":2961}]}
```

- The signature is ECDSA P-256 with SHA-256, base64 DER, over the exact UTF-8 bytes of the `manifest` string.
- `key` is a key id: the first 16 hex characters of sha256 over the DER public key (SubjectPublicKeyInfo). It names
  which pinned key signed; the key itself always comes from the client's pins.
- Every time is Unix milliseconds. `sequence` equals `issued_at`.
- `source` is `gig.log` for gig.log's own feeds. A self-hosted feed's source is `custom:<key id>`, the id the app
  gives the public key the dasher pastes; it can never be `gig.log`.
- `contract` is the payload version of the feed. Fuel is contract 1.
- A payload is fetched from the same directory as its envelope, by the sha256 the manifest names, and checked against
  that sha256 and `size` before it is read.

## Client rules

These are rules of the app, so no publisher can widen them. `feed-contract/README.md` has the exact limits.

- `source` and `feed` must equal what the client asked for.
- A manifest with `issued_at` more than 24 hours past the later of the phone's clock and the app's build time is
  refused. Nothing a feed sends moves that bound.
- One high-water sequence per source and feed: a higher sequence is a candidate, an equal one with the same manifest is
  a no-op, an equal one with a different manifest or a lower one is refused.
- A contract the app does not support keeps the last good generation.
- Freshness is a label, never a refusal: a fuel generation is current for 21 days after its `issued_at`.
- The reader refuses duplicate keys, nesting deeper than 4, and numbers that are not finite. Byte caps: envelope
  32 KB, fuel payload 64 KB.

**Clock behind (a residual).** A phone whose clock is more than a day behind real time and behind the app's build time
refuses every new manifest, with the reason `ClockBehind`, until its clock is right. The app shows "Phone clock is
behind" (#1566). Freshness also trusts the wall clock, so a phone set years ahead shows every feed as old.

## Fuel payload (contract 1)

File name `prices`. One week of EIA weekly retail prices in U.S. dollars per gallon:

```json
{"period":1794787200000,"prices":{"NUS":{"diesel":3.712,"midgrade":3.512,"premium":3.905,"regular":3.051}},"series":{"diesel":"EMD_EPD2D_PTE","midgrade":"EMM_EPMM_PTE","premium":"EMM_EPMP_PTE","regular":"EMM_EPMR_PTE"},"source":"EIA","unit":"USD/gal"}
```

- `period` is the start of EIA's observation week: 00:00 UTC of the Monday EIA dates the week by. It is a valid EIA
  week no later than the manifest's `issued_at`.
- `prices` maps an EIA area code to the fuels published for it. A pair that EIA did not publish for `period`, or that
  failed the bounds below, is absent; the file still signs. Diesel exists only for the U.S., the PADD-level areas and
  California.
- `series` names the EIA series of each fuel. The full series id is `<series>_<area>_DPG`.
- The publisher writes keys sorted and compact, so one payload has one byte form.

### EIA series

Checked against EIA's public series pages on 2026-11-18
(`https://www.eia.gov/dnav/pet/pet_pri_gnd_a_<product>_pte_dpgal_w.htm`).

| Fuel | Series | EIA product |
| --- | --- | --- |
| `regular` | `EMM_EPMR_PTE` | Regular gasoline, all formulations |
| `midgrade` | `EMM_EPMM_PTE` | Midgrade gasoline, all formulations |
| `premium` | `EMM_EPMP_PTE` | Premium gasoline, all formulations |
| `diesel` | `EMD_EPD2D_PTE` | On-highway diesel, all types |

The feed does not publish regular conventional (`EMM_EPMRU_PTE`), the series the app reads from EIA directly today.

### Areas

| Code | Area | Code | Area |
| --- | --- | --- | --- |
| `NUS` | U.S. | `SNY` | New York |
| `R10` | East Coast (PADD 1) | `SOH` | Ohio |
| `R1X` | New England (PADD 1A) | `STX` | Texas |
| `R1Y` | Central Atlantic (PADD 1B) | `SWA` | Washington |
| `R1Z` | Lower Atlantic (PADD 1C) | `YBOS` | Boston |
| `R20` | Midwest (PADD 2) | `YORD` | Chicago |
| `R30` | Gulf Coast (PADD 3) | `YCLE` | Cleveland |
| `R40` | Rocky Mountain (PADD 4) | `YDEN` | Denver |
| `R50` | West Coast (PADD 5) | `Y44HO` | Houston |
| `R5XCA` | West Coast less California | `Y05LA` | Los Angeles |
| `SCA` | California | `YMIA` | Miami |
| `SCO` | Colorado | `Y35NY` | New York City |
| `SFL` | Florida | `Y05SF` | San Francisco |
| `SMA` | Massachusetts | `Y48SE` | Seattle |
| `SMN` | Minnesota | | |

Diesel areas: `NUS`, `R10`, `R1X`, `R1Y`, `R1Z`, `R20`, `R30`, `R40`, `R50`, `R5XCA`, `SCA`.

### Bounds per pair

The publisher applies these to each (area, fuel) pair. The app's fuel client (#1566) applies the same rules.

- The price is between 0.50 and 15.00.
- The price moves at most 40 % against the pair's anchor: its price in the previous distinct EIA week.
- The anchor is set once per week. A correction of the current week (same `period`, new sequence) is compared to the
  same anchor, so repeated corrections cannot walk a price.
- The anchor keeps its own `period` and survives weeks in which the pair was omitted. It is dropped only when it is
  more than 8 weeks older than the new `period`; a real move over 40 % that lasts is therefore published after about
  9 weeks.
- A row whose period is not a Monday, or is later than `issued_at`, is refused before any anchor changes.

The publisher keeps its anchors in the Pages snapshot (`state/fuel/anchors.json`), not on the site.

## When the fuel feed publishes

The Pages workflow (`.github/workflows/pages.yml`) runs hourly on Tuesday and Wednesday 14:30-23:30 UTC and once a
day. EIA releases on Tuesday about 10 a.m. Eastern, on Wednesday after a Monday holiday. Each run compares EIA's
newest values with the live feed: a new week or a correction signs a new generation; no change signs nothing and
deploys nothing, so the sequence and freshness stay as they were.

There is one deploy workflow, `pages.yml`, not a separate fuel workflow: a Pages deploy replaces the whole site, so
the job that signs fuel and the job that publishes the privacy pages have to build the same site. The rules and
constants tag deploys (#641, #695) add their jobs to the same workflow.
