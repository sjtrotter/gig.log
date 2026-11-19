# Running your own fuel feed

A fuel feed is one signed file of EIA weekly retail prices, published on GitHub Pages and refreshed by GitHub Actions.
gig.log runs one; anyone can run their own from a fork of this repository and point the app at it. **A fuel feed needs
its own EIA API key, free at https://www.eia.gov/opendata/register.php.** The format is in `docs/feeds/FORMAT.md`.

You need a GitHub account, a computer with `openssl`, `git` and `bash`, and about 15 minutes.

## Steps

1. **Fork** this repository on GitHub.
2. **Pages source.** In the fork: Settings > Pages > Build and deployment > Source: **GitHub Actions**.
3. **EIA key.** Register at https://www.eia.gov/opendata/register.php. EIA emails the key.
4. **Signing key.** On your computer, in a clone of the fork, run:

   ```sh
   tools/feeds/feed-keygen.sh ~/fuel-feed-key.pem
   ```

   The path must be outside any git repository; the script refuses otherwise and never replaces an existing file.
   It writes the private key (mode 600) and prints:
   - the public key: the variable of step 6, and the key you paste into the app;
   - the key id;
   - the source id, `custom:<key id>`. Keep it: the app shows it for your feed.

   Keep a copy of the private key somewhere safe and offline. If you lose it, make a new pair and paste the new
   public key into the app; the app treats that as a new source.
5. **Environment.** Settings > Environments > New environment, named exactly `feed-fuel`.
   - Deployment branches and tags: **Selected branches and tags**, add `master`.
   - Add two environment secrets:
     - `EIA_API_KEY`: the key from step 3;
     - `FEED_FUEL_SIGNING_KEY`: the whole contents of the private key file from step 4, including the
       `-----BEGIN` and `-----END` lines.
6. **Public key variable.** Settings > Secrets and variables > Actions > Variables > New repository variable:
   `FEED_FUEL_PUBLIC_KEY`, set to the public key from step 4. The workflow checks every published and carried-over
   fuel file against it.
7. **Enable Actions and the schedule.** Forks start with both off. Open the Actions tab and enable workflows, then
   open the **Pages** workflow and enable it if GitHub shows it as disabled.
8. **Run it once.** Actions > Pages > Run workflow, on `master`, with `bootstrap` left off. When it finishes, the
   deploy job links the site. Open `<site>/feeds/fuel/envelope.json`: it names `custom:<your key id>` as its source.
   The first run has no earlier site to carry over, so it publishes only the fuel feed (and the privacy pages).
9. **Paste into the app.** In the app's custom fuel source setting (#1567), enter the feed URL
   `<site>/feeds/fuel/` and the public key from step 4.

From then on the workflow checks EIA hourly on Tuesday and Wednesday afternoon (UTC) and once a day, and signs a new
file only when EIA's numbers changed.

## Messages from the fuel job

- `fuel feed not set up: see docs/feeds/SELF_HOSTING.md`: there is no signing key and no pinned key. The job ends
  successfully, so the hourly schedule does not fail before you finish the steps.
- Any half-setup fails the run and names both secrets: a signing key with no `FEED_FUEL_PUBLIC_KEY`, a
  `FEED_FUEL_PUBLIC_KEY` with no signing key, a signing key that does not match it, or a missing `EIA_API_KEY`.
- `EIA refused the request (HTTP 403)`: the EIA key is wrong or revoked.

## Keeping it running

- **GitHub disables scheduled workflows in a public repository after 60 days without activity.** An idle fork's feed
  then stops, and about 21 days after its last file the app labels its prices as old. Re-enable the workflow on the
  Actions tab, or push any commit, to restart it.
- **A lapse over 90 days also expires the snapshot.** Each deploy keeps a copy of the site as the artifact
  `feeds-site` for 90 days, the most GitHub allows on a public repository; the next deploy carries the feeds over from
  it. After a longer lapse the next run starts from an empty site and publishes only the fuel feed, which is all a
  fork has. Phones keep their last good file until then.
- The rejected-identity check in the deploy job passes with a notice in a fork: a fork has no Play login to check.

## Recovery

A run refuses to deploy when the live site does not serve the files its snapshot recorded (for example, the site was
deployed from somewhere else). Find out why first. To start over from an empty site, run the Pages workflow by hand
with **bootstrap** on: it ignores the snapshot and publishes only the fuel feed, with a sequence above the live one.
In gig.log's own repository a bootstrap also drops the rules and constants feeds until their tags run again (#641,
#695).

## How it is built

- `.github/workflows/pages.yml` is the only deploy workflow, since a Pages deploy replaces the whole site. Its jobs:
  - `prepare` finds the snapshot of the last run of this workflow that deployed, by run id, checks the live site
    against it and re-verifies every file it carries;
  - `fuel` signs, in environment `feed-fuel`, with only the pinned checkout, the Python standard library and
    `openssl`;
  - `build` assembles the site and uploads the next snapshot;
  - `deploy` publishes and then fetches every file back for up to 12 minutes.
- `tools/feeds/publish_fuel.py` can also run by hand: `EIA_API_KEY=... tools/feeds/publish_fuel.py publish --out DIR
  --key-file KEY.pem`.
- gig.log's own fuel key is pinned in `feed-contract/pins/feed-pins.json` (a primary and a backup, changed only by an
  app release), so its repository does not set `FEED_FUEL_PUBLIC_KEY`.
- If your fork also publishes tagged feeds, set the `github-pages` environment's deployment policy to admit those
  tag patterns as well as `master`.
