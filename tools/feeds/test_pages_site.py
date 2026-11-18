"""Tests of the Pages snapshot, carry-over, assembly and live checks (#1564). No network: the live site and the
GitHub API are fakes. Run: python3 -I -m unittest discover -s tools/feeds -p 'test_*.py'
"""
from __future__ import annotations

import datetime as dt
import io
import json
import os
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

import feedlib
import feedtest
import pages_site
import publish_fuel
from feedlib import FeedError
from feedtest import BASE_URL, NOW, WEEK, FakeSite, eia_body, monday

W0 = feedtest.week_ms("2026-11-16")
RUN = 900


class Deploys:
    """Successive Pages runs: carry from the last snapshot, maybe sign fuel, assemble, then the site goes live."""

    def __init__(self, test):
        self.test = test
        self.root = Path(test.tmp.name) / "deploys"
        self.snapshot = None
        self.live = FakeSite()
        self.n = 0

    def run(self, weeks=None, now=NOW, deploy=True):
        self.n += 1
        run_dir = self.root / str(self.n)
        site = run_dir / "site"
        site.mkdir(parents=True)
        (site / "index.html").write_text("<p>gig.log</p>")
        (site / ".nojekyll").write_text("")
        with redirect_stdout(io.StringIO()):
            carried = pages_site.carry(self.snapshot, self.test.epochs, run_dir / "carried", BASE_URL, RUN + self.n,
                                       self.live.fetch)
        fuel_dir = None
        if weeks is not None:
            out = run_dir / "fuel-generation"
            live = run_dir / "carried" / "feeds" / "fuel"
            anchors = run_dir / "carried" / "state" / "fuel" / "anchors.json"
            args = feedtest.Args(out, self.test.pins, key_file=self.test.key,
                                 live=str(live) if "fuel" in carried else None,
                                 anchors=str(anchors) if anchors.is_file() else None)
            with mock.patch.dict(os.environ, {"EIA_API_KEY": "fixture-not-a-key"}):
                outcome = publish_fuel.publish(args, now, eia_body(weeks))
            if outcome.payload is not None:
                fuel_dir = pages_site.stage_fuel(out)
        index = pages_site.assemble(site, run_dir / "carried", fuel_dir, self.test.epochs, run_dir / "snapshot",
                                    RUN + self.n, BASE_URL, self.live.fetch)
        if deploy:
            self.live = FakeSite.of(site)
            self.snapshot = run_dir / "snapshot"
        return site, index

    def record(self):
        return json.loads((self.snapshot / "snapshot.json").read_bytes())


class PagesSiteTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.key, self.pub = feedtest.keypair(self.dir)
        self.pins = feedtest.empty_pins(self.dir)
        self.epochs = feedlib.trusted_epochs(self.pins, self.pub)

    def tearDown(self):
        self.tmp.cleanup()

    def fuel_files(self, site):
        return sorted(p.name for p in (site / "feeds" / "fuel").iterdir())

    # ---- bootstrap and carry-over ---------------------------------------------------------------------------

    def test_bootstrap_publishes_only_its_own_feed(self):
        deploys = Deploys(self)
        site, index = deploys.run({monday(W0): feedtest.base_prices()})
        self.assertEqual(2, len(self.fuel_files(site)))
        self.assertTrue((site / "feeds" / "README.md").is_file())
        self.assertEqual(["fuel"], list(deploys.record()["feeds"]))
        self.assertEqual(index, deploys.record()["site"])
        self.assertIn(".nojekyll", index)
        self.assertIn("feeds/fuel/envelope.json", index)

    def test_each_feed_keeps_its_current_and_previous_payloads(self):
        deploys = Deploys(self)
        deploys.run({monday(W0 - WEEK): feedtest.base_prices()}, NOW - WEEK)
        first = deploys.record()["feeds"]["fuel"]["current"]
        deploys.run({monday(W0): feedtest.base_prices(1.01)}, NOW)
        second = deploys.record()["feeds"]["fuel"]
        self.assertEqual(first, second["previous"])
        site, _ = deploys.run({monday(W0 + WEEK): feedtest.base_prices(1.02)}, NOW + WEEK)
        third = deploys.record()["feeds"]["fuel"]
        self.assertEqual(second["current"], third["previous"])
        self.assertEqual(sorted(["envelope.json"] + [f"{d}.json" for d in third["current"] + third["previous"]]),
                         self.fuel_files(site))
        # The anchors ride the snapshot: last week's prices anchor this one.
        anchors = json.loads((deploys.snapshot / "state" / "fuel" / "anchors.json").read_bytes())["anchors"]
        self.assertEqual(W0, anchors["NUS"]["regular"]["period"])

    def test_a_push_carries_the_fuel_feed_over_unchanged(self):
        deploys = Deploys(self)
        deploys.run({monday(W0): feedtest.base_prices()})
        before = deploys.record()["feeds"]
        site, _ = deploys.run(None)
        self.assertEqual(before, deploys.record()["feeds"])
        self.assertEqual(deploys.live.files["feeds/fuel/envelope.json"],
                         (site / "feeds" / "fuel" / "envelope.json").read_bytes())

    def test_carry_refuses_when_the_live_site_disagrees_with_the_snapshot(self):
        deploys = Deploys(self)
        deploys.run({monday(W0): feedtest.base_prices()})
        record = deploys.record()
        for change in ("other fuel", "no fuel", "extra rules"):
            live = FakeSite(deploys.live.files)
            if change == "other fuel":
                live.files["feeds/fuel/envelope.json"] = b'{"manifest":"{}","key":"0","sig":""}'
            elif change == "no fuel":
                del live.files["feeds/fuel/envelope.json"]
            else:
                live.files["feeds/rules/envelope.json"] = b"{}"
            with self.assertRaises(FeedError, msg=change) as e:
                pages_site.check_live_agrees(record, BASE_URL, RUN, live.fetch, attempts=1)
            self.assertIn("Refusing to deploy", str(e.exception))
        pages_site.check_live_agrees(record, BASE_URL, RUN, deploys.live.fetch, attempts=1)
        self.assertTrue(all("?r=" in url for url in deploys.live.requests))

    def test_a_feed_that_fails_the_current_pins_is_dropped_with_one_warning(self):
        deploys = Deploys(self)
        deploys.run({monday(W0): feedtest.base_prices()})
        _, rotated = feedtest.keypair(self.dir, "rotated")
        epochs = feedlib.trusted_epochs(self.pins, rotated)
        out = io.StringIO()
        with redirect_stdout(out):
            carried = pages_site.carry(deploys.snapshot, epochs, self.dir / "carried", BASE_URL, RUN,
                                       deploys.live.fetch)
        self.assertEqual({}, carried)
        warnings = [line for line in out.getvalue().splitlines() if line.startswith("::warning::")]
        self.assertEqual(1, len(warnings))
        self.assertIn("fuel: dropped", warnings[0])
        self.assertFalse((self.dir / "carried" / "feeds" / "fuel").exists())

    def test_a_tampered_carried_payload_drops_the_feed(self):
        deploys = Deploys(self)
        deploys.run({monday(W0): feedtest.base_prices()})
        digest = deploys.record()["feeds"]["fuel"]["current"][0]
        path = deploys.snapshot / "feeds" / "fuel" / f"{digest}.json"
        path.write_bytes(path.read_bytes().replace(b"3.0", b"3.1", 1))
        with redirect_stdout(io.StringIO()):
            carried = pages_site.carry(deploys.snapshot, self.epochs, self.dir / "carried", BASE_URL, RUN,
                                       deploys.live.fetch)
        self.assertNotIn("fuel", carried)

    # ---- sequence against the live site ---------------------------------------------------------------------

    def test_a_sequence_below_the_live_one_is_refused(self):
        deploys = Deploys(self)
        deploys.run({monday(W0): feedtest.base_prices()}, NOW)
        newer = deploys.live
        older = Deploys(self)
        older.root = self.dir / "older"
        older.run({monday(W0): feedtest.base_prices()}, NOW - 60_000, deploy=False)
        feeds = {"fuel": pages_site.load_feed(older.root / "1" / "snapshot", "fuel", self.epochs["fuel"])}
        with self.assertRaises(FeedError) as e:
            pages_site.check_sequences(feeds, BASE_URL, RUN, newer.fetch)
        self.assertIn("below the live", str(e.exception))
        pages_site.check_sequences(feeds, BASE_URL, RUN, FakeSite().fetch)

    def test_an_equal_sequence_with_another_manifest_is_refused(self):
        deploys = Deploys(self)
        deploys.run({monday(W0): feedtest.base_prices()}, NOW)
        live = deploys.live
        twin = Deploys(self)
        twin.root = self.dir / "twin"
        twin.live = FakeSite()
        twin.run({monday(W0): feedtest.base_prices(1.01)}, NOW, deploy=False)
        feeds = {"fuel": pages_site.load_feed(twin.root / "1" / "snapshot", "fuel", self.epochs["fuel"])}
        with self.assertRaises(FeedError) as e:
            pages_site.check_sequences(feeds, BASE_URL, RUN, live.fetch)
        self.assertIn("different manifest", str(e.exception))

    # ---- verify after deploy --------------------------------------------------------------------------------

    def test_verify_live_waits_for_every_file_then_passes(self):
        deploys = Deploys(self)
        site, index = deploys.run({monday(W0): feedtest.base_prices()})
        live, calls = FakeSite.of(site), {"n": 0}

        def lagging(url):
            calls["n"] += 1
            return b"stale" if calls["n"] <= 3 else live.fetch(url)

        clock = iter(range(0, 10_000, 30))
        with redirect_stdout(io.StringIO()):
            pages_site.verify_live(index, BASE_URL, RUN, lagging, clock=lambda: next(clock), sleep=lambda _: None)
        self.assertTrue(all(url.endswith(f"?r={RUN}") for url in live.requests))

    def test_verify_live_fails_after_12_minutes(self):
        deploys = Deploys(self)
        _, index = deploys.run({monday(W0): feedtest.base_prices()})
        clock = iter(range(0, 10_000, 30))
        with self.assertRaises(FeedError) as e:
            pages_site.verify_live(index, BASE_URL, RUN, FakeSite().fetch, clock=lambda: next(clock),
                                   sleep=lambda _: None)
        self.assertIn("720 s", str(e.exception))

    def test_the_index_survives_a_job_output(self):
        index = {"feeds/fuel/envelope.json": "a" * 64, "privacy/index.html": "b" * 64}
        self.assertEqual(index, feedlib.unpack_index(feedlib.pack_index(index)))
        with self.assertRaises(FeedError):
            feedlib.unpack_index(feedlib.pack_index({"../etc/passwd": "a" * 64}))


class FakeGitHub:
    def __init__(self, runs, deployed_ids, artifacts=None):
        self.runs, self.deployed_ids, self.artifacts = runs, set(deployed_ids), artifacts or {}
        self.calls = []

    def get(self, path):
        self.calls.append(path)
        if "/actions/workflows/pages.yml/runs" in path:
            page = int(path.rsplit("page=", 1)[1])
            return {"workflow_runs": self.runs[(page - 1) * 100:page * 100]}
        if path.endswith("/jobs?per_page=100"):
            run_id = int(path.split("/runs/")[1].split("/")[0])
            conclusion = "success" if run_id in self.deployed_ids else "skipped"
            return {"jobs": [{"name": "build", "steps": []},
                             {"name": "deploy", "steps": [{"name": "Deploy to Pages", "conclusion": conclusion}]}]}
        if "/artifacts?name=" in path:
            run_id = int(path.split("/runs/")[1].split("/")[0])
            return {"artifacts": self.artifacts.get(run_id, [])}
        raise AssertionError(path)

    def download(self, path):
        return self.zip

    @staticmethod
    def run(run_id, event="schedule", branch="master", fork=False, days_ago=1):
        created = dt.datetime(2026, 11, 17, tzinfo=dt.timezone.utc) - dt.timedelta(days=days_ago)
        return {"id": run_id, "event": event, "head_branch": branch, "created_at": created.isoformat(),
                "repository": {"id": 1}, "head_repository": {"id": 2 if fork else 1}}


class SnapshotLookupTest(unittest.TestCase):
    NOW = dt.datetime(2026, 11, 17, tzinfo=dt.timezone.utc)

    def test_the_newest_trusted_run_whose_deploy_step_succeeded_is_chosen(self):
        run = FakeGitHub.run
        runs = [
            run(60, event="pull_request"),           # a fork's PR from a branch named master
            run(59, fork=True, event="push"),         # a fork's run
            run(58),                                  # a no-change cron: deploy skipped
            run(57, branch="feature"),                # another branch
            run(56, event="workflow_dispatch"),       # deployed
            run(55, event="push"),                    # deployed, older
        ]
        gh = FakeGitHub(runs, deployed_ids={60, 59, 57, 56, 55})
        self.assertEqual(56, pages_site.find_snapshot_run(gh, "o/r", 61, self.NOW)["id"])
        self.assertFalse(any("/runs/60/" in c or "/runs/59/" in c or "/runs/57/" in c for c in gh.calls))

    def test_the_current_and_later_runs_never_count(self):
        gh = FakeGitHub([FakeGitHub.run(70), FakeGitHub.run(61)], deployed_ids={70, 61})
        self.assertEqual(61, pages_site.find_snapshot_run(gh, "o/r", 65, self.NOW)["id"])

    def test_nothing_inside_the_retention_window_is_a_bootstrap(self):
        gh = FakeGitHub([FakeGitHub.run(5, days_ago=91)], deployed_ids={5})
        self.assertIsNone(pages_site.find_snapshot_run(gh, "o/r", 10, self.NOW))
        self.assertIsNone(pages_site.find_snapshot_run(FakeGitHub([], set()), "o/r", 10, self.NOW))

    def test_the_artifact_is_taken_from_that_run_and_extracted_safely(self):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("snapshot.json", "{}")
            archive.writestr("feeds/fuel/envelope.json", "{}")
        gh = FakeGitHub([], set(), artifacts={56: [{"id": 7, "name": "feeds-site", "expired": False}]})
        gh.zip = buffer.getvalue()
        with tempfile.TemporaryDirectory() as tmp:
            self.assertTrue(pages_site.fetch_snapshot(gh, "o/r", 56, Path(tmp)))
            self.assertTrue((Path(tmp) / "feeds" / "fuel" / "envelope.json").is_file())
            self.assertFalse(pages_site.fetch_snapshot(gh, "o/r", 55, Path(tmp)))

        evil = io.BytesIO()
        with zipfile.ZipFile(evil, "w") as archive:
            archive.writestr("../outside.json", "{}")
        gh.zip = evil.getvalue()
        with tempfile.TemporaryDirectory() as tmp, self.assertRaises(FeedError):
            pages_site.fetch_snapshot(gh, "o/r", 56, Path(tmp))


if __name__ == "__main__":
    unittest.main()
