#!/usr/bin/env bash
# feed-keygen.sh: makes the P-256 key pair a self-hosted fuel feed signs with (#1564, docs/feeds/SELF_HOSTING.md).
#
# Writes the PRIVATE key to <private-key.pem> (mode 600), which must be outside this repository, and prints:
#   - the public key (base64 DER SubjectPublicKeyInfo): the repository variable FEED_FUEL_PUBLIC_KEY, and the key
#     you paste into the app;
#   - its key id (the first 16 hex characters of sha256 over the DER public key);
#   - the source id the app gives that key, custom:<key id>.
# The private key's contents go into the secret FEED_FUEL_SIGNING_KEY of environment feed-fuel. Never commit it.
#
# Usage: tools/feeds/feed-keygen.sh <private-key.pem>
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <private-key.pem>   (a path outside the repository)" >&2
  exit 2
fi

out="$1"
dir="$(cd "$(dirname "$out")" 2>/dev/null && pwd -P)" || { echo "error: no such directory: $(dirname "$out")" >&2; exit 2; }
target="$dir/$(basename "$out")"

if repo="$(git -C "$dir" rev-parse --show-toplevel 2>/dev/null)"; then
  echo "error: $target is inside the git repository $repo; write the private key outside any repository" >&2
  exit 2
fi
if [[ -L "$target" ]]; then
  echo "error: $target is a symbolic link; give the key's real path" >&2
  exit 2
fi
[[ -e "$target" ]] && { echo "error: $target already exists; refusing to replace a key" >&2; exit 2; }

# The key is made in a new mode-600 file beside the target, then hard-linked into place: link(2) never follows a
# symbolic link at the target and fails when anything (a file, a dangling link) already has that name.
umask 077
tmp="$(mktemp "$dir/.feed-keygen.XXXXXX")"
trap 'rm -f "$tmp"' EXIT
openssl ecparam -name prime256v1 -genkey -noout -out "$tmp"
if ! ln -T "$tmp" "$target" 2>/dev/null; then
  echo "error: could not create $target (did something appear at that path?)" >&2
  exit 2
fi
rm -f "$tmp"

pub="$(openssl ec -in "$target" -pubout -outform DER 2>/dev/null | base64 -w0)"
kid="$(printf '%s' "$pub" | base64 -d | sha256sum | cut -c1-16)"

echo "private key (secret FEED_FUEL_SIGNING_KEY, keep it offline too): $target"
echo "public key (variable FEED_FUEL_PUBLIC_KEY, and the key you paste into the app): $pub"
echo "key id: $kid"
echo "source id: custom:$kid"
