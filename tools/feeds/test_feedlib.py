"""Tests of the Python side of the feed format against feed-contract's rules (#1564)."""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import feedlib
import feedtest
from feedlib import FeedError


class PinFileTest(unittest.TestCase):
    """The same refusals as feed-contract FeedTrust.readPinFile."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        _, self.a = feedtest.keypair(self.dir, "a")
        _, self.b = feedtest.keypair(self.dir, "b")

    def tearDown(self):
        self.tmp.cleanup()

    def read(self, root) -> dict:
        path = self.dir / "pins.json"
        path.write_text(json.dumps(root))
        return feedlib.read_pin_file(path)

    def entry(self, **changes):
        entry = {"epoch": 1, "primary": self.a, "backup": self.b}
        entry.update(changes)
        return {k: v for k, v in entry.items() if v is not None}

    def test_a_well_formed_pin_file_reads(self):
        epochs = self.read({"format_version": 1, "feeds": {"fuel": self.entry()}})
        self.assertEqual(2, len(epochs["fuel"].keys))

    def test_malformed_pins_are_refused(self):
        cases = {
            "missing epoch": self.entry(epoch=None),
            "epoch 0": self.entry(epoch=0),
            "epoch as text": self.entry(epoch="1"),
            "epoch true": self.entry(epoch=True),
            "same key twice": self.entry(backup=self.a),
            "no backup": self.entry(backup=None),
            "not a key": self.entry(primary="AAAA"),
        }
        for name, entry in cases.items():
            with self.subTest(name), self.assertRaises(FeedError):
                self.read({"format_version": 1, "feeds": {"fuel": entry}})
        for name, root in {
            "unknown feed": {"format_version": 1, "feeds": {"diesel": self.entry()}},
            "format_version true": {"format_version": True, "feeds": {}},
            "format_version 2": {"format_version": 2, "feeds": {}},
            "too deep": {"format_version": 1, "feeds": {"fuel": {**self.entry(), "x": [[1]]}}},
        }.items():
            with self.subTest(name), self.assertRaises(FeedError):
                self.read(root)
        path = self.dir / "dup.json"
        path.write_text('{"format_version":1,"feeds":{},"feeds":{}}')
        with self.assertRaises(FeedError):
            feedlib.read_pin_file(path)


class EnvelopeLimitsTest(unittest.TestCase):
    """feed-contract FeedLimits: decimal byte caps, 16 files, depth 4, a bounded signature."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.key, pub = feedtest.keypair(self.dir)
        der = feedlib.public_der_from_b64(pub)
        self.epoch = feedlib.Epoch(feedlib.custom_source(feedlib.key_id(der)), [der])
        self.kid = feedlib.key_id(der)

    def tearDown(self):
        self.tmp.cleanup()

    def envelope(self, manifest: str, sig: str | None = None) -> bytes:
        return feedlib.build_envelope(manifest, self.kid, sig or feedlib.sign(self.key, manifest.encode()))

    def manifest(self, files) -> str:
        return feedlib.build_manifest(self.epoch.source, "fuel", feedtest.NOW, 1, files)

    def test_the_caps_are_feed_contracts(self):
        self.assertEqual(64_000, feedlib.MAX_FILE_BYTES["fuel"])
        self.assertEqual(32_000, feedlib.MAX_ENVELOPE_BYTES)
        feedlib.verify_envelope(self.envelope(self.manifest([("prices", b"x" * 64_000)])), "fuel", self.epoch)
        with self.assertRaises(FeedError):
            feedlib.verify_envelope(self.envelope(self.manifest([("prices", b"x" * 64_001)])), "fuel", self.epoch)

    def test_structure_is_bounded(self):
        with self.assertRaises(FeedError):  # 17 files
            feedlib.verify_envelope(self.envelope(self.manifest([(f"f{n}", b"x") for n in range(17)])), "fuel",
                                    self.epoch)
        with self.assertRaises(FeedError):  # the same name twice
            feedlib.verify_envelope(self.envelope(self.manifest([("prices", b"a"), ("prices", b"b")])), "fuel",
                                    self.epoch)
        with self.assertRaises(FeedError):  # over the generation cap in all
            feedlib.verify_envelope(self.envelope(self.manifest([("a", b"x" * 40_000), ("b", b"x" * 40_000)])),
                                    "fuel", self.epoch)
        with self.assertRaises(FeedError):  # an oversized signature
            feedlib.verify_envelope(self.envelope(self.manifest([("prices", b"x")]), "A" * 516), "fuel", self.epoch)
        deep = self.manifest([("prices", b"x")])[:-1] + ',"extra":[[[[1]]]]}'
        with self.assertRaises(FeedError):  # deeper than 4
            feedlib.verify_envelope(self.envelope(deep), "fuel", self.epoch)


if __name__ == "__main__":
    unittest.main()
