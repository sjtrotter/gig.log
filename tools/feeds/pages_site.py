#!/usr/bin/env python3
"""The feeds half of the Pages deploy (#1564): the snapshot, the carry-over, the assembly and the live checks.

A Pages deploy replaces the whole site, so every deploy rebuilds it: the privacy pages from tools/site/build_site.py
and every feed. A feed this run did not sign is carried over from the snapshot of the last run of pages.yml that
deployed, found by run id (never by artifact name: a fork's pull request can upload an artifact of any name). Stdlib
and `openssl` only.

  pages_site.py carry --repo OWNER/NAME --run-id N --out DIR
      Finds the snapshot, checks the live site still serves what it recorded, re-verifies every file against the
      current pins and writes the carried feeds to DIR. Outputs base_url and fuel_state (the fuel job's input).
  pages_site.py assemble --site DIR --carried DIR --snapshot DIR --run-id N [--base-url URL]
      Adds the feeds (this run's fuel generation from FUEL_BUNDLE, else the carried one) to the built site, refuses a
      sequence below the live one (or equal with another digest), and writes the next snapshot. Outputs index.
  pages_site.py verify-live --base-url URL --run-id N
      After the deploy: fetches every file of INDEX from the live site, cache-busted, for up to 12 minutes.

Environment: GH_TOKEN (carry), FEED_FUEL_PUBLIC_KEY (a self-hosted fuel feed's key), FUEL_BUNDLE (assemble), INDEX
(verify-live).
"""
from __future__ import annotations

import argparse
import datetime as dt
import io
import re
import json
import os
import shutil
import sys
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import feedlib  # noqa: E402
from feedlib import FeedError  # noqa: E402

# The snapshot artifact is `feeds-site-<run attempt>`: artifact names are fixed once uploaded, so a re-run uploads its
# own, and the deploy job's name records which one it deployed.
SNAPSHOT_NAME = re.compile(r"feeds-site-[1-9][0-9]{0,4}")
WORKFLOW_FILE = "pages.yml"
DEPLOY_JOB = "deploy"
DEPLOY_STEP = "Deploy to Pages"
TRUSTED_EVENTS = {"push", "schedule", "workflow_dispatch"}
RETENTION_DAYS = 90
MAX_RUN_PAGES = 10
MAX_JOB_PAGES = 20
VERIFY_SECONDS = 12 * 60
VERIFY_INTERVAL = 30
SNAPSHOT_FORMAT = 1
FEEDS_README = (
    "# gig.log signed feeds\n\n"
    "Each directory holds one feed: `envelope.json` (the signed manifest) and its payloads, each named by its own\n"
    "sha256. The format, the client rules and the payloads are in docs/feeds/FORMAT.md of the gig.log repository.\n"
)


def warn(message: str) -> None:
    print(f"::warning::{message}")
    feedlib.summary(f"WARN {message}")


def notice(message: str) -> None:
    print(f"::notice::{message}")
    feedlib.summary(message)


# ---- GitHub ---------------------------------------------------------------------------------------------------


class GitHub:
    """The REST calls the snapshot lookup needs. The token is never sent on a redirect (artifact downloads go to
    a signed storage URL)."""

    def __init__(self, token: str, api: str = "https://api.github.com"):
        self.token = token
        self.api = api

    def _request(self, url: str) -> urllib.request.Request:
        request = urllib.request.Request(url, headers={
            "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "feeds-pages",
        })
        request.add_unredirected_header("Authorization", f"Bearer {self.token}")
        return request

    def get(self, path: str):
        """The JSON at [path], or None on 404."""
        try:
            with urllib.request.urlopen(self._request(self.api + path), timeout=60) as response:
                return json.loads(response.read())
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            raise FeedError(f"GitHub API {path.split('?')[0]}: HTTP {e.code}") from None

    def download(self, path: str) -> bytes:
        try:
            with urllib.request.urlopen(self._request(self.api + path), timeout=120) as response:
                return response.read(64 * 1024 * 1024)
        except urllib.error.HTTPError as e:
            raise FeedError(f"GitHub API {path}: HTTP {e.code}") from None


class Deployment:
    """One successful deploy step: which run and attempt, when it finished, and the snapshot artifact it deployed."""

    def __init__(self, run_id: int, attempt: int, completed_at: dt.datetime, snapshot: str | None):
        self.run_id = run_id
        self.attempt = attempt
        self.completed_at = completed_at
        self.snapshot = snapshot


def _time(text: str) -> dt.datetime:
    return dt.datetime.fromisoformat(text.replace("Z", "+00:00"))


def find_last_deployment(gh, repo: str, now: dt.datetime) -> Deployment | None:
    """The last successful deploy of pages.yml: on master, from this repository, a push, schedule or dispatch run,
    across every attempt of every run, by the deploy step's completion time. None when there is none inside the
    artifact retention window (bootstrap).

    A deploy counts by its step, not the run's conclusion: a deploy that went through before its verify step timed out
    is what the live site serves. A re-run attempt can deploy after a newer run did, so run ids do not order deploys;
    a run's `updated_at` bounds when any of its attempts finished, which ends the scan. GitHub re-runs only runs under
    30 days old, so the 90-day listing holds every attempt that could still be newest.
    """
    oldest = now - dt.timedelta(days=RETENTION_DAYS)
    runs = []
    for page in range(1, MAX_RUN_PAGES + 1):
        listing = gh.get(f"/repos/{repo}/actions/workflows/{WORKFLOW_FILE}/runs?branch=master&per_page=100&page={page}")
        batch = (listing or {}).get("workflow_runs") or []
        runs += [r for r in batch if _time(r["created_at"]) >= oldest and trusted_run(r)]
        if len(batch) < 100 or any(_time(r["created_at"]) < oldest for r in batch):
            break
    best = None
    for run in sorted(runs, key=lambda r: _time(r["updated_at"]), reverse=True):
        if best is not None and _time(run["updated_at"]) < best.completed_at:
            break
        for found in deployments(gh, repo, run["id"]):
            if best is None or found.completed_at > best.completed_at:
                best = found
    return best


def trusted_run(run: dict) -> bool:
    head, base = run.get("head_repository") or {}, run.get("repository") or {}
    return (
        run.get("event") in TRUSTED_EVENTS
        and run.get("head_branch") == "master"
        and head.get("id") is not None and head.get("id") == base.get("id")
    )


def deployments(gh, repo: str, run_id: int) -> list[Deployment]:
    """Every attempt's successful deploy step in one run. The deploy job's name carries the snapshot it deployed
    (`deploy feeds-site-<attempt>`); a job named plainly `deploy` predates the snapshot."""
    jobs = []
    for page in range(1, MAX_JOB_PAGES + 1):
        batch = (gh.get(f"/repos/{repo}/actions/runs/{run_id}/jobs?filter=all&per_page=100&page={page}") or {}).get("jobs") or []
        jobs += batch
        if len(batch) < 100:
            break
    else:
        raise FeedError(f"run {run_id}: more than {MAX_JOB_PAGES * 100} jobs; a truncated history is not an absent deploy")
    out = []
    for job in jobs:
        name = job.get("name") or ""
        if name != DEPLOY_JOB and not name.startswith(DEPLOY_JOB + " "):
            continue
        snapshot = name[len(DEPLOY_JOB) + 1:] or None
        if snapshot is not None and not SNAPSHOT_NAME.fullmatch(snapshot):
            continue
        for step in job.get("steps") or []:
            if step.get("name") == DEPLOY_STEP and step.get("conclusion") == "success" and step.get("completed_at"):
                out.append(Deployment(run_id, job.get("run_attempt", 1), _time(step["completed_at"]), snapshot))
    return out


def fetch_snapshot(gh, repo: str, run_id: int, name: str, dest: Path) -> bool:
    """Extracts the snapshot artifact [name] of that run into [dest]. False when it is gone (expired or deleted)."""
    listing = gh.get(f"/repos/{repo}/actions/runs/{run_id}/artifacts?name={name}&per_page=10") or {}
    found = [a for a in listing.get("artifacts") or [] if a.get("name") == name and not a.get("expired")]
    if not found:
        return False
    data = gh.download(f"/repos/{repo}/actions/artifacts/{found[0]['id']}/zip")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            target = dest / feedlib.safe_relative(info.filename)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(info))
    return True


def pages_url(gh, repo: str) -> str:
    """The site's base URL from the Pages API, ending in '/'; empty when Pages is not set up."""
    site = gh.get(f"/repos/{repo}/pages")
    url = (site or {}).get("html_url") or ""
    return url if not url or url.endswith("/") else url + "/"


# ---- the live site --------------------------------------------------------------------------------------------


def fetch_live(url: str, attempts: int = 4, pause: float = 5, sleep=time.sleep) -> bytes | None:
    """One file from the live site, or None on 404.

    Pages answers the first request for a missing path on a cold edge with a 503 now and then, and each run's
    ?r=<run id> makes every path cold, so a 5xx is retried; one that persists still refuses.
    """
    request = urllib.request.Request(url, headers={"Cache-Control": "no-cache", "User-Agent": "feeds-pages"})
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.read(feedlib.MAX_FILE_BYTES["rules"] + 1)
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if e.code < 500 or attempt + 1 == attempts:
                raise FeedError(f"{url}: HTTP {e.code}") from None
        except (urllib.error.URLError, OSError) as e:
            raise FeedError(f"{url}: {type(e).__name__}") from None
        sleep(pause)


def live_url(base_url: str, path: str, run_id: int) -> str:
    return f"{base_url}{path}?r={run_id}"


def envelope_path(feed: str) -> str:
    return f"feeds/{feed}/envelope.json"


# ---- snapshot files -------------------------------------------------------------------------------------------


def read_record(snapshot: Path) -> dict:
    root = feedlib.strict_loads((snapshot / "snapshot.json").read_bytes())
    if not isinstance(root, dict) or root.get("format") != SNAPSHOT_FORMAT or not isinstance(root.get("feeds"), dict):
        raise FeedError("malformed snapshot.json")
    return root


class FeedFiles:
    """One feed's files: the current envelope, the envelope of the previous generation, and their payloads."""

    def __init__(self, feed: str, current: feedlib.Verified, payloads: dict[str, bytes],
                 previous: feedlib.Verified | None = None, anchors: bytes | None = None):
        self.feed = feed
        self.current = current
        self.previous = previous
        self.payloads = payloads
        self.anchors = anchors

    def record(self) -> dict:
        return {
            "sequence": self.current.sequence,
            "digest": self.current.digest,
            "envelope_sha256": self.current.envelope_sha256,
            "current": sorted(f[1] for f in self.current.files),
            "previous": sorted(f[1] for f in self.previous.files) if self.previous else [],
        }


def _verified_with_payloads(feed: str, envelope: bytes, epoch: feedlib.Epoch, read) -> tuple:
    verified = feedlib.verify_envelope(envelope, feed, epoch)
    payloads = {}
    for _, digest, _ in verified.files:
        data = read(digest)
        if data is None:
            raise FeedError(f"{feed}: payload {digest[:12]} is missing")
        feedlib.verify_payload(verified, digest, data)
        payloads[digest] = data
    return verified, payloads


def load_feed(root: Path, feed: str, epoch: feedlib.Epoch | None) -> FeedFiles:
    """Re-verifies one feed of a snapshot (or of the carried directory) against the current pins."""
    if epoch is None:
        raise FeedError(f"{feed}: no key is pinned for it now")
    feed_dir = root / "feeds" / feed

    def read(digest):
        path = feed_dir / f"{digest}.json"
        return path.read_bytes() if path.is_file() else None

    current, payloads = _verified_with_payloads(feed, (feed_dir / "envelope.json").read_bytes(), epoch, read)
    previous = None
    previous_path = root / "state" / feed / "previous-envelope.json"
    if previous_path.is_file():
        try:
            previous, older = _verified_with_payloads(feed, previous_path.read_bytes(), epoch, read)
            payloads.update(older)
        except FeedError as e:
            warn(f"{feed}: the previous generation is dropped ({e})")
    anchors_path = root / "state" / feed / "anchors.json"
    anchors = anchors_path.read_bytes() if anchors_path.is_file() else None
    return FeedFiles(feed, current, payloads, previous, anchors)


def write_feed(files: FeedFiles, site: Path | None, state_root: Path) -> None:
    """The feed's public files under site/feeds/<feed>/, and its state under state_root/state/<feed>/."""
    for root in [r for r in (site, state_root) if r is not None]:
        feed_dir = root / "feeds" / files.feed
        feed_dir.mkdir(parents=True, exist_ok=True)
        (feed_dir / "envelope.json").write_bytes(files.current.envelope_bytes)
        for digest, data in files.payloads.items():
            (feed_dir / f"{digest}.json").write_bytes(data)
    state = state_root / "state" / files.feed
    if files.previous is not None or files.anchors is not None:
        state.mkdir(parents=True, exist_ok=True)
    if files.previous is not None:
        (state / "previous-envelope.json").write_bytes(files.previous.envelope_bytes)
    if files.anchors is not None:
        (state / "anchors.json").write_bytes(files.anchors)


# ---- carry ----------------------------------------------------------------------------------------------------


def check_live_agrees(record: dict, base_url: str, run_id: int, fetch=fetch_live, attempts=3, pause=20) -> None:
    """Refuses when the live site serves an envelope the snapshot did not record (or lacks one it did)."""
    if not base_url:
        raise FeedError("the snapshot names a deployed site but the Pages API gives no URL")
    for attempt in range(attempts):
        mismatched = []
        for feed in feedlib.FEEDS:
            live = fetch(live_url(base_url, envelope_path(feed), run_id))
            expected = (record["feeds"].get(feed) or {}).get("envelope_sha256")
            got = feedlib.sha256_hex(live) if live is not None else None
            if got != expected:
                mismatched.append(f"{feed}: live {got or 'absent'}, snapshot {expected or 'absent'}")
        if not mismatched:
            return
        if attempt + 1 < attempts:
            time.sleep(pause)
    raise FeedError(
        f"the live site does not serve what the snapshot of run {record.get('run_id')} recorded "
        f"({'; '.join(mismatched)}). Refusing to deploy over it; see docs/feeds/SELF_HOSTING.md, Recovery.")


def carry(snapshot: Path | None, epochs: dict, out: Path, base_url: str, run_id: int, fetch=fetch_live) -> dict:
    """Writes every feed of the snapshot that still verifies to [out]; returns {feed: FeedFiles}."""
    out.mkdir(parents=True, exist_ok=True)
    if snapshot is None:
        notice("no snapshot of a deployed site: starting from an empty site (bootstrap)")
        (out / "carried.json").write_bytes(feedlib.canonical_json({"run_id": None, "feeds": []}))
        return {}
    record = read_record(snapshot)
    check_live_agrees(record, base_url, run_id, fetch)
    carried = {}
    for feed in record["feeds"]:
        if feed not in feedlib.FEEDS:
            warn(f"{feed}: not a feed this site knows; dropped")
            continue
        try:
            files = load_feed(snapshot, feed, epochs.get(feed))
        except (FeedError, OSError) as e:
            warn(f"{feed}: dropped from the site, it fails the current pins ({e})")
            continue
        write_feed(files, None, out)
        carried[feed] = files
        print(f"carried {feed}: sequence {files.current.sequence}")
    (out / "carried.json").write_bytes(feedlib.canonical_json({"run_id": record.get("run_id"),
                                                               "feeds": sorted(carried)}))
    return carried


# ---- assemble -------------------------------------------------------------------------------------------------


def stage_fuel(publisher_out: Path) -> Path:
    """The publisher writes fuel/ and anchors.json; the loader reads the snapshot layout (feeds/fuel/)."""
    (publisher_out / "feeds").mkdir(exist_ok=True)
    shutil.move(str(publisher_out / "fuel"), str(publisher_out / "feeds" / "fuel"))
    return publisher_out


def adopt_fuel(fuel_dir: Path, epoch: feedlib.Epoch | None, carried: FeedFiles | None) -> FeedFiles:
    """This run's fuel generation, verified; the carried generation becomes its previous one."""
    if epoch is None:
        raise FeedError("fuel: this run signed a generation but no fuel key is pinned")
    new = load_feed(fuel_dir, "fuel", epoch)
    if carried is not None and new.current.sequence <= carried.current.sequence:
        raise FeedError(f"fuel: sequence {new.current.sequence} is not above the carried {carried.current.sequence}")
    anchors_path = fuel_dir / "anchors.json"
    anchors = anchors_path.read_bytes() if anchors_path.is_file() else (carried.anchors if carried else None)
    payloads = dict(new.payloads)
    previous = carried.current if carried else None
    if carried is not None:
        payloads.update({d: carried.payloads[d] for _, d, _ in carried.current.files})
    return FeedFiles("fuel", new.current, payloads, previous, anchors)


def check_sequences(feeds: dict, base_url: str, run_id: int, fetch=fetch_live) -> None:
    """Refuses a feed whose sequence is below the live one, or equal to it with a different manifest."""
    if not base_url:
        return
    for feed, files in feeds.items():
        live = fetch(live_url(base_url, envelope_path(feed), run_id))
        if live is None:
            continue
        try:
            root = feedlib.strict_loads(live)
            manifest_text = root["manifest"]
            manifest = feedlib.strict_loads(manifest_text)
            live_sequence = manifest["sequence"]
        except (FeedError, KeyError, TypeError) as e:
            raise FeedError(f"{feed}: cannot read the live envelope ({e})") from None
        live_digest = feedlib.sha256_hex(manifest_text.encode("utf-8"))
        if files.current.sequence < live_sequence:
            raise FeedError(f"{feed}: sequence {files.current.sequence} is below the live {live_sequence}")
        if files.current.sequence == live_sequence and files.current.digest != live_digest:
            raise FeedError(f"{feed}: sequence {live_sequence} equals the live one with a different manifest")


def site_index(site: Path) -> dict[str, str]:
    """Every file the site serves, by path. Dot files (.nojekyll) are left out: the Pages artifact may not carry
    them, and a Pages site published from Actions does not run Jekyll either way."""
    index = {}
    for path in sorted(site.rglob("*")):
        rel = path.relative_to(site).as_posix()
        if path.is_file() and not any(part.startswith(".") for part in rel.split("/")):
            index[rel] = feedlib.sha256_hex(path.read_bytes())
    return index


def assemble(site: Path, carried_dir: Path | None, fuel_dir: Path | None, epochs: dict, snapshot_out: Path,
             run_id: int, base_url: str, fetch=fetch_live) -> dict:
    """Writes the feeds into the built site and the next snapshot; returns the site index."""
    feeds = {}
    if carried_dir is not None and (carried_dir / "feeds").is_dir():
        for feed_dir in sorted((carried_dir / "feeds").iterdir()):
            feeds[feed_dir.name] = load_feed(carried_dir, feed_dir.name, epochs.get(feed_dir.name))
    if fuel_dir is not None:
        feeds["fuel"] = adopt_fuel(fuel_dir, epochs.get("fuel"), feeds.get("fuel"))
    check_sequences(feeds, base_url, run_id, fetch)

    if snapshot_out.exists():
        shutil.rmtree(snapshot_out)
    snapshot_out.mkdir(parents=True)
    (site / "feeds").mkdir(parents=True, exist_ok=True)
    (site / "feeds" / "README.md").write_text(FEEDS_README)
    for files in feeds.values():
        write_feed(files, site, snapshot_out)
    index = site_index(site)
    record = {
        "format": SNAPSHOT_FORMAT,
        "run_id": run_id,
        "feeds": {feed: files.record() for feed, files in sorted(feeds.items())},
        "site": index,
        "site_digest": feedlib.sha256_hex(feedlib.canonical_json(index)),
    }
    (snapshot_out / "snapshot.json").write_bytes(feedlib.canonical_json(record))
    for feed, files in sorted(feeds.items()):
        feedlib.summary(f"{feed}: sequence {files.current.sequence}, manifest {files.current.digest[:16]}")
    feedlib.summary(f"site digest {record['site_digest']}")
    return index


# ---- verify after deploy --------------------------------------------------------------------------------------


def verify_live(index: dict, base_url: str, run_id: int, fetch=fetch_live, seconds=VERIFY_SECONDS,
                interval=VERIFY_INTERVAL, clock=time.monotonic, sleep=time.sleep) -> None:
    """Fetches every file until the live site serves each one's hash, or fails after [seconds]."""
    deadline = clock() + seconds
    pending = dict(index)
    while True:
        for path, digest in list(pending.items()):
            try:
                data = fetch(live_url(base_url, path, run_id))
            except FeedError:
                data = None
            if data is not None and feedlib.sha256_hex(data) == digest:
                del pending[path]
        if not pending:
            print(f"verify-live: all {len(index)} files served")
            return
        if clock() >= deadline:
            raise FeedError(f"the live site does not serve {len(pending)} file(s) after {seconds} s: "
                            + ", ".join(sorted(pending)[:10]))
        sleep(interval)


# ---- CLI ------------------------------------------------------------------------------------------------------


def epochs_from_env(pins: str) -> dict:
    return feedlib.trusted_epochs(Path(pins), os.environ.get("FEED_FUEL_PUBLIC_KEY", "").strip() or None)


def cmd_carry(args) -> None:
    gh = GitHub(os.environ.get("GH_TOKEN", ""))
    epochs = epochs_from_env(args.pins)
    base_url = pages_url(gh, args.repo)
    snapshot = None
    last = find_last_deployment(gh, args.repo, dt.datetime.now(dt.timezone.utc))
    if last is not None:
        where = f"run {last.run_id} attempt {last.attempt}"
        dest = Path(args.out).parent / "snapshot"
        if last.snapshot is None:
            notice(f"the last deploy ({where}) predates the feeds snapshot")
        elif fetch_snapshot(gh, args.repo, last.run_id, last.snapshot, dest):
            print(f"snapshot: {last.snapshot} of {where}")
            snapshot = dest
        else:
            notice(f"the last deploy ({where}) recorded {last.snapshot}, which is gone (expired after "
                   f"{RETENTION_DAYS} days, or deleted)")
    out = Path(args.out)
    carried = carry(snapshot, epochs, out, base_url, args.run_id)
    fuel_state = out / "fuel-state"
    if "fuel" in carried:
        write_feed(carried["fuel"], None, fuel_state)
    fuel_state.mkdir(parents=True, exist_ok=True)
    feedlib.github_output(base_url=base_url, fuel_state=feedlib.pack_dir(fuel_state))
    shutil.rmtree(fuel_state)


def cmd_assemble(args) -> None:
    fuel_dir = None
    bundle = os.environ.get("FUEL_BUNDLE", "").strip()
    if bundle:
        fuel_dir = Path(args.snapshot).parent / "fuel-generation"
        feedlib.unpack_dir(bundle, fuel_dir)
        stage_fuel(fuel_dir)
    carried = Path(args.carried) if args.carried else None
    index = assemble(Path(args.site), carried, fuel_dir, epochs_from_env(args.pins), Path(args.snapshot),
                     args.run_id, args.base_url or "")
    feedlib.github_output(index=feedlib.pack_index(index))


def cmd_verify_live(args) -> None:
    index = feedlib.unpack_index(os.environ.get("INDEX", ""))
    base = args.base_url if args.base_url.endswith("/") else args.base_url + "/"
    verify_live(index, base, args.run_id)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    c = sub.add_parser("carry")
    c.add_argument("--repo", required=True)
    c.add_argument("--out", required=True)
    a = sub.add_parser("assemble")
    a.add_argument("--site", required=True)
    a.add_argument("--carried")
    a.add_argument("--snapshot", required=True)
    a.add_argument("--base-url")
    v = sub.add_parser("verify-live")
    v.add_argument("--base-url", required=True)
    for p in (c, a, v):
        p.add_argument("--run-id", type=int, required=True)
        p.add_argument("--pins", default=feedlib.PIN_FILE)
    args = parser.parse_args(argv)
    try:
        {"carry": cmd_carry, "assemble": cmd_assemble, "verify-live": cmd_verify_live}[args.command](args)
        return 0
    except FeedError as e:
        print(f"::error::{e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
