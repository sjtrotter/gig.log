#!/usr/bin/env python3
"""Publishes gig.log's fuel price feed (#1564): EIA weekly retail prices, one signed file. Stdlib and `openssl` only.

It fetches every EIA weekly retail area and fuel, applies the per-pair bounds against the live feed, and writes the
content-addressed payload and the signed envelope. docs/feeds/FORMAT.md describes the payload and the rules;
docs/feeds/SELF_HOSTING.md is the operator's guide.

  publish_fuel.py publish --out DIR [--key-file PEM] [--live DIR] [--anchors FILE] [--source ID]
                          [--pins FILE] [--now-ms MS] [--eia-response FILE]
  publish_fuel.py ci --out DIR       (the workflow's fuel job; reads FUEL_STATE and the secrets from the environment)

Environment: EIA_API_KEY (required), FEED_FUEL_SIGNING_KEY (the private key PEM, when --key-file is not given),
FEED_FUEL_PUBLIC_KEY (a self-hosted feed's own public key, base64 DER; the repository variable).

The source is derived from the signing key: the key pinned for gig.log's fuel feed gives `gig.log`, any other key gives
`custom:<key id>`, the id the app gives that key when it is pasted.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import math
from decimal import Decimal
import os
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import feedlib  # noqa: E402
from feedlib import FeedError  # noqa: E402

EIA_LINE = "A fuel feed needs its own EIA API key, free at https://www.eia.gov/opendata/register.php"
SECRETS_LINE = (
    "The fuel job needs both secrets in environment feed-fuel: EIA_API_KEY and FEED_FUEL_SIGNING_KEY "
    "(see docs/feeds/SELF_HOSTING.md)."
)
NOT_SET_UP = "fuel feed not set up: see docs/feeds/SELF_HOSTING.md"

EIA_URL = "https://api.eia.gov/v2/petroleum/pri/gnd/data/"
CONTRACT = 1
UNIT = "USD/gal"

# EIA's 29 weekly retail areas, by EIA area code (the `duoarea` of the API and the area part of each series id).
AREAS = (
    "NUS",                                                   # U.S.
    "R10", "R1X", "R1Y", "R1Z", "R20", "R30", "R40", "R50",  # PADD 1, 1A, 1B, 1C, 2, 3, 4, 5
    "R5XCA",                                                 # West Coast less California
    "SCA", "SCO", "SFL", "SMA", "SMN", "SNY", "SOH", "STX", "SWA",
    "YBOS", "YORD", "YCLE", "YDEN", "Y44HO", "Y05LA", "YMIA", "Y35NY", "Y05SF", "Y48SE",
)
# All formulations for the gasoline grades (not EMM_EPMRU_PTE, regular conventional), on-highway diesel all types.
SERIES = {
    "regular": "EMM_EPMR_PTE",
    "midgrade": "EMM_EPMM_PTE",
    "premium": "EMM_EPMP_PTE",
    "diesel": "EMD_EPD2D_PTE",
}
# EIA publishes diesel for the U.S., the PADD-level areas and California only.
DIESEL_AREAS = ("NUS", "R10", "R1X", "R1Y", "R1Z", "R20", "R30", "R40", "R50", "R5XCA", "SCA")

# Bounds compare in decimal, from each price's shortest text form, so the edges are exact (3.500 -> 4.900 is 40 %).
MIN_PRICE, MAX_PRICE = Decimal("0.50"), Decimal("15.00")
MAX_MOVE = Decimal("0.40")
WEEK_MS = 7 * 24 * 60 * 60 * 1000
ANCHOR_MAX_AGE_MS = 8 * WEEK_MS
FETCH_WEEKS = 5


def pairs() -> list[tuple[str, str]]:
    """Every (area, fuel) pair EIA publishes: 29 areas x 3 grades + 11 diesel areas = 98."""
    out = [(area, fuel) for area in AREAS for fuel in ("regular", "midgrade", "premium")]
    out += [(area, "diesel") for area in DIESEL_AREAS]
    return out


def series_id(area: str, fuel: str) -> str:
    return f"{SERIES[fuel]}_{area}_DPG"


def period_ms(text) -> int | None:
    """An EIA week as ms (00:00 UTC of its Monday), or None when the text is not a Monday date."""
    if not isinstance(text, str) or len(text) != 10:
        return None
    try:
        day = dt.date.fromisoformat(text)
    except ValueError:
        return None
    if day.weekday() != 0 or day.year < 1990:
        return None
    return (day - dt.date(1970, 1, 1)).days * 86_400_000


def period_text(ms: int) -> str:
    return (dt.date(1970, 1, 1) + dt.timedelta(days=ms // 86_400_000)).isoformat()


def price_value(raw) -> float | None:
    if isinstance(raw, bool) or raw is None:
        return None
    try:
        value = float(raw)
    except (TypeError, ValueError):
        return None
    return value if math.isfinite(value) else None


# ---- EIA -------------------------------------------------------------------------------------------------------


def eia_query(api_key: str, now_ms: int) -> str:
    start = period_text(now_ms - FETCH_WEEKS * WEEK_MS)
    params = [("api_key", api_key), ("frequency", "weekly"), ("data[0]", "value"), ("start", start)]
    params += [("facets[series][]", series_id(a, f)) for a, f in pairs()]
    params += [("sort[0][column]", "period"), ("sort[0][direction]", "desc"), ("offset", "0"), ("length", "5000")]
    return EIA_URL + "?" + urllib.parse.urlencode(params)


def fetch_eia(api_key: str, now_ms: int) -> bytes:
    """One request for every series. The URL carries the key, so no message here ever prints it."""
    request = urllib.request.Request(eia_query(api_key, now_ms), headers={"User-Agent": "feeds-publisher"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.read(4 * 1024 * 1024)
    except urllib.error.HTTPError as e:
        raise FeedError(f"EIA refused the request (HTTP {e.code}). {EIA_LINE}") from None
    except (urllib.error.URLError, OSError) as e:
        raise FeedError(f"EIA could not be reached ({type(e).__name__})") from None


def read_eia(body: bytes) -> dict[str, list[tuple[str, object]]]:
    """Rows by series id, as (period text, raw value). Refuses a response that is not EIA's v2 data shape."""
    try:
        root = json.loads(body)
    except (json.JSONDecodeError, UnicodeDecodeError):
        raise FeedError("EIA's response is not JSON") from None
    response = root.get("response") if isinstance(root, dict) else None
    rows = response.get("data") if isinstance(response, dict) else None
    if not isinstance(rows, list):
        raise FeedError("EIA's response has no response.data list")
    total = response.get("total")
    if total is not None and str(total).isdigit() and int(total) > len(rows):
        raise FeedError(f"EIA's response is truncated ({len(rows)} of {total} rows)")
    out: dict[str, list[tuple[str, object]]] = {}
    for row in rows:
        if isinstance(row, dict) and isinstance(row.get("series"), str):
            out.setdefault(row["series"], []).append((row.get("period"), row.get("value")))
    return out


# ---- the generation -------------------------------------------------------------------------------------------


class Outcome:
    """What one run decided. `payload` is None when nothing changed (or nothing could be published)."""

    def __init__(self, payload, anchors, log, reason=""):
        self.payload = payload
        self.anchors = anchors
        self.log = log
        self.reason = reason


def _newest_valid(rows, now_ms, area, fuel, log) -> tuple[int, float] | None:
    best = None
    for text, raw in rows:
        ms = period_ms(text)
        if ms is None or ms > now_ms:
            # Checked before any per-pair state moves: a bad period never resets an anchor.
            log.append(f"refused {area}/{fuel}: period {text!r} is not an EIA week at or before issued_at")
            continue
        value = price_value(raw)
        if value is None:
            continue
        if best is None or ms > best[0]:
            best = (ms, value)
    return best


def dec(price: float) -> Decimal:
    """A price as the decimal its JSON text names (Python writes the shortest round-trip form)."""
    return Decimal(repr(float(price)))


def anchors_after(live: dict | None, anchors: dict) -> dict:
    """The anchors for a new week: the live feed's week becomes each published pair's anchor; others keep theirs."""
    out = {area: dict(fuels) for area, fuels in anchors.items()}
    if live is None:
        return out
    for area, fuels in live["prices"].items():
        for fuel, price in fuels.items():
            out.setdefault(area, {})[fuel] = {"period": live["period"], "price": price}
    return out


def generate(eia: dict, now_ms: int, live: dict | None, anchors: dict) -> Outcome:
    """The new payload, per-pair bounds applied, or no payload when it would equal the live one."""
    log: list[str] = []
    newest = {}
    for area, fuel in pairs():
        found = _newest_valid(eia.get(series_id(area, fuel), []), now_ms, area, fuel, log)
        if found is None:
            log.append(f"omitted {area}/{fuel}: not published")
        else:
            newest[(area, fuel)] = found
    if not newest:
        raise FeedError("EIA returned no usable week for any area")
    period = max(ms for ms, _ in newest.values())
    live_period = live["period"] if live else None
    if live_period is not None and period < live_period:
        return Outcome(None, anchors, log, f"EIA's newest week {period_text(period)} is older than the live "
                                           f"{period_text(live_period)}; nothing published")

    # A new week moves the live week into the anchors; a correction of the live week compares to the same anchors.
    working = anchors_after(live, anchors) if live_period is None or period > live_period else \
        {a: dict(f) for a, f in anchors.items()}
    prices: dict[str, dict[str, float]] = {}
    for (area, fuel), (ms, value) in sorted(newest.items()):
        if ms != period:
            log.append(f"omitted {area}/{fuel}: newest week {period_text(ms)} is not {period_text(period)}")
            continue
        if not MIN_PRICE <= dec(value) <= MAX_PRICE:
            log.append(f"omitted {area}/{fuel}: {value} is outside {MIN_PRICE:.2f}-{MAX_PRICE:.2f}")
            continue
        anchor = working.get(area, {}).get(fuel)
        if anchor is not None and period - anchor["period"] > ANCHOR_MAX_AGE_MS:
            del working[area][fuel]
            log.append(f"anchor dropped {area}/{fuel}: week {period_text(anchor['period'])} is over 8 weeks old")
            anchor = None
        if anchor is not None and abs(dec(value) - dec(anchor["price"])) > MAX_MOVE * dec(anchor["price"]):
            log.append(f"omitted {area}/{fuel}: {value} moved over 40% from {anchor['price']} "
                       f"(week {period_text(anchor['period'])})")
            continue
        prices.setdefault(area, {})[fuel] = value
    working = {a: f for a, f in working.items() if f}
    # Every pair may fail the bounds: the generation still signs (an empty map) and advances the high-water.
    payload = {"source": "EIA", "period": period, "unit": UNIT, "series": dict(SERIES), "prices": prices}
    if live is not None and live_period == period and live["prices"] == prices:
        return Outcome(None, anchors, log, f"no change since the live feed (week {period_text(period)})")
    return Outcome(payload, working, log)


# ---- keys and source ------------------------------------------------------------------------------------------


def derive_source(kid: str, gig_fuel: feedlib.Epoch | None, requested: str | None) -> str:
    source = feedlib.GIG_LOG if gig_fuel is not None and kid in gig_fuel else feedlib.custom_source(kid)
    if requested and requested != source:
        if requested == feedlib.GIG_LOG:
            raise FeedError(f"--source gig.log needs the pinned gig.log fuel key; this key's source is {source}")
        raise FeedError(f"--source {requested} does not match this key's source {source}")
    return source


class SigningKey:
    """The private key as a mode-600 temporary file, removed on exit. From --key-file or FEED_FUEL_SIGNING_KEY."""

    def __init__(self, key_file: str | None):
        self.key_file = key_file
        self.tmp = None

    def __enter__(self) -> Path:
        if self.key_file:
            return Path(self.key_file)
        pem = os.environ.get("FEED_FUEL_SIGNING_KEY", "")
        if not pem.strip():
            raise FeedError(f"A fuel feed needs its signing key: FEED_FUEL_SIGNING_KEY or --key-file. {SECRETS_LINE}")
        self.tmp = tempfile.TemporaryDirectory()
        path = Path(self.tmp.name) / "fuel-key.pem"
        feedlib.write_private(path, pem.strip().encode("ascii") + b"\n")
        return path

    def __exit__(self, *exc):
        if self.tmp is not None:
            self.tmp.cleanup()


# ---- the live feed and the files ------------------------------------------------------------------------------


def read_live(live_dir: Path | None, epoch: feedlib.Epoch) -> tuple[dict | None, int]:
    """The live fuel payload and its sequence, re-verified, or (None, 0) when there is no live feed."""
    if live_dir is None or not (live_dir / "envelope.json").is_file():
        return None, 0
    verified = feedlib.verify_envelope((live_dir / "envelope.json").read_bytes(), "fuel", epoch)
    entry = next((f for f in verified.files if f[0] == "prices"), None)
    if entry is None:
        raise FeedError("the live fuel manifest names no prices file")
    data = (live_dir / f"{entry[1]}.json").read_bytes()
    feedlib.verify_payload(verified, entry[1], data)
    payload = feedlib.bounded_loads(data, "the live fuel payload")
    if not isinstance(payload, dict) or type(payload.get("period")) is not int or not isinstance(
            payload.get("prices"), dict):
        raise FeedError("the live fuel payload is malformed")
    return payload, verified.sequence


def read_anchors(path: Path | None) -> dict:
    if path is None or not path.is_file():
        return {}
    root = feedlib.strict_loads(path.read_bytes())
    if not isinstance(root, dict) or root.get("format") != 1 or not isinstance(root.get("anchors"), dict):
        raise FeedError(f"malformed anchors file {path}")
    return root["anchors"]


def anchors_bytes(anchors: dict) -> bytes:
    return feedlib.canonical_json({"format": 1, "anchors": anchors})


def write_generation(out: Path, payload: dict, anchors: dict, key: Path, source: str, issued_at: int) -> str:
    """Writes fuel/<sha256>.json, fuel/envelope.json and anchors.json under [out]; returns the manifest."""
    data = feedlib.canonical_json(payload)
    if len(data) > feedlib.MAX_FILE_BYTES["fuel"]:
        raise FeedError(f"the fuel payload is over {feedlib.MAX_FILE_BYTES['fuel']} bytes")
    manifest = feedlib.build_manifest(source, "fuel", issued_at, CONTRACT, [("prices", data)])
    der = feedlib.public_der_of_private_key(key)
    sig = feedlib.sign(key, manifest.encode("ascii"))
    if not feedlib.verifies(der, manifest.encode("ascii"), sig):
        raise FeedError("the new signature does not verify")
    feed_dir = out / "fuel"
    feed_dir.mkdir(parents=True, exist_ok=True)
    (feed_dir / f"{feedlib.sha256_hex(data)}.json").write_bytes(data)
    (feed_dir / "envelope.json").write_bytes(feedlib.build_envelope(manifest, feedlib.key_id(der), sig))
    (out / "anchors.json").write_bytes(anchors_bytes(anchors))
    return manifest


def publish(args, now_ms: int, eia_body: bytes | None = None) -> Outcome:
    """One run: refusals first, then EIA, the bounds and, when anything changed, the signed files."""
    api_key = os.environ.get("EIA_API_KEY", "")
    if not api_key.strip():
        raise FeedError(EIA_LINE)
    pins = feedlib.read_pin_file(Path(args.pins))
    with SigningKey(args.key_file) as key:
        der = feedlib.public_der_of_private_key(key)
        source = derive_source(feedlib.key_id(der), pins.get("fuel"), args.source)
        epoch = feedlib.Epoch(source, [der]) if source != feedlib.GIG_LOG else pins["fuel"]
        live, live_sequence = read_live(Path(args.live) if args.live else None, epoch)
        anchors = read_anchors(Path(args.anchors) if args.anchors else None)
        if eia_body is None:
            eia_body = fetch_eia(api_key.strip(), now_ms)
        outcome = generate(read_eia(eia_body), now_ms, live, anchors)
        if outcome.payload is not None:
            if now_ms <= live_sequence:
                raise FeedError(f"issued_at {now_ms} is not after the live sequence {live_sequence}")
            write_generation(Path(args.out), outcome.payload, outcome.anchors, key, source, now_ms)
    return outcome


# ---- the workflow's fuel job ----------------------------------------------------------------------------------


def setup_state(pins_path: Path) -> str:
    """'ready', or 'not-set-up' when there is neither a key nor a pin. Every half-setup is refused loudly."""
    has_key = bool(os.environ.get("FEED_FUEL_SIGNING_KEY", "").strip())
    custom = os.environ.get("FEED_FUEL_PUBLIC_KEY", "").strip()
    gig_fuel = feedlib.read_pin_file(pins_path).get("fuel")
    has_pin = bool(custom) or gig_fuel is not None
    if not has_key and not has_pin:
        return "not-set-up"
    if not has_key:
        raise FeedError(f"a fuel key is pinned but FEED_FUEL_SIGNING_KEY is missing. {SECRETS_LINE} {EIA_LINE}")
    if not has_pin:
        raise FeedError(
            "FEED_FUEL_SIGNING_KEY is set but no fuel key is pinned: set the repository variable FEED_FUEL_PUBLIC_KEY "
            f"to its public key (a self-hosted feed) or pin it in {feedlib.PIN_FILE} (gig.log). {SECRETS_LINE} "
            f"{EIA_LINE}")
    with SigningKey(None) as key:
        kid = feedlib.key_id(feedlib.public_der_of_private_key(key))
    custom_kid = feedlib.key_id(feedlib.public_der_from_b64(custom)) if custom else None
    if not ((gig_fuel is not None and kid in gig_fuel) or kid == custom_kid):
        raise FeedError(f"FEED_FUEL_SIGNING_KEY (key id {kid}) is not the pinned fuel key. {SECRETS_LINE} {EIA_LINE}")
    if not os.environ.get("EIA_API_KEY", "").strip():
        raise FeedError(f"EIA_API_KEY is missing. {SECRETS_LINE} {EIA_LINE}")
    return "ready"


def run_ci(args, now_ms: int) -> int:
    pins = Path(args.pins)
    if setup_state(pins) == "not-set-up":
        print(f"::notice::{NOT_SET_UP}")
        feedlib.summary(NOT_SET_UP)
        feedlib.github_output(published="false")
        return 0
    with tempfile.TemporaryDirectory() as tmp:
        state = Path(tmp) / "state"
        feedlib.unpack_dir(os.environ.get("FUEL_STATE", ""), state)
        args.live = str(state / "feeds" / "fuel")
        args.anchors = str(state / "state" / "fuel" / "anchors.json")
        args.key_file = None
        out = Path(args.out)
        outcome = publish(args, now_ms)
        for line in outcome.log:
            print(line)
        if outcome.payload is None:
            print(f"::notice::fuel: {outcome.reason}")
            feedlib.summary(f"fuel: {outcome.reason}")
            feedlib.github_output(published="false")
            return 0
        omitted = sum(1 for line in outcome.log if line.startswith("omitted"))
        message = (f"fuel: signed week {period_text(outcome.payload['period'])}, sequence {now_ms}, "
                   f"{sum(len(f) for f in outcome.payload['prices'].values())} prices, {omitted} omitted")
        print(f"::notice::{message}")
        feedlib.summary(message)
        feedlib.github_output(published="true", bundle=feedlib.pack_dir(out))
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("publish", "ci"):
        p = sub.add_parser(name)
        p.add_argument("--out", required=True)
        p.add_argument("--pins", default=feedlib.PIN_FILE)
        p.add_argument("--source")
        p.add_argument("--now-ms", type=int)
    pub = sub.choices["publish"]
    pub.add_argument("--key-file")
    pub.add_argument("--live", help="the live feed's fuel directory (envelope.json and its payloads)")
    pub.add_argument("--anchors", help="the anchors file the last generation wrote")
    pub.add_argument("--eia-response", help="read EIA's response from this file instead of the API (tests)")
    args = parser.parse_args(argv)
    now_ms = args.now_ms if args.now_ms is not None else int(time.time() * 1000)
    try:
        if args.command == "ci":
            return run_ci(args, now_ms)
        body = Path(args.eia_response).read_bytes() if args.eia_response else None
        outcome = publish(args, now_ms, body)
        for line in outcome.log:
            print(line)
        print(f"publish_fuel: {'wrote ' + args.out if outcome.payload else outcome.reason}")
        return 0
    except FeedError as e:
        print(f"::error::{e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
