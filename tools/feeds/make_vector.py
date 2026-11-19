#!/usr/bin/env python3
"""Regenerates the cross-language vector (#1564): a fuel generation the publisher signs, which the Kotlin verifier in
feed-contract must accept, and a copy of its envelope with one byte changed, which it must refuse
(PublisherVectorTest).

The key is ephemeral: made with openssl in a temporary directory and removed with it, so only the PUBLIC key is
written. ECDSA signatures are randomized, so each run writes different signature bytes; the manifest and payload
bytes are the same every time.

Usage: python3 -I tools/feeds/make_vector.py [<output dir>]
"""
from __future__ import annotations

import base64
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import feedlib  # noqa: E402
import publish_fuel  # noqa: E402

DEFAULT_OUT = HERE.parent.parent / "feed-contract" / "src" / "test" / "resources" / "vectors" / "fuel"
# Tuesday 2026-11-17 18:00 UTC.
ISSUED_AT = 1_794_938_400_000


class Args:
    def __init__(self, out, pins, key_file):
        self.out, self.pins, self.key_file = str(out), str(pins), str(key_file)
        self.live = self.anchors = self.source = None


def main() -> int:
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_OUT
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        key = tmp / "ephemeral.pem"
        subprocess.run(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(key)],
                       check=True, capture_output=True)
        pins = tmp / "feed-pins.json"
        pins.write_text('{"format_version":1,"feeds":{}}')
        os.environ["EIA_API_KEY"] = "fixture-not-a-key"
        body = (HERE / "testdata" / "eia-response-shape.json").read_bytes()
        publish_fuel.publish(Args(tmp / "gen", pins, key), ISSUED_AT, body)
        public = base64.b64encode(feedlib.public_der_of_private_key(key)).decode("ascii")

        if out.exists():
            shutil.rmtree(out)
        out.mkdir(parents=True)
        for path in (tmp / "gen" / "fuel").iterdir():
            shutil.copy(path, out / path.name)
        envelope = (out / "envelope.json").read_bytes()
        marker = f'\\"sequence\\":{ISSUED_AT}'.encode()
        at = envelope.index(marker) + len(marker) - 1
        changed = envelope[:at] + bytes([envelope[at] ^ 0x01]) + envelope[at + 1:]
        (out / "envelope-one-byte-changed.json").write_bytes(changed)
        (out / "public-key.txt").write_text(public + "\n")
    print(f"make_vector: wrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
