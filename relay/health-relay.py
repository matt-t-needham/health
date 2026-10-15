#!/usr/bin/env python3
"""Ring-data relay for health.bix.computer: the phone app's upload endpoint.

The phone owns the ring now. It uploads each history batch as gzip JSON to
POST https://health.bix.computer/relay/v1/batch; Cloudflare Access (the
health.bix.computer app, via a Service Auth policy for the phone's service
token) and health-proxy (nginx, one POST-only location, size
and rate limits) sit in front, and health-proxy forwards it here.

This daemon authenticates the phone, validates the batch strictly, writes it
to the spool (incoming/), fsyncs, and only then replies 204. It never opens
oura.db and executes nothing: health-ingest (Rust, oura-store) commits spool
files into the database. See ../CLAUDE.md "Relay".

Listens on 172.17.0.1:9102 (docker0). Requests must come from the health
compose network (HEALTH_RELAY_ALLOW, a CIDR), carry a valid Cloudflare Access
JWT for the health.bix.computer app (Cf-Access-Jwt-Assertion: RS256 from the team's
keys, aud = HEALTH_RELAY_ACCESS_AUD, iss = the team domain, unexpired), and
carry `Authorization: Bearer <device secret>`; only SHA-256 hashes of device
secrets are stored, one per line, in HEALTH_RELAY_CONF (written by
provision-phone.sh). The file is reread on every request, so provisioning or
revoking a phone needs no restart.

Replies: 204 spooled (also for an already-spooled batch_id), 401 bad secret,
403 wrong source address or no valid Access JWT, 404/405 wrong route, 411/413 length, 422
permanently invalid (the phone sets the batch aside and shows the body),
500 spool failure, 503 Access signing keys unreachable. Error bodies are one short plain-text reason. Every
refusal and failure is one ERROR line on stderr (journal).
"""

import gzip
import hashlib
import hmac
import ipaddress
import json
import os
import re
import sys
import threading
import time
import urllib.request
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import jwt  # PyJWT (distro python3-jwt, with python3-cryptography for RS256)

HOST = "172.17.0.1"
PORT = 9102
MAX_BODY = 2 * 1024 * 1024        # compressed; matches nginx client_max_body_size
MAX_JSON = 16 * 1024 * 1024       # decompressed cap (gzip bombs)
MAX_EVENTS = 4096
U32 = 2**32 - 1
# Feature names oura sync snapshots into feature_modes.json (open_health
# 08e1b86 cmd_sync).
FEATURES = {"daytime_hr", "exercise_hr", "spo2", "resting_hr", "real_steps", "experimental", "cva_ppg"}
TOP_REQUIRED = {"batch_id", "serial", "cursor_before", "cursor_after", "events"}
TOP_OPTIONAL = {"anchor", "device", "feature_modes"}
HEX_RE = re.compile(r"(?:[0-9a-f]{2})*")
HASH_RE = re.compile(r"[0-9a-f]{64}")
# The earliest plausible anchor wall-clock time (2020-01-01).
MIN_UNIX = 1577836800
TEAM_DOMAIN = "bixcomputer.cloudflareaccess.com"
KEYS_TTL = 3600       # refetch the team's signing keys hourly
KEYS_REFETCH_MIN = 60  # and on an unknown key id, at most once a minute


def log(msg: str) -> None:
    sys.stderr.write(f"[health-relay] {msg}\n")
    sys.stderr.flush()


def env(name: str) -> str:
    v = os.environ.get(name, "").strip()
    if not v:
        sys.exit(f"[health-relay] {name} not set")
    return v


class Invalid(Exception):
    """The batch can never be accepted (422)."""


class AccessDenied(Exception):
    """No valid Access JWT for this app (403)."""


class AccessUnavailable(Exception):
    """The team's signing keys couldn't be fetched (503, the phone retries)."""


def fetch_keys(team: str) -> dict:
    with urllib.request.urlopen(f"https://{team}/cdn-cgi/access/certs", timeout=10) as r:
        return json.load(r)


class AccessVerifier:
    """Checks Cf-Access-Jwt-Assertion as rss_feed's auth.access_verified does,
    but fetches the key set itself so an unreachable Cloudflare (503) is never
    confused with a bad token (403)."""

    def __init__(self, team: str, aud: str, fetch=fetch_keys):
        self.team, self.aud, self.fetch = team, aud, fetch
        self.keys: dict[str, jwt.PyJWK] = {}
        self.fetched = float("-inf")
        self.lock = threading.Lock()

    def _key(self, kid: str) -> jwt.PyJWK:
        with self.lock:
            age = time.monotonic() - self.fetched
            if age > KEYS_TTL or (kid not in self.keys and age > KEYS_REFETCH_MIN):
                try:
                    jwks = jwt.PyJWKSet.from_dict(self.fetch(self.team))
                except (OSError, ValueError, jwt.PyJWKSetError) as e:
                    if not self.keys or age > 2 * KEYS_TTL:
                        raise AccessUnavailable(f"fetching {self.team} signing keys: {e}")
                    log(f"ERROR refetching {self.team} signing keys failed, using cached ones: {e}")
                else:
                    self.keys = {k.key_id: k for k in jwks.keys if k.key_id}
                    self.fetched = time.monotonic()
            if kid not in self.keys:
                raise AccessDenied(f"unknown signing key id {kid[:40]!r}")
            return self.keys[kid]

    def check(self, token: str) -> None:
        if not token:
            raise AccessDenied("no Cf-Access-Jwt-Assertion")
        try:
            kid = jwt.get_unverified_header(token).get("kid", "")
        except jwt.PyJWTError as e:
            raise AccessDenied(f"malformed JWT: {e}")
        key = self._key(str(kid))
        try:
            jwt.decode(token, key.key, algorithms=["RS256"], audience=self.aud,
                       issuer=f"https://{self.team}",
                       options={"require": ["exp", "iat", "aud", "iss"]})
        except jwt.PyJWTError as e:
            raise AccessDenied(f"JWT rejected: {e}")


def is_u32(v) -> bool:
    return type(v) is int and 0 <= v <= U32


def load_hashes(conf: Path) -> list[str]:
    """Device-secret hashes: `<sha256 hex> <label>` lines, # comments."""
    hashes = []
    for n, line in enumerate(conf.read_text().splitlines(), 1):
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        h = line.split()[0]
        if not HASH_RE.fullmatch(h):
            raise ValueError(f"{conf}:{n}: not a sha256 hex hash")
        hashes.append(h)
    return hashes


def secret_ok(header: str, hashes: list[str]) -> bool:
    if not header.startswith("Bearer "):
        return False
    digest = hashlib.sha256(header[len("Bearer "):].strip().encode()).hexdigest()
    ok = False
    for h in hashes:  # no early exit: compare against every entry
        ok |= hmac.compare_digest(digest, h)
    return ok


def gunzip_capped(data: bytes, cap: int) -> bytes:
    """Decompress exactly one gzip member, refusing output over `cap`."""
    d = zlib.decompressobj(wbits=31)
    try:
        out = d.decompress(data, cap + 1)
    except zlib.error as e:
        raise Invalid(f"bad gzip: {e}")
    if len(out) > cap or d.unconsumed_tail:
        raise Invalid(f"decompressed body over {cap} bytes")
    if not d.eof:
        raise Invalid("truncated gzip")
    if d.unused_data:
        raise Invalid("trailing data after gzip member")
    return out


def _no_dupes(pairs):
    obj = {}
    for k, v in pairs:
        if k in obj:
            raise Invalid(f"duplicate key {k!r}")
        obj[k] = v
    return obj


def _no_constants(name):
    raise Invalid(f"non-finite number {name}")


def validate(raw: bytes, serials: set[str], now: float) -> dict:
    """Parse and check a batch; returns it in canonical form or raises Invalid."""
    try:
        b = json.loads(raw.decode("utf-8"), object_pairs_hook=_no_dupes, parse_constant=_no_constants)
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        raise Invalid(f"bad JSON: {e}")
    if not isinstance(b, dict):
        raise Invalid("body is not an object")
    keys = set(b)
    if TOP_REQUIRED - keys:
        raise Invalid(f"missing {sorted(TOP_REQUIRED - keys)}")
    if keys - TOP_REQUIRED - TOP_OPTIONAL:
        raise Invalid(f"unknown fields {sorted(keys - TOP_REQUIRED - TOP_OPTIONAL)}")

    serial = b["serial"]
    if not isinstance(serial, str) or serial not in serials:
        raise Invalid(f"serial {str(serial)[:40]!r} not allowed")
    before, after = b["cursor_before"], b["cursor_after"]
    if not (is_u32(before) and is_u32(after)) or before > after:
        raise Invalid(f"bad cursors {before!r}..{after!r}")
    if b["batch_id"] != f"{serial}:{before}:{after}":
        raise Invalid(f"batch_id {str(b['batch_id'])[:80]!r} isn't serial:cursor_before:cursor_after")

    events = b["events"]
    if not isinstance(events, list) or len(events) > MAX_EVENTS:
        raise Invalid(f"events must be a list of at most {MAX_EVENTS}")
    for i, ev in enumerate(events):
        if not (isinstance(ev, list) and len(ev) == 3):
            raise Invalid(f"event {i} is not [tag, ring_timestamp, body_hex]")
        tag, ts, body = ev
        if not (type(tag) is int and 0 <= tag <= 255):
            raise Invalid(f"event {i}: tag {tag!r} out of range")
        if not is_u32(ts):
            raise Invalid(f"event {i}: ring_timestamp {ts!r} out of range")
        if not (isinstance(body, str) and HEX_RE.fullmatch(body)):
            raise Invalid(f"event {i}: body is not lowercase hex")

    out = {"batch_id": b["batch_id"], "serial": serial, "cursor_before": before,
           "cursor_after": after, "events": events}

    if "anchor" in b:
        a = b["anchor"]
        if not (isinstance(a, dict) and set(a) == {"ring_timestamp", "unix_time"}):
            raise Invalid("anchor must be {ring_timestamp, unix_time}")
        if not (is_u32(a["ring_timestamp"]) and a["ring_timestamp"] > 0):
            raise Invalid(f"anchor.ring_timestamp {a['ring_timestamp']!r} out of range")
        if not (is_u32(a["unix_time"]) and MIN_UNIX <= a["unix_time"] <= now + 86400):
            raise Invalid(f"anchor.unix_time {a['unix_time']!r} implausible")
        out["anchor"] = a

    if "device" in b:
        d = b["device"]
        if not (isinstance(d, dict) and d and set(d) <= {"firmware", "battery"}):
            raise Invalid("device must hold firmware and/or battery")
        fw = d.get("firmware")
        if fw is not None and not (isinstance(fw, str) and re.fullmatch(r"[0-9A-Za-z._+-]{1,32}", fw)):
            raise Invalid(f"device.firmware {str(fw)[:40]!r} invalid")
        bat = d.get("battery")
        if bat is not None and not (type(bat) is int and 0 <= bat <= 100):
            raise Invalid(f"device.battery {bat!r} out of range")
        out["device"] = d

    if "feature_modes" in b:
        fm = b["feature_modes"]
        if not (isinstance(fm, dict) and fm and set(fm) <= FEATURES):
            raise Invalid(f"feature_modes keys must be among {sorted(FEATURES)}")
        if not all(type(v) is int and 0 <= v <= 255 for v in fm.values()):
            raise Invalid("feature_modes values must be 0-255")
        out["feature_modes"] = fm

    return out


def spool_name(batch: dict) -> str:
    # All three parts were checked: serial is allowlisted, cursors are ints.
    return f"{batch['serial']}_{batch['cursor_before']:010d}_{batch['cursor_after']:010d}.json"


def _fsync_dir(d: Path) -> None:
    fd = os.open(d, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


class Spool:
    def __init__(self, root: Path):
        self.root = root
        self.tmp, self.incoming = root / "tmp", root / "incoming"
        self.seen_dirs = [self.incoming, root / "done", root / "failed"]
        for d in [self.tmp, *self.seen_dirs]:
            if not d.is_dir():
                sys.exit(f"[health-relay] spool dir {d} missing (run install-host.sh)")
        self.lock = threading.Lock()

    def put(self, batch: dict) -> bool:
        """Durably spool a batch; False if this batch_id is already spooled."""
        name = spool_name(batch)
        with self.lock:
            if any((d / name).exists() for d in self.seen_dirs):
                return False
            tmp = self.tmp / f".{name}.{os.getpid()}"
            with open(tmp, "wb") as f:
                f.write(json.dumps(batch, separators=(",", ":")).encode())
                f.flush()
                os.fsync(f.fileno())
            # tmp/ and incoming/ are on one filesystem, so this is atomic, and
            # the ingest path unit only ever sees whole files.
            os.rename(tmp, self.incoming / name)
            _fsync_dir(self.incoming)
            _fsync_dir(self.tmp)
            return True


class Handler(BaseHTTPRequestHandler):
    # Set in main().
    allow: ipaddress.IPv4Network
    access: AccessVerifier
    conf: Path
    serials: set[str]
    spool: Spool
    timeout = 30

    def log_message(self, fmt, *args):
        log(f"{self.address_string()} - {fmt % args}")

    def _reply(self, code: int, error: str = "") -> None:
        # Errors are one short plain-text line; the phone shows the first 200
        # characters of a 422's body beside the batch it set aside.
        body = error.encode()
        self.send_response(code)
        if body:
            self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _refuse(self, code: int, error: str, detail: str = "") -> None:
        log(f"ERROR {code} {error}{': ' + detail if detail else ''} (from {self.client_address[0]})")
        self._reply(code, error)

    def do_GET(self):
        self._refuse(405, "method not allowed")

    def do_POST(self):
        try:
            src_ok = ipaddress.ip_address(self.client_address[0]) in self.allow
        except ValueError:
            src_ok = False
        if not src_ok:
            return self._refuse(403, "forbidden", "source address outside HEALTH_RELAY_ALLOW")
        if self.path != "/v1/batch":
            return self._refuse(404, "not found", self.path[:80])
        try:
            self.access.check(self.headers.get("Cf-Access-Jwt-Assertion", ""))
        except AccessDenied as e:
            return self._refuse(403, "forbidden", str(e))
        except AccessUnavailable as e:
            return self._refuse(503, "try later", str(e))
        try:
            hashes = load_hashes(self.conf)
        except (OSError, ValueError) as e:
            return self._refuse(500, "relay misconfigured", str(e))
        if not secret_ok(self.headers.get("Authorization", ""), hashes):
            return self._refuse(401, "bad device secret")

        length = self.headers.get("Content-Length")
        if length is None or not length.isdigit():
            return self._refuse(411, "length required")
        if int(length) > MAX_BODY:
            return self._refuse(413, "too large", f"{length} bytes")
        data = self.rfile.read(int(length))
        if len(data) != int(length):
            return self._refuse(400, "short body")

        try:
            if self.headers.get("Content-Encoding", "").strip().lower() != "gzip":
                raise Invalid("Content-Encoding must be gzip")
            if self.headers.get("Content-Type", "").split(";")[0].strip().lower() != "application/json":
                raise Invalid("Content-Type must be application/json")
            batch = validate(gunzip_capped(data, MAX_JSON), self.serials, time.time())
        except Invalid as e:
            return self._refuse(422, f"invalid batch: {str(e)[:180]}")

        try:
            new = self.spool.put(batch)
        except OSError as e:
            return self._refuse(500, "spool write failed", f"{batch['batch_id']}: {e}")
        log(f"{'spooled' if new else 'already spooled'} {batch['batch_id']} ({len(batch['events'])} events)")
        self._reply(204)


def main() -> None:
    Handler.allow = ipaddress.ip_network(env("HEALTH_RELAY_ALLOW"))
    Handler.access = AccessVerifier(os.environ.get("HEALTH_RELAY_ACCESS_TEAM", TEAM_DOMAIN),
                                    env("HEALTH_RELAY_ACCESS_AUD"))
    Handler.conf = Path(env("HEALTH_RELAY_CONF"))
    Handler.serials = set(env("HEALTH_SERIALS").split(","))
    Handler.spool = Spool(Path(env("HEALTH_SPOOL")))
    load_hashes(Handler.conf)  # fail at start, not on the first upload
    server = ThreadingHTTPServer((os.environ.get("HEALTH_RELAY_HOST", HOST), PORT), Handler)
    log(f"listening on {server.server_address[0]}:{PORT}, allowing {Handler.allow}")
    server.serve_forever()


if __name__ == "__main__":
    main()
