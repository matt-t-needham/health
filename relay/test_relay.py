"""Tests for health-relay.py: `cd health/relay && python3 -m pytest -q`."""

import gzip
import hashlib
import importlib.util
import json
import threading
import time
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa

spec = importlib.util.spec_from_file_location("relay", Path(__file__).with_name("health-relay.py"))
relay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(relay)

SERIAL = "2016082503005273"
SERIALS = {SERIAL}
SECRET = "s3cret-device-token"
TEAM = "bixcomputer.cloudflareaccess.com"
AUD = "relay-app-aud"
KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
OTHER_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)


def jwks():
    k = json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(KEY.public_key()))
    k.update(kid="k1", alg="RS256", use="sig")
    return {"keys": [k]}


def token(aud=AUD, iss=f"https://{TEAM}", exp_in=300, key=KEY, kid="k1"):
    now = int(time.time())
    return jwt.encode({"aud": [aud], "iss": iss, "iat": now, "exp": now + exp_in,
                       "common_name": "phone.access"}, key, algorithm="RS256", headers={"kid": kid})


def verifier(fetch=jwks):
    return relay.AccessVerifier(TEAM, AUD, fetch=lambda team: fetch())


def test_access_jwt():
    v = verifier()
    v.check(token())
    for bad in ["", "not.a.jwt", token(aud="other-app"), token(iss="https://evil.cloudflareaccess.com"),
                token(exp_in=-60), token(key=OTHER_KEY), token(kid="k2"),
                jwt.encode({"aud": AUD}, "secret", algorithm="HS256", headers={"kid": "k1"})]:
        with pytest.raises(relay.AccessDenied):
            v.check(bad)


def test_access_keys_unreachable():
    def down():
        raise OSError("connection refused")
    with pytest.raises(relay.AccessUnavailable):
        verifier(down).check(token())
    # Once keys are cached, a failed refetch keeps using them.
    calls = []
    def flaky():
        calls.append(1)
        if len(calls) > 1:
            raise OSError("down")
        return jwks()
    v = verifier(flaky)
    v.check(token())
    v.fetched -= relay.KEYS_TTL + 1
    v.check(token())
    assert len(calls) == 2


def batch(before=2086524, after=2091203, **extra):
    b = {"batch_id": f"{SERIAL}:{before}:{after}", "serial": SERIAL,
         "cursor_before": before, "cursor_after": after,
         "events": [[65, before + 6, "0a1b2c"], [72, before + 7, ""]]}
    b.update(extra)
    return b


def raw(b):
    return json.dumps(b).encode()


def test_valid_batch_and_optional_fields():
    b = batch(anchor={"ring_timestamp": 2091202, "unix_time": 1791950000},
              device={"firmware": "2.12.5", "battery": 98},
              feature_modes={"daytime_hr": 1, "spo2": 1, "cva_ppg": 0})
    assert relay.validate(raw(b), SERIALS, 1791950000) == b


@pytest.mark.parametrize("change", [
    {"serial": "999"},
    {"batch_id": f"{SERIAL}:1:2"},
    {"cursor_before": 5, "cursor_after": 4, "batch_id": f"{SERIAL}:5:4"},
    {"cursor_after": 2**32, "batch_id": f"{SERIAL}:2086524:{2**32}"},
    {"cursor_before": True},
    {"events": [[256, 1, ""]]},
    {"events": [[65, -1, ""]]},
    {"events": [[65, 1, "ABCD"]]},
    {"events": [[65, 1, "abc"]]},
    {"events": [[65, 1]]},
    {"events": [[65, 1.5, ""]]},
    {"events": [[65, 1, ""]] * 4097},
    {"extra": 1},
    {"anchor": {"ring_timestamp": 1, "unix_time": 5}},
    {"anchor": {"ring_timestamp": 1, "unix_time": 1791950000, "x": 1}},
    {"device": {"firmware": "2.12.5; rm -rf"}},
    {"device": {"battery": 101}},
    {"device": {}},
    {"feature_modes": {"ring_key": 1}},
    {"feature_modes": {"spo2": 300}},
])
def test_invalid(change):
    with pytest.raises(relay.Invalid):
        relay.validate(raw(batch(**change)), SERIALS, 1791950000)


@pytest.mark.parametrize("body", [b"{not json", b"[]", b'{"a":1,"a":2}', b'{"x": NaN}', b"\xff\xfe"])
def test_invalid_json(body):
    with pytest.raises(relay.Invalid):
        relay.validate(body, SERIALS, 0)


def test_missing_field():
    b = batch()
    del b["events"]
    with pytest.raises(relay.Invalid):
        relay.validate(raw(b), SERIALS, 0)


def test_gunzip_caps_and_strictness():
    assert relay.gunzip_capped(gzip.compress(b"x" * 100), 100) == b"x" * 100
    bomb = gzip.compress(b"\0" * (17 * 1024 * 1024))
    assert len(bomb) < 100_000
    with pytest.raises(relay.Invalid):
        relay.gunzip_capped(bomb, relay.MAX_JSON)
    with pytest.raises(relay.Invalid):
        relay.gunzip_capped(gzip.compress(b"ab")[:-3], 100)
    with pytest.raises(relay.Invalid):
        relay.gunzip_capped(gzip.compress(b"ab") + gzip.compress(b"cd"), 100)
    with pytest.raises(relay.Invalid):
        relay.gunzip_capped(b"plain", 100)


def test_secrets(tmp_path):
    conf = tmp_path / "c"
    conf.write_text(f"# phone\n{hashlib.sha256(SECRET.encode()).hexdigest()}  pixel 2026-10-13\n")
    hashes = relay.load_hashes(conf)
    assert relay.secret_ok(f"Bearer {SECRET}", hashes)
    assert not relay.secret_ok("Bearer wrong", hashes)
    assert not relay.secret_ok(SECRET, hashes)
    assert not relay.secret_ok(f"Bearer {SECRET}", [])
    conf.write_text("plaintext-secret\n")
    with pytest.raises(ValueError):
        relay.load_hashes(conf)


@pytest.fixture
def server(tmp_path):
    spool = tmp_path / "spool"
    for d in ["tmp", "incoming", "done", "failed"]:
        (spool / d).mkdir(parents=True)
    conf = tmp_path / "health-relay.conf"
    conf.write_text(hashlib.sha256(SECRET.encode()).hexdigest() + " test\n")
    relay.Handler.allow = relay.ipaddress.ip_network("127.0.0.0/8")
    relay.Handler.access = verifier()
    relay.Handler.conf = conf
    relay.Handler.serials = SERIALS
    relay.Handler.spool = relay.Spool(spool)
    srv = ThreadingHTTPServer(("127.0.0.1", 0), relay.Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{srv.server_address[1]}", spool
    srv.shutdown()


def post(url, body, secret=SECRET, path="/v1/batch", encoding="gzip", want_body=False, jwt_token=None):
    req = urllib.request.Request(url + path, data=body, method="POST", headers={
        "Authorization": f"Bearer {secret}", "Content-Type": "application/json",
        "Content-Encoding": encoding,
        "Cf-Access-Jwt-Assertion": token() if jwt_token is None else jwt_token})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return (e.code, e.read().decode()) if want_body else e.code


def test_http(server):
    url, spool = server
    body = gzip.compress(raw(batch()))
    name = f"{SERIAL}_0002086524_0002091203.json"
    assert post(url, body, secret="wrong") == 401
    assert post(url, body, jwt_token="") == 403
    assert post(url, body, jwt_token=token(aud="other-app")) == 403
    assert post(url, body, path="/v1/other") == 404
    assert list((spool / "incoming").iterdir()) == []

    assert post(url, body) == 204
    spooled = json.loads((spool / "incoming" / name).read_text())
    assert spooled == batch()
    assert list((spool / "tmp").iterdir()) == []

    # Already spooled, or already ingested: 204, nothing rewritten.
    assert post(url, body) == 204
    (spool / "incoming" / name).rename(spool / "done" / name)
    assert post(url, body) == 204
    assert list((spool / "incoming").iterdir()) == []

    assert post(url, gzip.compress(raw(batch(serial="1"))), want_body=True) == (
        422, "invalid batch: serial '1' not allowed")
    assert post(url, gzip.compress(b"{bad")) == 422
    assert post(url, raw(batch(1, 2)), encoding="identity") == 422
    assert post(url, gzip.compress(b"\0" * (17 * 1024 * 1024))) == 422
    assert post(url, b"x" * (relay.MAX_BODY + 1)) == 413
    assert list((spool / "incoming").iterdir()) == []


def test_http_wrong_source(server):
    url, _ = server
    relay.Handler.allow = relay.ipaddress.ip_network("172.21.0.0/16")
    assert post(url, gzip.compress(raw(batch()))) == 403
