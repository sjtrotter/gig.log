"""Shared helpers for the feed tests: ephemeral keys, EIA responses in the v2 shape, and fake fetchers.

No real key and no live call: every key is made with `openssl` in a temporary directory at run time and removed with
it, and every EIA response is built here in the shape of testdata/eia-response-shape.json.
"""
from __future__ import annotations

import base64
import json
import subprocess
from pathlib import Path

import publish_fuel

# Tuesday 2026-11-17 18:00 UTC, the day after the week of Monday 2026-11-16.
NOW = 1_794_938_400_000
WEEK = publish_fuel.WEEK_MS


def keypair(directory: Path, name: str = "key", curve: str = "prime256v1") -> tuple[Path, str]:
    """An ephemeral private key file and its base64 DER public key."""
    pem = directory / f"{name}.pem"
    subprocess.run(["openssl", "ecparam", "-name", curve, "-genkey", "-noout", "-out", str(pem)], check=True,
                   capture_output=True)
    der = subprocess.run(["openssl", "pkey", "-in", str(pem), "-pubout", "-outform", "DER"], check=True,
                         capture_output=True).stdout
    return pem, base64.b64encode(der).decode("ascii")


def pin_file(directory: Path, **feeds: tuple[str, str]) -> Path:
    """A pin file with {feed: (primary b64, backup b64)}."""
    path = directory / "feed-pins.json"
    path.write_text(json.dumps({"format_version": 1, "feeds": {
        feed: {"epoch": 1, "primary": p, "backup": b} for feed, (p, b) in feeds.items()}}))
    return path


def empty_pins(directory: Path) -> Path:
    return pin_file(directory)


def monday(ms: int) -> str:
    return publish_fuel.period_text(ms)


def week_ms(text: str) -> int:
    return publish_fuel.period_ms(text)


def base_prices(scale: float = 1.0) -> dict[tuple[str, str], float]:
    """A plausible price for every pair: grades step up, diesel above premium, areas spread a little."""
    out = {}
    for n, (area, fuel) in enumerate(publish_fuel.pairs()):
        base = {"regular": 3.0, "midgrade": 3.5, "premium": 3.9, "diesel": 3.7}[fuel]
        out[(area, fuel)] = round((base + (n % 7) * 0.05) * scale, 3)
    return out


def eia_body(weeks: dict[str, dict[tuple[str, str], object]]) -> bytes:
    """An EIA v2 /data response: {period text: {(area, fuel): value}}, newest period first, as EIA sorts it."""
    rows = []
    for period in sorted(weeks, reverse=True):
        for (area, fuel), value in weeks[period].items():
            rows.append({
                "period": period, "duoarea": area, "area-name": area, "product": fuel, "product-name": fuel,
                "process": "PTE", "process-name": "Retail Sales by All Sellers",
                "series": publish_fuel.series_id(area, fuel), "series-description": f"{area} {fuel}",
                "value": value, "units": "$/GAL",
            })
    return json.dumps({"response": {"total": str(len(rows)), "dateFormat": "YYYY-MM-DD", "frequency": "weekly",
                                    "data": rows}, "apiVersion": "2.1.8"}).encode()


class Args:
    """The publisher's arguments, as argparse would give them."""

    def __init__(self, out, pins, key_file=None, live=None, anchors=None, source=None):
        self.out, self.pins, self.key_file = str(out), str(pins), str(key_file) if key_file else None
        self.live, self.anchors, self.source = live, anchors, source


class FakeSite:
    """A live site: {url path: bytes}; anything else is a 404."""

    def __init__(self, files: dict[str, bytes] | None = None):
        self.files = dict(files or {})
        self.requests: list[str] = []

    def fetch(self, url: str) -> bytes | None:
        self.requests.append(url)
        path = url.split("://", 1)[1].split("/", 1)[1].split("?", 1)[0]
        return self.files.get(path)

    @staticmethod
    def of(site: Path) -> "FakeSite":
        return FakeSite({p.relative_to(site).as_posix(): p.read_bytes() for p in site.rglob("*") if p.is_file()})


BASE_URL = "https://feeds.example.test/"

