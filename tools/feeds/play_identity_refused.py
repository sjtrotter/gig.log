#!/usr/bin/env python3
"""The rejected-identity check (#1564): passes only when Google refuses this job's GitHub identity for the Play login.

The Pages deploy job holds `id-token: write`, so it can ask GitHub for an identity token. The Play provider must admit
only the `release` environment on a `v*` tag and the `play-listing` environment on master (docs/RELEASE.md § 4); this
job runs in `github-pages`, so Google's token exchange must refuse it. Stdlib only.

Fail-safe: the identity token and anything Google returns live only in this process's memory. Nothing is written to a
file, an environment variable or a step output, and nothing of either is printed. When Google does issue a token,
the check fails without using it, and the process exits.

The one answer that passes is the refusal Google's STS gives when a provider's attribute condition rejects the
token: HTTP 400 with

    {"error": "unauthorized_client", "error_description": "The given credential is rejected by the attribute condition."}

Any other error (`invalid_request`, `invalid_target`, `invalid_grant`, ...) can come from a wrong provider name or
audience, which proves nothing about the condition, so it fails, as does any answer carrying a token.

Environment: PLAY_WIF_PROVIDER and PLAY_SERVICE_ACCOUNT (repository variables), GITHUB_REPOSITORY and GH_TOKEN (to
ask GitHub whether this repository is a fork), ACTIONS_ID_TOKEN_REQUEST_URL and ACTIONS_ID_TOKEN_REQUEST_TOKEN (set
by the runner when the job has `id-token: write`). With both Play variables unset the step passes only in a fork,
which has no Play login; in any other repository it fails.
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

STS_URL = "https://sts.googleapis.com/v1/token"
REFUSED_STATUS = 400
REFUSED_ERROR = "unauthorized_client"
REFUSED_DESCRIPTION = "rejected by the attribute condition"
TOKEN_KEYS = {"access_token", "id_token", "token"}


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
    """(passed, message). Only the attribute-condition refusal passes; a token, an outage or any other answer fails."""
    if TOKEN_KEYS & set(response):
        return False, ("Google issued a token to the Pages deploy job: the Play provider admits it. "
                       "Narrow the provider's condition (docs/RELEASE.md § 4) before any deploy.")
    error = str(response.get("error", ""))
    described = str(response.get("error_description", ""))[:200]
    if status == REFUSED_STATUS and error == REFUSED_ERROR and REFUSED_DESCRIPTION in described:
        return True, f"Google refused this job's identity for the Play login (HTTP {status}, {error}: {described})"
    return False, (f"Google's answer (HTTP {status}, {error or 'no error code'}) is not the attribute-condition "
                   "refusal, so it proves nothing; check PLAY_WIF_PROVIDER")


def is_fork(repo: str, token: str) -> bool:
    """GitHub's `fork` flag for [repo]. Any failure raises: a check that cannot tell must not skip."""
    request = urllib.request.Request(f"https://api.github.com/repos/{repo}", headers={
        "Accept": "application/vnd.github+json", "Authorization": f"Bearer {token}", "User-Agent": "feeds-pages"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read())["fork"] is True


def main() -> int:
    provider_raw = os.environ.get("PLAY_WIF_PROVIDER", "").strip()
    account = os.environ.get("PLAY_SERVICE_ACCOUNT", "").strip()
    if not provider_raw and not account:
        try:
            fork = is_fork(os.environ.get("GITHUB_REPOSITORY", ""), os.environ.get("GH_TOKEN", ""))
        except (urllib.error.URLError, OSError, KeyError, json.JSONDecodeError) as e:
            print(f"::error::PLAY_WIF_PROVIDER and PLAY_SERVICE_ACCOUNT are unset and GitHub could not say whether "
                  f"this repository is a fork ({type(e).__name__})")
            return 1
        if not fork:
            print("::error::PLAY_WIF_PROVIDER and PLAY_SERVICE_ACCOUNT are unset; this repository is not a fork, so "
                  "its Play login must be configured and checked (docs/RELEASE.md § 5)")
            return 1
        print("::notice::a fork has no Play login (PLAY_WIF_PROVIDER, PLAY_SERVICE_ACCOUNT unset); nothing to check")
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
