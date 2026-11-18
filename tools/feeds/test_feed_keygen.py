"""Tests of tools/feeds/feed-keygen.sh (#1564). The key it makes lives in a temporary directory and dies with it."""
from __future__ import annotations

import base64
import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

import feedlib

SCRIPT = Path(__file__).resolve().parent / "feed-keygen.sh"


class FeedKeygenTest(unittest.TestCase):
    def run_keygen(self, path: Path):
        return subprocess.run(["bash", str(SCRIPT), str(path)], capture_output=True, text=True)

    def test_it_writes_a_private_p256_key_and_prints_the_public_key_id_and_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            key = Path(tmp) / "fuel.pem"
            done = self.run_keygen(key)
            self.assertEqual(0, done.returncode, done.stderr)
            self.assertEqual(0o600, stat.S_IMODE(os.stat(key).st_mode))
            lines = dict(line.split(": ", 1) for line in done.stdout.splitlines())
            der = feedlib.public_der_of_private_key(key)
            public = next(v for k, v in lines.items() if k.startswith("public key"))
            self.assertEqual(base64.b64encode(der).decode(), public)
            self.assertEqual(der, feedlib.public_der_from_b64(public))
            self.assertEqual(feedlib.key_id(der), lines["key id"])
            self.assertEqual(f"custom:{feedlib.key_id(der)}", lines["source id"])
            self.assertNotIn("PRIVATE", done.stdout)

            again = self.run_keygen(key)
            self.assertNotEqual(0, again.returncode)
            self.assertIn("already exists", again.stderr)

    def test_it_refuses_a_path_inside_a_git_repository(self):
        inside = Path(__file__).resolve().parent / "never-written.pem"
        done = self.run_keygen(inside)
        self.assertNotEqual(0, done.returncode)
        self.assertIn("inside the git repository", done.stderr)
        self.assertFalse(inside.exists())


if __name__ == "__main__":
    unittest.main()
