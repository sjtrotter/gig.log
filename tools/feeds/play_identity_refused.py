#!/usr/bin/env python3
"""The rejected-identity check (#1564): passes only when Google refuses this job's GitHub identity for the Play login.

The Pages deploy job holds `id-token: write`, so it can ask GitHub for an identity token. The Play provider must admit
only the `release` environment on a `v*` tag and the `play-listing` environment on master (docs/RELEASE.md § 4); this
job runs in `github-pages`, so Google's token exchange must refuse it. Stdlib only.

Fail-safe: the identity token and anything Google returns live only in this process's memory. Nothing is written to a
file, an environment variable or a step output, and nothing of either is printed. When Google does issue a token,
the check fails without using it, and the process exits.

Environment: PLAY_WIF_PROVIDER and PLAY_SERVICE_ACCOUNT (repository variables; when both are unset, as in a fork,
there is no Play login to check and the step passes with a notice), ACTIONS_ID_TOKEN_REQUEST_URL and
ACTIONS_ID_TOKEN_REQUEST_TOKEN (set by the runner when the job has `id-token: write`).
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

STS_URL = "https://sts.googleapis.com/v1/token"
REFUSED_STATUSES = {400, 401, 403}


def provider_path(raw: str) -> str:
    """`projects/<n>/locations/global/workloadIdentityPools/<pool>/providers/<id>`, whatever prefix the variable has."""
    for prefix in ("https://iam.googleapis.com/", "//iam.googleapis.com/"):
        if raw.startswith(prefix):
            raw = raw[len(prefix):]
    return raw.strip("/")


def github_identity_token(audience: str) -> str:
    url = os.environ["ACTIONS_ID_TOKEN_REQUEST_URL"] + "&audience=" + urllib.parse.quote(audience, safe="")
    request = urllib.request.Request(
        url, headers={"Authorization": "bearer " + os.environ["ACTIONS_ID_TOKEN_REQUEST_TOKEN"]})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read())["value"]


def exchange(provider: str, subject_token: str, post=None) -> tuple[int, dict]:
    """Google's STS token exchange: (HTTP status, the response body as JSON)."""
    body = json.dumps({
        "audience": "//iam.googleapis.com/" + provider,
        "grantType": "urn:ietf:params:oauth:grant-type:token-exchange",
        "requestedTokenType": "urn:ietf:params:oauth:token-type:access_token",
        "scope": "https://www.googleapis.com/auth/cloud-platform",
        "subjectTokenType": "urn:ietf:params:oauth:token-type:jwt",
        "subjectToken": subject_token,
    }).encode()
    if post is not None:
        return post(body)
    request = urllib.request.Request(STS_URL, data=body, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.loads(response.read() or b"{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"{}")
        except json.JSONDecodeError:
            return e.code, {}


def verdict(status: int, response: dict) -> tuple[bool, str]:
    """(passed, message). Only an explicit refusal from Google passes; a token, an outage or anything else fails."""
    if status == 200 and "access_token" in response:
        return False, ("Google issued a federated token to the Pages deploy job: the Play provider admits it. "
                       "Narrow the provider's condition (docs/RELEASE.md § 4) before any deploy.")
    error = str(response.get("error", ""))
    if status in REFUSED_STATUSES and error:
        described = str(response.get("error_description", ""))[:200]
        return True, f"Google refused this job's identity for the Play login (HTTP {status}, {error}: {described})"
    return False, f"Google gave no clear refusal (HTTP {status}); the check cannot pass on an unknown answer"


def main() -> int:
    provider_raw = os.environ.get("PLAY_WIF_PROVIDER", "").strip()
    account = os.environ.get("PLAY_SERVICE_ACCOUNT", "").strip()
    if not provider_raw and not account:
        print("::notice::no Play login is configured here (PLAY_WIF_PROVIDER, PLAY_SERVICE_ACCOUNT); nothing to check")
        return 0
    if not provider_raw or not account:
        print("::error::only one of PLAY_WIF_PROVIDER and PLAY_SERVICE_ACCOUNT is set; see docs/RELEASE.md § 5")
        return 1
    if not os.environ.get("ACTIONS_ID_TOKEN_REQUEST_URL"):
        print("::error::this job cannot request an identity token, so the check proves nothing; it needs id-token: write")
        return 1
    provider = provider_path(provider_raw)
    try:
        status, response = exchange(provider, github_identity_token("https://iam.googleapis.com/" + provider))
    except (urllib.error.URLError, OSError, KeyError, json.JSONDecodeError) as e:
        print(f"::error::the identity check could not reach GitHub or Google ({type(e).__name__})")
        return 1
    passed, message = verdict(status, response)
    del response
    print(("::notice::" if passed else "::error::") + message)
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
