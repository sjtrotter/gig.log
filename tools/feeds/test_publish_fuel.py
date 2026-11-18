"""Fixture tests of the fuel publisher (#1564). Run: python3 -I -m unittest discover -s tools/feeds -p 'test_*.py'

Every key is ephemeral (made with openssl in a temporary directory); every EIA response is a fixture.
"""
from __future__ import annotations

import base64
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import feedlib
import feedtest
import publish_fuel
from feedlib import FeedError
from feedtest import NOW, WEEK, eia_body, monday

HERE = Path(__file__).resolve().parent
WEEK_0 = "2026-11-16"
W0 = feedtest.week_ms(WEEK_0)


class Chain:
    """Successive runs, each reading the files the previous one left, as the snapshot carries them."""

    def __init__(self, test, key, pins):
        self.test, self.key, self.pins = test, key, pins
        self.live, self.anchors, self.runs = None, None, 0
        self.root = Path(test.tmp.name) / "runs"

    def run(self, weeks, now):
        self.runs += 1
        out = self.root / str(self.runs)
        args = feedtest.Args(out, self.pins, key_file=self.key, live=self.live, anchors=self.anchors)
        with mock.patch.dict(os.environ, {"EIA_API_KEY": "fixture-not-a-key"}):
            outcome = publish_fuel.publish(args, now, eia_body(weeks))
        if outcome.payload is not None:
            self.live, self.anchors = str(out / "fuel"), str(out / "anchors.json")
        return outcome

    def live_payload(self):
        envelope = feedlib.strict_loads((Path(self.live) / "envelope.json").read_bytes())
        manifest = feedlib.strict_loads(envelope["manifest"])
        return feedlib.strict_loads((Path(self.live) / f"{manifest['files'][0]['sha256']}.json").read_bytes())


class PublisherTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.key, self.pub = feedtest.keypair(self.dir)
        self.pins = feedtest.empty_pins(self.dir)
        self.kid = feedlib.key_id(base64.b64decode(self.pub))

    def tearDown(self):
        self.tmp.cleanup()

    def chain(self):
        return Chain(self, self.key, self.pins)

    # ---- refusals -------------------------------------------------------------------------------------------

    def test_refuses_without_an_eia_key(self):
        args = feedtest.Args(self.dir / "out", self.pins, key_file=self.key)
        with mock.patch.dict(os.environ, {"EIA_API_KEY": ""}):
            with self.assertRaises(FeedError) as e:
                publish_fuel.publish(args, NOW, eia_body({WEEK_0: feedtest.base_prices()}))
        self.assertEqual(
            str(e.exception),
            "A fuel feed needs its own EIA API key, free at https://www.eia.gov/opendata/register.php")
        self.assertFalse((self.dir / "out").exists())

    def test_refuses_without_a_signing_key(self):
        args = feedtest.Args(self.dir / "out", self.pins)
        env = {"EIA_API_KEY": "fixture-not-a-key", "FEED_FUEL_SIGNING_KEY": ""}
        with mock.patch.dict(os.environ, env):
            with self.assertRaises(FeedError) as e:
                publish_fuel.publish(args, NOW, eia_body({WEEK_0: feedtest.base_prices()}))
        self.assertIn("FEED_FUEL_SIGNING_KEY", str(e.exception))
        self.assertIn("EIA_API_KEY", str(e.exception))

    def test_refuses_source_gig_log_with_another_key(self):
        args = feedtest.Args(self.dir / "out", self.pins, key_file=self.key, source="gig.log")
        with mock.patch.dict(os.environ, {"EIA_API_KEY": "fixture-not-a-key"}):
            with self.assertRaises(FeedError) as e:
                publish_fuel.publish(args, NOW, eia_body({WEEK_0: feedtest.base_prices()}))
        self.assertIn(f"custom:{self.kid}", str(e.exception))

    def test_refuses_a_key_that_is_not_p256(self):
        p384, _ = feedtest.keypair(self.dir, "p384", curve="secp384r1")
        args = feedtest.Args(self.dir / "out", self.pins, key_file=p384)
        with mock.patch.dict(os.environ, {"EIA_API_KEY": "fixture-not-a-key"}):
            with self.assertRaises(FeedError):
                publish_fuel.publish(args, NOW, eia_body({WEEK_0: feedtest.base_prices()}))

    def test_refuses_a_truncated_or_foreign_response(self):
        body = json.loads(eia_body({WEEK_0: feedtest.base_prices()}))
        body["response"]["total"] = "5001"
        with self.assertRaises(FeedError):
            publish_fuel.read_eia(json.dumps(body).encode())
        with self.assertRaises(FeedError):
            publish_fuel.read_eia(b'{"error": "invalid api_key", "code": 403}')

    # ---- source ---------------------------------------------------------------------------------------------

    def test_the_pinned_key_publishes_as_gig_log_and_any_other_as_custom(self):
        _, backup = feedtest.keypair(self.dir, "backup")
        pins = feedtest.pin_file(self.dir, fuel=(self.pub, backup))
        args = feedtest.Args(self.dir / "gig", pins, key_file=self.key, source="gig.log")
        with mock.patch.dict(os.environ, {"EIA_API_KEY": "fixture-not-a-key"}):
            publish_fuel.publish(args, NOW, eia_body({WEEK_0: feedtest.base_prices()}))
        manifest = json.loads(json.loads((self.dir / "gig" / "fuel" / "envelope.json").read_text())["manifest"])
        self.assertEqual("gig.log", manifest["source"])

        other, _ = feedtest.keypair(self.dir, "other")
        self.assertTrue(publish_fuel.derive_source(
            feedlib.key_id(feedlib.public_der_of_private_key(other)),
            feedlib.read_pin_file(pins)["fuel"], None).startswith("custom:"))

    # ---- byte-exact output ----------------------------------------------------------------------------------

    def test_manifest_and_payload_are_byte_exact_and_the_signature_verifies_with_openssl(self):
        out = self.dir / "out"
        args = feedtest.Args(out, self.pins, key_file=self.key)
        body = (HERE / "testdata" / "eia-response-shape.json").read_bytes()
        with mock.patch.dict(os.environ, {"EIA_API_KEY": "fixture-not-a-key"}):
            outcome = publish_fuel.publish(args, NOW, body)
        self.assertIn("omitted Y05SF/premium: not published", outcome.log)

        payload = (
            b'{"period":1794787200000,"prices":{"NUS":{"regular":3.051},"SCA":{"diesel":5.402}},'
            b'"series":{"diesel":"EMD_EPD2D_PTE","midgrade":"EMM_EPMM_PTE","premium":"EMM_EPMP_PTE",'
            b'"regular":"EMM_EPMR_PTE"},"source":"EIA","unit":"USD/gal"}'
        )
        digest = feedlib.sha256_hex(payload)
        self.assertEqual(payload, (out / "fuel" / f"{digest}.json").read_bytes())
        manifest = (
            f'{{"format_version":1,"source":"custom:{self.kid}","feed":"fuel","sequence":{NOW},"issued_at":{NOW},'
            f'"contract":1,"files":[{{"name":"prices","sha256":"{digest}","size":{len(payload)}}}]}}'
        )
        envelope = json.loads((out / "fuel" / "envelope.json").read_bytes())
        self.assertEqual(["manifest", "key", "sig"], list(envelope))
        self.assertEqual(manifest, envelope["manifest"])
        self.assertEqual(self.kid, envelope["key"])
        self.assertEqual({"format": 1, "anchors": {}}, json.loads((out / "anchors.json").read_bytes()))

        # ECDSA signatures are randomized: the bytes are pinned above, the signature by openssl itself.
        pub_pem, sig = self.dir / "pub.pem", self.dir / "sig.der"
        subprocess.run(["openssl", "pkey", "-in", str(self.key), "-pubout", "-out", str(pub_pem)], check=True)
        sig.write_bytes(base64.b64decode(envelope["sig"]))
        done = subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(pub_pem), "-signature", str(sig)],
                              input=manifest.encode(), capture_output=True)
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertNotEqual(0, subprocess.run(
            ["openssl", "dgst", "-sha256", "-verify", str(pub_pem), "-signature", str(sig)],
            input=manifest.replace("fuel", "rules", 1).encode(), capture_output=True).returncode)

    def test_the_payload_reads_under_the_contract_bounds(self):
        out = self.dir / "out"
        with mock.patch.dict(os.environ, {"EIA_API_KEY": "fixture-not-a-key"}):
            publish_fuel.publish(feedtest.Args(out, self.pins, key_file=self.key), NOW,
                                 eia_body({WEEK_0: feedtest.base_prices()}))
        payload_file = next(p for p in (out / "fuel").iterdir() if p.name != "envelope.json")
        self.assertLess(payload_file.stat().st_size, feedlib.MAX_FILE_BYTES["fuel"])
        payload = feedlib.strict_loads(payload_file.read_bytes())
        self.assertEqual(29, len(payload["prices"]))
        self.assertEqual(98, sum(len(f) for f in payload["prices"].values()))
        self.assertNotIn("diesel", payload["prices"]["YBOS"])
        self.assertIn("diesel", payload["prices"]["SCA"])

    # ---- omitted and missing --------------------------------------------------------------------------------

    def test_an_out_of_bounds_or_unpublished_pair_is_omitted_and_the_file_still_signs(self):
        prices = feedtest.base_prices()
        prices[("STX", "regular")] = 16.20
        prices[("YMIA", "midgrade")] = 0.31
        del prices[("SOH", "premium")]
        prices[("SMN", "regular")] = None
        outcome = self.chain().run({WEEK_0: prices}, NOW)
        got = outcome.payload["prices"]
        for area, fuel in [("STX", "regular"), ("YMIA", "midgrade"), ("SOH", "premium"), ("SMN", "regular")]:
            self.assertNotIn(fuel, got[area])
            self.assertTrue(any(line.startswith(f"omitted {area}/{fuel}") for line in outcome.log), (area, fuel))
        self.assertEqual(94, sum(len(f) for f in got.values()))

    def test_a_missing_fuel_is_absent_and_the_rest_signs(self):
        prices = {k: v for k, v in feedtest.base_prices().items() if k[1] != "diesel"}
        chain = self.chain()
        outcome = chain.run({WEEK_0: prices}, NOW)
        self.assertTrue(all("diesel" not in fuels for fuels in outcome.payload["prices"].values()))
        self.assertEqual(11, sum(1 for line in outcome.log if line.endswith("/diesel: not published")))
        self.assertIsNotNone(chain.live)

    def test_a_pair_still_on_last_week_is_omitted(self):
        last = feedtest.base_prices()
        this = dict(last)
        del this[("SFL", "regular")]
        outcome = self.chain().run({monday(W0 - WEEK): last, WEEK_0: this}, NOW)
        self.assertEqual(W0, outcome.payload["period"])
        self.assertNotIn("regular", outcome.payload["prices"]["SFL"])

    # ---- no change, revisions, corrections ------------------------------------------------------------------

    def test_no_change_signs_nothing(self):
        chain = self.chain()
        chain.run({WEEK_0: feedtest.base_prices()}, NOW)
        first = chain.live
        outcome = chain.run({WEEK_0: feedtest.base_prices()}, NOW + 3_600_000)
        self.assertIsNone(outcome.payload)
        self.assertIn("no change", outcome.reason)
        self.assertEqual(first, chain.live)
        self.assertFalse((chain.root / "2").exists())

    def test_a_revised_week_publishes_a_new_sequence_against_the_same_anchor(self):
        chain = self.chain()
        chain.run({monday(W0 - WEEK): feedtest.base_prices()}, NOW - WEEK)
        chain.run({WEEK_0: feedtest.base_prices(1.01)}, NOW)
        revised = feedtest.base_prices(1.01)
        revised[("NUS", "regular")] = 3.111
        # EIA also revises the week before; the anchor is set once per week and does not follow it.
        older = feedtest.base_prices(1.2)
        outcome = chain.run({monday(W0 - WEEK): older, WEEK_0: revised}, NOW + 3_600_000)
        self.assertEqual(W0, outcome.payload["period"])
        self.assertEqual(3.111, outcome.payload["prices"]["NUS"]["regular"])
        anchors = json.loads(Path(chain.anchors).read_bytes())["anchors"]
        self.assertEqual({"period": W0 - WEEK, "price": feedtest.base_prices()[("NUS", "regular")]},
                         anchors["NUS"]["regular"])
        envelope = json.loads((Path(chain.live) / "envelope.json").read_bytes())
        self.assertEqual(NOW + 3_600_000, json.loads(envelope["manifest"])["sequence"])

    def test_successive_same_week_corrections_across_runs_do_not_walk_the_anchor(self):
        chain = self.chain()
        start = feedtest.base_prices()
        anchor = start[("NUS", "regular")]  # 3.0
        chain.run({monday(W0 - WEEK): start}, NOW - WEEK)

        def week_with(price):
            prices = dict(start)
            prices[("NUS", "regular")] = price
            return {WEEK_0: prices}

        # Each correction is under 40% from the one before, but the anchor stays last week's price.
        self.assertEqual(round(anchor * 1.35, 3), chain.run(week_with(round(anchor * 1.35, 3)), NOW)
                         .payload["prices"]["NUS"]["regular"])
        second = chain.run(week_with(round(anchor * 1.7, 3)), NOW + 3_600_000)
        self.assertNotIn("regular", second.payload["prices"]["NUS"])
        third = chain.run(week_with(round(anchor * 1.9, 3)), NOW + 7_200_000)
        self.assertIsNone(third.payload, "the omitted pair is still omitted: nothing changed")
        anchors = json.loads(Path(chain.anchors).read_bytes())["anchors"]
        self.assertEqual({"period": W0 - WEEK, "price": anchor}, anchors["NUS"]["regular"])

    # ---- future period --------------------------------------------------------------------------------------

    def test_a_future_period_is_refused_and_the_next_run_recovers(self):
        chain = self.chain()
        chain.run({monday(W0 - WEEK): feedtest.base_prices()}, NOW - WEEK)
        future = monday(W0 + 20 * WEEK)
        with self.assertRaises(FeedError):
            chain.run({future: feedtest.base_prices(9.0)}, NOW)
        # A future row beside real ones is refused on its own and moves no anchor.
        poisoned = {future: {("NUS", "regular"): 2.0}, WEEK_0: feedtest.base_prices(1.02)}
        outcome = chain.run(poisoned, NOW + 60_000)
        self.assertEqual(W0, outcome.payload["period"])
        self.assertTrue(any(line.startswith("refused NUS/regular") for line in outcome.log))
        self.assertEqual(round(3.0 * 1.02, 3), outcome.payload["prices"]["NUS"]["regular"])
        anchors = json.loads(Path(chain.anchors).read_bytes())["anchors"]
        self.assertEqual(W0 - WEEK, anchors["NUS"]["regular"]["period"])
        # A date that is not a Monday is not an EIA week either.
        self.assertIsNone(publish_fuel.period_ms("2026-11-17"))

    # ---- sustained move -------------------------------------------------------------------------------------

    def test_a_sustained_move_over_40_percent_is_accepted_after_8_weeks(self):
        chain = self.chain()
        start = feedtest.base_prices()
        chain.run({monday(W0 - WEEK): start}, NOW - WEEK)
        moved = dict(start)
        moved[("SCA", "diesel")] = round(start[("SCA", "diesel")] * 1.6, 3)
        accepted_at = None
        for k in range(12):
            outcome = chain.run({monday(W0 + k * WEEK): moved}, NOW + k * WEEK)
            if "diesel" in outcome.payload["prices"]["SCA"]:
                accepted_at = k
                break
            self.assertTrue(any(line.startswith("omitted SCA/diesel") and "40%" in line for line in outcome.log))
        # The anchor is last published in week -1; week k is (k + 1) weeks newer, dropped once over 8 weeks.
        self.assertEqual(8, accepted_at)
        self.assertTrue(any(line.startswith("anchor dropped SCA/diesel") for line in outcome.log))


class SetupTest(unittest.TestCase):
    """The workflow's not-set-up and half-setup decisions (publish_fuel.setup_state)."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.key, self.pub = feedtest.keypair(self.dir)
        _, self.backup = feedtest.keypair(self.dir, "backup")
        self.pem = self.key.read_text()
        self.empty = feedtest.empty_pins(self.dir / "")
        pinned_dir = self.dir / "pinned"
        pinned_dir.mkdir()
        self.pinned = feedtest.pin_file(pinned_dir, fuel=(self.pub, self.backup))

    def tearDown(self):
        self.tmp.cleanup()

    def state(self, pins, **env):
        base = {"FEED_FUEL_SIGNING_KEY": "", "EIA_API_KEY": "", "FEED_FUEL_PUBLIC_KEY": ""}
        base.update(env)
        with mock.patch.dict(os.environ, base):
            return publish_fuel.setup_state(pins)

    def refused(self, pins, **env):
        with self.assertRaises(FeedError) as e:
            self.state(pins, **env)
        message = str(e.exception)
        for name in ("EIA_API_KEY", "FEED_FUEL_SIGNING_KEY", publish_fuel.EIA_LINE):
            self.assertIn(name, message)
        return message

    def test_no_key_and_no_pin_is_not_set_up(self):
        self.assertEqual("not-set-up", self.state(self.empty))
        self.assertEqual("not-set-up", self.state(self.empty, EIA_API_KEY="fixture-not-a-key"))

    def test_the_not_set_up_run_exits_cleanly_with_one_notice(self):
        out = self.dir / "github-output"
        args = mock.Mock(pins=str(self.empty), out=str(self.dir / "o"))
        env = {"FEED_FUEL_SIGNING_KEY": "", "EIA_API_KEY": "", "FEED_FUEL_PUBLIC_KEY": "",
               "GITHUB_OUTPUT": str(out), "GITHUB_STEP_SUMMARY": str(self.dir / "summary")}
        with mock.patch.dict(os.environ, env), mock.patch("builtins.print") as printed:
            self.assertEqual(0, publish_fuel.run_ci(args, NOW))
        printed.assert_called_once_with("::notice::fuel feed not set up: see docs/feeds/SELF_HOSTING.md")
        self.assertEqual("published=false\n", out.read_text())

    def test_a_pin_with_no_key_is_refused(self):
        self.refused(self.pinned, EIA_API_KEY="fixture-not-a-key")
        self.refused(self.empty, FEED_FUEL_PUBLIC_KEY=self.pub, EIA_API_KEY="fixture-not-a-key")

    def test_a_key_with_no_pin_is_refused(self):
        self.refused(self.empty, FEED_FUEL_SIGNING_KEY=self.pem, EIA_API_KEY="fixture-not-a-key")

    def test_a_key_that_is_not_the_pinned_one_is_refused(self):
        other, _ = feedtest.keypair(self.dir, "other")
        self.refused(self.pinned, FEED_FUEL_SIGNING_KEY=other.read_text(), EIA_API_KEY="fixture-not-a-key")

    def test_a_missing_eia_key_is_refused(self):
        self.refused(self.pinned, FEED_FUEL_SIGNING_KEY=self.pem)

    def test_full_setup_is_ready(self):
        self.assertEqual("ready", self.state(self.pinned, FEED_FUEL_SIGNING_KEY=self.pem, EIA_API_KEY="k"))
        self.assertEqual("ready", self.state(self.empty, FEED_FUEL_SIGNING_KEY=self.pem, EIA_API_KEY="k",
                                             FEED_FUEL_PUBLIC_KEY=self.pub))

    def test_the_ci_run_signs_and_hands_the_files_on_as_one_output(self):
        out = self.dir / "github-output"
        args = mock.Mock(pins=str(self.empty), out=str(self.dir / "o"), source=None)
        env = {"FEED_FUEL_SIGNING_KEY": self.pem, "EIA_API_KEY": "fixture-not-a-key", "FEED_FUEL_PUBLIC_KEY": self.pub,
               "FUEL_STATE": "", "GITHUB_OUTPUT": str(out), "GITHUB_STEP_SUMMARY": str(self.dir / "summary")}
        body = eia_body({WEEK_0: feedtest.base_prices()})
        with mock.patch.dict(os.environ, env), mock.patch.object(publish_fuel, "fetch_eia", return_value=body), \
                mock.patch("builtins.print"):
            self.assertEqual(0, publish_fuel.run_ci(args, NOW))
        lines = dict(line.split("=", 1) for line in out.read_text().splitlines())
        self.assertEqual("true", lines["published"])
        unpacked = self.dir / "unpacked"
        feedlib.unpack_dir(lines["bundle"], unpacked)
        self.assertTrue((unpacked / "fuel" / "envelope.json").is_file())
        self.assertTrue((unpacked / "anchors.json").is_file())
        # The bundle carries no private key material.
        for path in unpacked.rglob("*"):
            if path.is_file():
                self.assertNotIn(b"PRIVATE KEY", path.read_bytes())


if __name__ == "__main__":
    unittest.main()
