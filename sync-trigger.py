#!/usr/bin/env python3
"""Sync button for health.bix.computer.

The dashboard container has no Bluetooth, so its Sync button reaches this host
daemon instead: health-proxy (nginx) forwards POST /api/sync here with a token,
and the only thing this can do is start oura-sync.service, at most once per
RATE_LIMIT. No request input reaches the command line.

Listens on 172.17.0.1:9101 (docker0, what host.docker.internal resolves to).
Every request must carry X-Health-Sync-Token; the token lives in
~/.config/bix/health-sync-token.conf as the nginx `set` line health-proxy
includes. Runs as matt (systemd health-sync-trigger); starting the system unit
goes through one sudoers rule (health/systemd/health-sync.sudoers).

Replies in the dashboard's own sync shape, {"ok": bool, "message": str}.
Cloudflare drops a request after 100s, so this waits at most WAIT for the sync
and then reports it as still running rather than as done.
"""

import hmac
import json
import re
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HOST = "172.17.0.1"
PORT = 9101
UNIT = "oura-sync.service"
RATE_LIMIT = 15 * 60  # seconds between accepted presses
WAIT = 80             # seconds to wait for the sync before replying
TOKEN_HEADER = "X-Health-Sync-Token"
TOKEN_FILE = Path("/home/matt/.config/bix/health-sync-token.conf")
# Defence in depth behind the bind address and the token: loopback and docker
# bridges only.
ALLOWED_PREFIXES = ("127.", "172.")


def _load_token() -> str:
    try:
        m = re.search(r'set \$health_sync_token "([^"]+)";', TOKEN_FILE.read_text())
    except OSError as e:
        sys.exit(f"[health-sync-trigger] can't read {TOKEN_FILE}: {e}")
    if not m or len(m.group(1)) < 32:
        sys.exit(f"[health-sync-trigger] token missing or too short in {TOKEN_FILE}")
    return m.group(1)


TOKEN = _load_token()

_lock = threading.Lock()
_last_accepted = 0.0  # monotonic; in memory, so a restart allows one early press


def log(msg: str) -> None:
    sys.stderr.write(f"[health-sync-trigger] {msg}\n")
    sys.stderr.flush()


def unit_props() -> dict[str, str]:
    out = subprocess.run(
        ["systemctl", "show", UNIT, "-p", "ActiveState", "-p", "Result", "-p", "InvocationID"],
        capture_output=True, text=True, check=True,
    ).stdout
    return dict(line.split("=", 1) for line in out.splitlines() if "=" in line)


def last_log_line(invocation: str) -> str:
    """The sync's last journal line, for the failure toast (matt reads the
    system journal through the adm group)."""
    out = subprocess.run(
        ["journalctl", f"_SYSTEMD_INVOCATION_ID={invocation}", "-o", "cat", "-n", "20", "--no-pager"],
        capture_output=True, text=True,
    )
    if out.returncode != 0:
        log(f"journalctl failed ({out.returncode}): {out.stderr.strip()}")
        return "see the oura-sync journal"
    lines = [l for l in out.stdout.splitlines() if l.strip() and not l.startswith(("Starting ", "Started ", "Finished "))]
    return lines[-1][:300] if lines else "no output"


def run_sync() -> tuple[int, dict]:
    global _last_accepted
    with _lock:
        props = unit_props()
        if props.get("ActiveState") == "activating":
            return 409, {"ok": False, "message": "A sync is already running."}
        wait_left = RATE_LIMIT - (time.monotonic() - _last_accepted)
        if _last_accepted and wait_left > 0:
            mins = int(wait_left // 60) + 1
            return 429, {"ok": False, "message": f"Synced recently; try again in {mins} min."}
        before = props.get("InvocationID", "")
        start = subprocess.run(
            ["sudo", "-n", "/usr/bin/systemctl", "start", "--no-block", UNIT],
            capture_output=True, text=True,
        )
        if start.returncode != 0:
            log(f"start failed ({start.returncode}): {start.stderr.strip()}")
            return 500, {"ok": False, "message": f"Couldn't start the sync: {start.stderr.strip()}"}
        _last_accepted = time.monotonic()
        log("sync started")

    deadline = time.monotonic() + WAIT
    while time.monotonic() < deadline:
        time.sleep(2)
        props = unit_props()
        if props.get("InvocationID", "") == before:
            continue  # the new run hasn't been picked up yet
        if props.get("ActiveState") != "activating":
            if props.get("Result") == "success":
                log("sync finished")
                return 200, {"ok": True, "message": "Ring synced."}
            reason = last_log_line(props["InvocationID"])
            log(f"sync failed ({props.get('Result')}): {reason}")
            return 200, {"ok": False, "message": reason}
    log("sync still running at reply time")
    return 200, {"ok": True, "message": "Still syncing; refresh in a few minutes."}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        log(f"{self.address_string()} - {fmt % args}")

    def _reply(self, code: int, body: dict) -> None:
        b = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(b)

    def do_POST(self):
        if not self.client_address[0].startswith(ALLOWED_PREFIXES):
            return self._reply(403, {"ok": False, "message": "forbidden"})
        supplied = self.headers.get(TOKEN_HEADER, "")
        if not hmac.compare_digest(supplied.encode(), TOKEN.encode()):
            return self._reply(403, {"ok": False, "message": "forbidden"})
        if self.path != "/sync":
            return self._reply(404, {"ok": False, "message": "not found"})
        try:
            code, body = run_sync()
        except subprocess.CalledProcessError as e:
            log(f"systemctl show failed ({e.returncode}): {e.stderr}")
            code, body = 500, {"ok": False, "message": "Couldn't read the sync unit's state."}
        self._reply(code, body)


if __name__ == "__main__":
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    log(f"listening on {HOST}:{PORT}")
    server.serve_forever()
