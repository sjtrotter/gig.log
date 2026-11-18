"""The signed feed format, in Python, for the workflows (#1564). Standard library plus `openssl` only.

The Kotlin owner of the format is `feed-contract/` (its README and `VerifiedManifest`); docs/feeds/FORMAT.md states it
for operators. This module makes the same bytes and checks them the same way, so a file the workflow publishes is a
file the app accepts. The cross-language vector (feed-contract/src/test/resources/vectors/fuel/) pins that.

- Signature: ECDSA P-256 with SHA-256, base64 DER, over the exact UTF-8 bytes of the manifest string.
- Key id: the first 16 hex characters of sha256 over the DER SubjectPublicKeyInfo.
- Envelope: {"manifest": "<manifest JSON as one string>", "key": "<key id>", "sig": "<base64 DER>"}.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path

FORMAT_VERSION = 1
GIG_LOG = "gig.log"
CUSTOM_PREFIX = "custom:"
KEY_ID_HEX_CHARS = 16

# The feeds of the channel (feed-contract Feed.kt) and the one payload name of each; rules names are per platform.
FEEDS = ("fuel", "constants", "rules")
FILE_NAMES = {"fuel": "prices", "constants": "values"}
MAX_ENVELOPE_BYTES = 32 * 1024
MAX_FILE_BYTES = {"fuel": 64 * 1024, "constants": 16 * 1024, "rules": 1024 * 1024}
MIN_MS = 10**12

# The DER prefix of every P-256 SubjectPublicKeyInfo (id-ecPublicKey, prime256v1, a 65-byte uncompressed point).
P256_SPKI_PREFIX = bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d030107034200")
P256_SPKI_LENGTH = len(P256_SPKI_PREFIX) + 65

SHA256_HEX = re.compile(r"[0-9a-f]{64}")
FILE_NAME = re.compile(r"[a-z0-9_]{1,64}")
PIN_FILE = "feed-contract/pins/feed-pins.json"


class FeedError(Exception):
    """A refusal. Its message is what the run log prints."""


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def key_id(der: bytes) -> str:
    return sha256_hex(der)[:KEY_ID_HEX_CHARS]


def custom_source(kid: str) -> str:
    return CUSTOM_PREFIX + kid


def canonical_json(value) -> bytes:
    """Compact, sorted keys, ASCII: one byte form for one value."""
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True, allow_nan=False).encode("ascii")


def _no_duplicates(pairs):
    out = {}
    for key, value in pairs:
        if key in out:
            raise FeedError(f"duplicate key {key!r}")
        out[key] = value
    return out


def _no_constant(name):
    raise FeedError(f"not a finite number: {name}")


def strict_loads(data: bytes | str):
    """Strict JSON: duplicate keys and NaN or Infinity are refused, as the Kotlin pre-pass does."""
    if isinstance(data, bytes):
        try:
            data = data.decode("utf-8")
        except UnicodeDecodeError as e:
            raise FeedError("not UTF-8") from e
    try:
        return json.loads(data, object_pairs_hook=_no_duplicates, parse_constant=_no_constant)
    except json.JSONDecodeError as e:
        raise FeedError(f"malformed JSON: {e.msg}") from e


def public_der_from_b64(b64: str) -> bytes:
    """A pinned or pasted public key: base64 DER SubjectPublicKeyInfo on P-256, else refused."""
    try:
        der = base64.b64decode(b64.strip(), validate=True)
    except ValueError as e:
        raise FeedError("a public key is not base64") from e
    if len(der) != P256_SPKI_LENGTH or not der.startswith(P256_SPKI_PREFIX) or der[len(P256_SPKI_PREFIX)] != 0x04:
        raise FeedError("a public key is not a P-256 SubjectPublicKeyInfo")
    return der


def _pem(der: bytes) -> str:
    body = base64.b64encode(der).decode("ascii")
    lines = [body[i:i + 64] for i in range(0, len(body), 64)]
    return "-----BEGIN PUBLIC KEY-----\n" + "\n".join(lines) + "\n-----END PUBLIC KEY-----\n"


def _openssl(args: list[str], stdin: bytes | None = None) -> bytes:
    try:
        done = subprocess.run(["openssl", *args], input=stdin, capture_output=True, check=False)
    except FileNotFoundError as e:
        raise FeedError("openssl is not installed") from e
    if done.returncode != 0:
        raise FeedError(f"openssl {args[0]} failed: {done.stderr.decode(errors='replace').strip()[:200]}")
    return done.stdout


def public_der_of_private_key(pem_path: Path) -> bytes:
    """The DER public key of a private key file; refused unless it is a P-256 key."""
    der = _openssl(["pkey", "-in", str(pem_path), "-pubout", "-outform", "DER"])
    if len(der) != P256_SPKI_LENGTH or not der.startswith(P256_SPKI_PREFIX):
        raise FeedError("the signing key is not a P-256 key")
    return der


def sign(pem_path: Path, data: bytes) -> str:
    """Base64 DER ECDSA P-256 / SHA-256 signature, the scheme of matchers/tools/sign-ruleset.sh."""
    return base64.b64encode(_openssl(["dgst", "-sha256", "-sign", str(pem_path)], stdin=data)).decode("ascii")


def verifies(public_der: bytes, data: bytes, sig_b64: str) -> bool:
    try:
        sig = base64.b64decode(sig_b64, validate=True)
    except ValueError:
        return False
    with tempfile.TemporaryDirectory() as tmp:
        pub, sig_path = Path(tmp) / "pub.pem", Path(tmp) / "sig.der"
        pub.write_text(_pem(public_der))
        sig_path.write_bytes(sig)
        done = subprocess.run(
            ["openssl", "dgst", "-sha256", "-verify", str(pub), "-signature", str(sig_path)],
            input=data, capture_output=True, check=False,
        )
    return done.returncode == 0


class Epoch:
    """One pinned epoch of a (source, feed): a primary key and an optional backup, by key id."""

    def __init__(self, source: str, keys: list[bytes]):
        self.source = source
        self.keys = {key_id(der): der for der in keys}

    def __contains__(self, kid: str) -> bool:
        return kid in self.keys


def read_pin_file(path: Path) -> dict[str, Epoch]:
    """gig.log's pins by feed wire name. A missing or malformed pin file is refused, never read as empty."""
    try:
        root = strict_loads(path.read_bytes())
    except OSError as e:
        raise FeedError(f"cannot read the pin file {path}") from e
    if not isinstance(root, dict) or root.get("format_version") != 1 or not isinstance(root.get("feeds"), dict):
        raise FeedError(f"malformed pin file {path}")
    out = {}
    for feed, entry in root["feeds"].items():
        if feed not in FEEDS or not isinstance(entry, dict):
            raise FeedError(f"malformed pin file entry {feed!r}")
        primary, backup = entry.get("primary"), entry.get("backup")
        if not isinstance(primary, str) or not isinstance(backup, str):
            raise FeedError(f"pin file entry {feed!r} needs a primary and a backup key")
        out[feed] = Epoch(GIG_LOG, [public_der_from_b64(primary), public_der_from_b64(backup)])
    return out


def trusted_epochs(pin_file: Path, custom_fuel_key_b64: str | None) -> dict[str, Epoch]:
    """The epoch each feed of this site is verified against.

    gig.log's feeds use the pin file. A self-hosted fuel feed sets the repository variable FEED_FUEL_PUBLIC_KEY to its
    own public key; its source is then `custom:<key id>`, the id the app gives that pasted key.
    """
    epochs = read_pin_file(pin_file)
    if custom_fuel_key_b64:
        der = public_der_from_b64(custom_fuel_key_b64)
        epochs["fuel"] = Epoch(custom_source(key_id(der)), [der])
    return epochs


def build_manifest(source: str, feed: str, issued_at: int, contract: int, files: list[tuple[str, bytes]]) -> str:
    """The manifest text, in the field order of FORMAT.md. `sequence` equals `issued_at`."""
    if issued_at < MIN_MS:
        raise FeedError("issued_at must be Unix milliseconds")
    entries = ",".join(
        f'{{"name":{json.dumps(name)},"sha256":"{sha256_hex(data)}","size":{len(data)}}}' for name, data in files
    )
    return (
        f'{{"format_version":{FORMAT_VERSION},"source":{json.dumps(source)},"feed":{json.dumps(feed)},'
        f'"sequence":{issued_at},"issued_at":{issued_at},"contract":{contract},"files":[{entries}]}}'
    )


def build_envelope(manifest: str, kid: str, sig_b64: str) -> bytes:
    return (
        '{"manifest":' + json.dumps(manifest, ensure_ascii=True) + ',"key":' + json.dumps(kid)
        + ',"sig":' + json.dumps(sig_b64) + "}"
    ).encode("ascii")


class Verified:
    """An envelope whose signature verified against the pinned epoch and whose manifest names the expected feed."""

    def __init__(self, source, feed, kid, digest, sequence, contract, files, envelope_bytes):
        self.source = source
        self.feed = feed
        self.kid = kid
        self.digest = digest
        self.sequence = sequence
        self.contract = contract
        self.files = files  # [(name, sha256, size)]
        self.envelope_bytes = envelope_bytes

    @property
    def envelope_sha256(self) -> str:
        return sha256_hex(self.envelope_bytes)


def _integer(manifest: dict, name: str) -> int:
    value = manifest.get(name)
    if type(value) is not int:
        raise FeedError(f"manifest {name} is not an integer")
    return value


def verify_envelope(envelope_bytes: bytes, feed: str, epoch: Epoch) -> Verified:
    """The checks the app makes before it trusts a manifest (feed-contract VerifiedManifest.verify)."""
    if len(envelope_bytes) > MAX_ENVELOPE_BYTES:
        raise FeedError(f"{feed}: envelope over {MAX_ENVELOPE_BYTES} bytes")
    root = strict_loads(envelope_bytes)
    if not isinstance(root, dict):
        raise FeedError(f"{feed}: envelope is not an object")
    manifest_text, kid, sig = root.get("manifest"), root.get("key"), root.get("sig")
    if not all(isinstance(v, str) for v in (manifest_text, kid, sig)):
        raise FeedError(f"{feed}: envelope needs manifest, key and sig strings")
    if kid not in epoch:
        raise FeedError(f"{feed}: signed by key {kid}, which the current pins do not name")
    manifest_bytes = manifest_text.encode("utf-8")
    if not verifies(epoch.keys[kid], manifest_bytes, sig):
        raise FeedError(f"{feed}: bad signature")
    manifest = strict_loads(manifest_text)
    if not isinstance(manifest, dict) or manifest.get("format_version") != FORMAT_VERSION:
        raise FeedError(f"{feed}: unsupported format_version")
    if manifest.get("source") != epoch.source:
        raise FeedError(f"{feed}: source {manifest.get('source')!r} is not {epoch.source!r}")
    if manifest.get("feed") != feed:
        raise FeedError(f"{feed}: manifest names feed {manifest.get('feed')!r}")
    sequence, issued_at = _integer(manifest, "sequence"), _integer(manifest, "issued_at")
    contract = _integer(manifest, "contract")
    if sequence != issued_at or issued_at < MIN_MS:
        raise FeedError(f"{feed}: sequence must equal issued_at, in milliseconds")
    entries = manifest.get("files")
    if not isinstance(entries, list):
        raise FeedError(f"{feed}: manifest files is not a list")
    files = []
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {"name", "sha256", "size"}:
            raise FeedError(f"{feed}: malformed file entry")
        name, digest, size = entry["name"], entry["sha256"], entry["size"]
        if not (isinstance(name, str) and FILE_NAME.fullmatch(name)):
            raise FeedError(f"{feed}: malformed file name")
        if not (isinstance(digest, str) and SHA256_HEX.fullmatch(digest)):
            raise FeedError(f"{feed}: malformed file sha256")
        if type(size) is not int or not 0 <= size <= MAX_FILE_BYTES[feed]:
            raise FeedError(f"{feed}: file size out of range")
        files.append((name, digest, size))
    return Verified(epoch.source, feed, kid, sha256_hex(manifest_bytes), sequence, contract, files, envelope_bytes)


def verify_payload(verified: Verified, digest: str, data: bytes) -> None:
    """A payload must be one the verified manifest names, with its signed size and sha256."""
    entry = next((f for f in verified.files if f[1] == digest), None)
    if entry is None:
        raise FeedError(f"{verified.feed}: payload {digest[:12]} is not in the signed manifest")
    if len(data) != entry[2] or sha256_hex(data) != digest:
        raise FeedError(f"{verified.feed}: payload {digest[:12]} does not match the signed size and sha256")


def write_private(path: Path, data: bytes) -> None:
    """Writes a file only its owner can read (mode 600), refusing to replace one."""
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as out:
        out.write(data)


def pack_dir(root: Path) -> str:
    """A directory as one base64 line, for a job output: {"files": {"<relative path>": "<base64 bytes>"}}."""
    files = {
        p.relative_to(root).as_posix(): base64.b64encode(p.read_bytes()).decode("ascii")
        for p in sorted(root.rglob("*")) if p.is_file()
    }
    return base64.b64encode(canonical_json({"files": files})).decode("ascii")


SAFE_PATH = re.compile(r"[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*")


def safe_relative(path: str) -> str:
    """A relative path with no `..`, no absolute root and no odd characters, else refused."""
    if not SAFE_PATH.fullmatch(path) or any(part in (".", "..") for part in path.split("/")):
        raise FeedError(f"unsafe path {path!r}")
    return path


def unpack_dir(packed: str, root: Path) -> None:
    """The inverse of [pack_dir]. An empty string unpacks to an empty directory."""
    root.mkdir(parents=True, exist_ok=True)
    if not packed.strip():
        return
    try:
        files = strict_loads(base64.b64decode(packed.strip(), validate=True))["files"]
    except (ValueError, KeyError, TypeError) as e:
        raise FeedError("malformed packed directory") from e
    for rel, data in files.items():
        target = root / safe_relative(rel)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(base64.b64decode(data, validate=True))


def github_output(**values: str) -> None:
    """Writes step outputs when the run is in Actions; prints them otherwise."""
    path = os.environ.get("GITHUB_OUTPUT")
    lines = "".join(f"{k}={v}\n" for k, v in values.items())
    if path:
        with open(path, "a", encoding="utf-8") as out:
            out.write(lines)
    else:
        print(lines, end="")


def summary(line: str) -> None:
    """One line in the run summary when the run is in Actions."""
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as out:
            out.write(line + "\n")


def pack_index(index: dict[str, str]) -> str:
    """A site index ({path: sha256}) as one base64 line, for a job output."""
    return base64.b64encode(canonical_json(index)).decode("ascii")


def unpack_index(packed: str) -> dict[str, str]:
    try:
        index = strict_loads(base64.b64decode(packed.strip(), validate=True))
    except ValueError as e:
        raise FeedError("malformed site index") from e
    if not isinstance(index, dict) or not index:
        raise FeedError("empty or malformed site index")
    for path, digest in index.items():
        safe_relative(path)
        if not isinstance(digest, str) or not SHA256_HEX.fullmatch(digest):
            raise FeedError(f"malformed site index entry {path!r}")
    return index
