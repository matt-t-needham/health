# health (health.bix.computer)

Self-hosted health data. Today that is an Oura ring, read straight off the ring
over Bluetooth with no Oura app or cloud. A packaging wrapper around upstream
[open_health](https://github.com/Th0rgal/open_health) (its `oura` binary: sync +
web dashboard), which pins [open_oura](https://github.com/Th0rgal/open_oura)
(protocol, BLE, store) in its own Cargo.toml. Our code is packaging plus the
Sync-button trigger.

Naming: generic things are `health` (project, compose services, network, logs,
data dir); things that are specifically the Oura product keep `oura` (the
upstream binary, `oura-sync` unit, `oura-ring.key`, `oura.db`).

## Layout

| Path | What |
|------|------|
| `Dockerfile` | Builds `oura` at the pinned `OPEN_HEALTH_REV` with `patches/` applied. `runtime` stage = the dashboard container |
| `patches/` | Our changes to upstream's web UI, applied at build; listed in `patches/README.md` |
| `bin/oura` | The same binary, copied out for the host sync unit (not committed; rebuild below) |
| `systemd/oura-sync.{service,timer}` | Host sync, hourly, into `~/health/data/oura.db` |
| `sync.sh` | The unit's `ExecStart`: connect + `oura sync`, retried for up to 15 min; stamps `bix-infra/logs/oura-sync/last-success` on success |
| `ring_connect.py` | Brings up the BlueZ link to the ring over D-Bus (python3-dbus + gi, distro packages), event-driven (see Gotchas) |
| `sync-trigger.py` + `systemd/health-sync-trigger.service` | Host daemon behind the dashboard's Sync button (172.17.0.1:9101) |
| `systemd/health-sync.sudoers` | Lets matt run exactly `systemctl start --no-block oura-sync.service` |
| `install-host.sh` | One-time root setup of everything outside the apps tree |
| `../bix-infra/nginx/health.conf` | Front proxy: Host rewrite, route allowlist, Sync route |

Compose services `health` (dashboard) and `health-proxy` (nginx, holds
`127.0.0.1:3014`) in `bix-infra/docker-compose.yml`, on their own `health`
network.

## Split: host sync, container dashboard

- Bluetooth goes through BlueZ on the host system D-Bus. Giving a container
  that bus would break the container baseline, so **sync runs on the host**
  as `matt` (systemd), and the container never touches Bluetooth.
- **Sync button:** health-proxy sends `POST /api/sync` (same-origin, with the
  dashboard's `X-Oura-Dash` header) to `health-sync-trigger` on docker0 with
  a token from `~/.config/bix/health-sync-token.conf`. The trigger takes no
  input and can only start `oura-sync.service`: at most once per 15 minutes
  (in memory, so a restart allows one early press; a failed start doesn't
  count), never while one is running. It waits up to 80s, then replies
  "Still syncing", because Cloudflare cuts requests at 100s. nginx adds a
  1r/m flood guard in front. It doesn't verify the Access JWT: the token
  limits callers to health-proxy, and the worst case is one extra sync per
  15 minutes.
- The **auth key** (16 bytes, hex) lives at `~/.config/bix/oura-ring.key`,
  never in `~/health/data`: `oura dashboard` auto-discovers `oura.key` /
  `oura-*.key` beside the database, and the container must not see it.
- **Data** lives in `~/health/data` (outside the apps tree, like Actual and
  Materialious) so ai-router can't read health data.

## Gotchas

- The dashboard binds `127.0.0.1` inside its container and 403s any `Host`
  but `127.0.0.1:<port>`; hence `network_mode: service:health-proxy`, with the
  proxy rewriting Host. The proxy owns the namespace, so recreating `health`
  is safe; **recreating `health-proxy` strands `health`**: run
  `docker compose up -d --force-recreate health` after it.
- The proxy is an **allowlist**. `/api/ring-key` (exports/replaces the key),
  `/api/live-hr`, `/api/feature`, `/dna`, `/blood` all 404 on purpose, and
  `patches/dashboard-trim.patch` removes the controls that call them (Live HR,
  DNA/Blood links, key export/import, capability toggles). A route added
  upstream stays closed until listed in `nginx/health.conf`; a control added
  upstream for a closed route shows until it is patched out too.
- Host processes calling 172.17.0.1 arrive from the LAN IP and are refused by
  the trigger's IP check; test it from a container.
- Scores (sleep/readiness/activity, CVA) need Oura's proprietary PyTorch
  models, which upstream doesn't ship and we don't have. The runners return
  nothing and the dashboard shows raw metrics only.
- Upstream CI is macOS-only; the Linux build is ours to keep working.
- **Our ring:** Ring 4, serial 2016082503005273, BlueZ identity
  `A0:38:F8:41:98:18` (`--address hci0/dev_A0_38_F8_41_98_18`). A second
  Ring 4 in the house (someone else's) also advertises as "Oura Ring 4":
  always select by `--address`, never by name.
- **Connecting needs `ring_connect.py` first.** The adapter can't resolve the
  ring's rotating private address in hardware ("Unable to set wake_support
  without RPA resolution"), so a connect to the identity only completes while
  discovery is running, on the ring's next received advert. `oura` stops its
  scan before connecting and times out ("Timeout waiting for reply", then
  "Operation already in progress"); once BlueZ holds the link, `oura` reuses
  it. Measured 2026-10-11 on the charger at -68..-77 dBm: received adverts
  1-23 s apart (median ~4 s, first after up to 13 s); `Connect()` returned
  7-21 s after the call, 5/5 trials. So never use fixed sleeps (the old
  bluetoothctl script failed whenever a cycle ran long): `ring_connect.py`
  waits for a real advert, calls `Connect()` with a 45 s timeout while
  discovery keeps running, waits for `ServicesResolved`, and retries to its
  deadline. It always drops a leftover link first: an idle one left by an
  earlier command stops answering ("no nonce response"). Piped
  `bluetoothctl` output is block-buffered, so it can't be used for timing.
  If the ring shows up at a random address instead of `A0:38:…`,
  `sudo systemctl restart bluetooth`.
- **Failure reporting.** A run that doesn't sync fails the unit and ends with
  one line: `WARN no sync this run …` while the last success is under 24 h
  old, `ERROR no successful ring sync in over 24 h …` after. The registry
  flags only those, `oura`'s `Error:` lines, tracebacks and non-exit-code
  systemd failures (timeouts) for this journal, and a `heartbeat` source on
  the stamp makes the 05:00 compiler raise an error after 24 h even if the
  timer never ran.
- **Pairing on Linux needs a BlueZ agent** to accept the ring's bond request:
  run `bluetoothctl` fed "yes" lines in the background while `oura pair`
  runs, or BlueZ refuses (`device_confirm_passkey: Operation not permitted`).
- **`features`/`feature-status`/`subscribe` never authenticate** (open_health
  08e1b86 leaves them out of `uses_ring` in `main.rs`, so the key isn't
  loaded) and fail with "no … response". Use `feature-mode 0x02` (daytime
  HR) / `feature-mode 0x04` (SpO2) instead.
- **Never set the ring up in the Oura app** after a factory reset: it
  installs Oura's key and locks this out until another reset.

## Commands

```bash
# Build image + refresh the host binary (after bumping OPEN_HEALTH_REV)
cd ~/apps/bix-infra && docker compose build health
cid=$(docker create apps-health) && docker cp $cid:/usr/local/bin/oura ../health/bin/oura && docker rm $cid

# Ring commands (host)
K=~/.config/bix/oura-ring.key; D=~/health/data/oura.db
R="--name '' --address hci0/dev_A0_38_F8_41_98_18 --key-file $K --db $D"
C="python3 -I ring_connect.py A0:38:F8:41:98:18 120"   # pre-connect before every ring command
bin/oura --name "" scan
bin/oura --name "" --key-file $K --db $D pair      # factory-reset ring only, with an agent (Gotchas)
./sync.sh                                           # what the unit runs (SYNC_DEADLINE_S, SYNC_STAMP override)
$C && eval bin/oura $R info
$C && eval bin/oura $R feature-mode 0x02            # daytime HR; 0x04 = SpO2
sudo systemctl start --no-block oura-sync.service   # allowed for matt by health-sync.sudoers
```

## Updating the pin

Review upstream's diff since `OPEN_HEALTH_REV` (and the open_oura rev it pins),
especially `crates/oura-cli/src/dashboard.rs` routes against the proxy
allowlist and the `/api/sync` reply shape `app.js` expects, and any new UI
controls that call closed routes, then bump the ARG, rebuild (the build fails
if a patch in `patches/` no longer applies: regenerate it per
`patches/README.md`), and copy the binary out again.
