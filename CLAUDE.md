# health (health.bix.computer)

Self-hosted health data. Today that is an Oura ring, read straight off the ring
over Bluetooth with no Oura app or cloud. Since 2026-10-13 an **Android phone
owns the ring**: our app (`android/`) bonds to it, syncs it a few times a day,
and uploads raw events to the HTPC, which stores them and serves the dashboard.
The dashboard is upstream
[open_health](https://github.com/Th0rgal/open_health)'s `oura dashboard`, which
pins [open_oura](https://github.com/Th0rgal/open_oura) (protocol, store) in its
own Cargo.toml. Our code is the phone app, its Rust bridge, the HTPC relay
and ingest, and packaging.

Naming: generic things are `health` (project, compose services, network, logs,
data dir, relay, ingest); things that are specifically the Oura product keep
`oura` (the upstream binary, `oura.db`).

## Layout

| Path | What |
|------|------|
| `Dockerfile` | Builds `oura` at the pinned `OPEN_HEALTH_REV` with `patches/` applied. `runtime` stage = the dashboard container; `ingest` stage = health-ingest against the same checkout |
| `patches/` | Our changes to upstream's web UI and sleep staging, applied at build; listed in `patches/README.md` |
| `staging-eval/` | Scores sleep staging against two-ring reference nights (official Oura app on a second ring); references and outputs in `~/health/eval/`. Run it before and after any staging change (`README.md`) |
| `android/` (`README.md`), `docs/android-design.md` | The phone app that owns the ring, and its Rust bridge (built and installed from the Mac, over adb) |
| `relay/health-relay.py` + `systemd/health-relay.service` | Phone app's upload endpoint (172.17.0.1:9102): auth, validate, spool. See "Relay" |
| `ingest/` → `bin/health-ingest` + `systemd/health-ingest.{service,path}` | Commits spooled batches into `oura.db` with the pinned oura-store (Rust, built in the Dockerfile's `ingest` stage) |
| `relay/provision-phone.sh` | New device secret → hash in `~/.config/bix/health-relay.conf`, provisioning JSON on stdout for adb |
| `relay/test_relay.py` | Relay tests: `cd relay && python3 -m pytest -q` |
| `install-host.sh` | Root setup of everything outside the apps tree (and cleanup of the retired host sync) |
| `../bix-infra/nginx/health.conf` | Front proxy: Host rewrite, route allowlist, relay route, static Sync-button reply |

Compose services `health` (dashboard) and `health-proxy` (nginx, holds
`127.0.0.1:3014`) in `bix-infra/docker-compose.yml`, on their own `health`
network.

## Split: phone syncs, HTPC stores and serves

- **Phone (only bond to the ring).** Connect, authenticate, drain, disconnect;
  no held link. Every 6 h (WorkManager, 3 min scan window) plus Sync now
  (60 s). Each batch and the cursor are committed in one SQLite transaction
  on the phone; batches are deleted only after the relay returns 2xx, so
  being away from home just queues them. **The phone owns the sync cursor.**
- **Uploads** go to the HTPC relay; the contract and the HTPC side are under
  "Relay" below. A `422` makes the phone set a batch aside (kept, shown,
  retried on Sync now).
- **Secrets on the phone** (ring key, relay URL, device secret, CF token) are
  in Android Keystore-backed storage. They arrive once as
  `files/provision.json` via `adb run-as`, which the app imports and deletes.
  The ring key was piped from the HTPC straight into adb, never onto the
  Mac's disk (`android/README.md`). The HTPC copy is gone now, so the phone's
  Keystore holds the only one: losing it (app data cleared, reinstall) means
  a factory reset and a new key.
- **The HTPC holds no ring key** (its copy was deleted 2026-10-13). Never put
  one in `~/health/data`: `oura dashboard` auto-discovers `oura.key` /
  `oura-*.key` beside the database, and the container must not see it.
- **No host sync.** The HTPC's BlueZ sync (`oura-sync` unit, `sync.sh`,
  `ring_connect.py`, the Sync-button trigger and its sudoers rule) was
  retired 2026-10-13; see git history before then if it's ever needed. The
  dashboard's **Sync button** is answered by health-proxy itself with
  `{"ok": true, "message": "Syncing happens in the phone app now…"}`; `ok`
  makes the page reload with the latest uploads.
- **Data** lives in `~/health/data` (outside the apps tree, like Actual and
  Materialious) so ai-router can't read health data.

## Relay (phone uploads)

The Android app (`android/`, `docs/android-design.md`) owns the ring; the HTPC
only receives what it drained.

```
phone --HTTPS--> Cloudflare Access (health app; Service Auth policy, service token)
      --> health-proxy  location = /relay/v1/batch: POST, 2m, 30r/m burst 120
      --> health-relay  172.17.0.1:9102: health network source, Access JWT, bearer
      --> ~/health/relay/spool/incoming/  --> health-ingest --> oura.db
```

- **Wire contract:** `POST /relay/v1/batch`, gzip JSON `{batch_id, serial,
  cursor_before, cursor_after, events: [[tag, ring_timestamp, body_hex]…],
  anchor?, device?, feature_modes?}`; `batch_id` =
  `serial:cursor_before:cursor_after` is the idempotency key. The phone sends
  `anchor` only on the newest batch of a sync that stored events (ring
  timestamp `cursor - 1`), `device`/`feature_modes` on every batch.
  Replies: 204 once fsynced into the spool (also for a batch already in
  incoming/done/failed), 401 bad secret, 422 permanently invalid with a
  one-line plain-text reason (the phone sets the batch aside and shows it),
  413/429/5xx retry. Validation is strict: unknown fields, other serials
  (`HEALTH_SERIALS` in both units), out-of-range values, non-lowercase hex,
  >4096 events and >16 MB decompressed are all 422.
- **Two checks, both required:** the Access JWT (`Cf-Access-Jwt-Assertion`,
  RS256 from `bixcomputer.cloudflareaccess.com`'s keys, `aud` =
  `HEALTH_RELAY_ACCESS_AUD`, the health.bix.computer app's AUD tag) proves
  the request came through Access; the
  device bearer secret identifies the phone. No JWT or a wrong one is 403;
  Cloudflare's keys unreachable (and none cached) is 503. The AUD lives in
  `bix-infra/.env`; `install-host.sh` copies just that line into
  `/etc/systemd/system/health-relay.service.d/access.conf`, so rerun it after
  changing it.
- **One Access app for dashboard and relay** (Matt's choice, 2026-10-13):
  the phone's service token passes a Service Auth policy on the
  health.bix.computer app beside the email policy, so that token also opens
  the dashboard. A leaked token reads health data; revoke it in Zero Trust.
  A logged-in browser's JWT passes the relay's check too, but still lacks
  the device secret.
- **health-relay never opens the database** and executes nothing. Its unit
  gives it an empty `/home` with only its script, the hash file and the spool
  bound in. Device secrets are stored as SHA-256 hashes, one per line, reread
  per request: revoke a phone by deleting its line.
- **health-ingest** reproduces what the retired host `oura sync` wrote: events via
  `RingEvent::from_packet` + `commit_batch` with cursor
  `max(stored, cursor_after)` (display only; the phone owns the real cursor),
  the phone anchor as a 0x42 `time_sync` row, the device row's firmware
  (keeping the stored api_version/MAC), and `feature_modes.json`. Battery is
  only logged; the dashboard reads it from `debug_data` events. Each file
  moves to `done/` (pruned after 30 days) or `failed/` with one `ERROR` line.
  Replay a failed one by moving it back to `incoming/`. A batch with new rows
  rewrites `bix-infra/logs/oura-sync/last-success` (path kept from the old
  host sync), so the 24 h heartbeat means "fresh ring data reached the HTPC".
  One run drains until the spool has been empty for 5 s: a run per arriving
  batch tripped systemd's start limit on the first backlog upload.
- `HEALTH_RELAY_ALLOW` in `health-relay.service` is the health network's
  subnet (`172.21.0.0/16`); if Docker recreates it elsewhere, uploads fail
  closed with 403 until it's updated.

## Gotchas

### Dashboard and proxy

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
  the relay's source check; test it from a container on the health network
  (and it still wants an Access JWT, so the full path is only testable
  through Cloudflare with the service token).
- Scores (sleep/readiness/activity, CVA) need Oura's proprietary PyTorch
  models, which upstream doesn't ship and we don't have. The runners return
  nothing and the dashboard shows raw metrics only.
- Upstream CI is macOS-only; the Linux build is ours to keep working.

### The ring

- **Our ring:** Ring 4, serial `2016082503005273`, fw 2.12.5. Bonded to the
  phone; Android sees it as `60:62:4D:4D:CA:B2`. The old BlueZ identity
  `A0:38:F8:41:98:18` is dead. A second Ring 4 in the house (someone else's)
  also advertises as "Oura Ring 4": pairing picks the strongest signal and
  then **refuses any serial but ours before installing the key**.
- **One bond only.** A provisioned ring cuts off any central that isn't
  bonded within 2-3 connection events (Android: GATT status 133 about 2 s
  after connecting). Moving the ring to another device means a factory
  reset: the BLE bond is the gate, not the auth key. A reset erases the key,
  all bonds and the on-ring buffer (sync first); the phone then re-installs
  the same key so history stays under the same serial.
- **Factory reset needs a bonded, authenticated device.** The pinned
  open_health `oura` has no `factory-reset`; the open_oura CLI at `4f7957d`
  does (built for the 2026-10-13 reset, then deleted; rebuild it if needed).
  Without a bonded device, only the dock procedure works
  (open_oura `docs/factory-reset.md`).
- **Never set the ring up in the Oura app** after a factory reset: it
  installs Oura's key and locks this out until another reset.
- **Live heart rate needs two commands**, which the official app sends:
  `SetFeatureMode(DAYTIME_HR, CONNECTED_LIVE)` (`2f03220203`) **and**
  `SetFeatureSubscription(DAYTIME_HR, LATEST)` (`2f03260202`). open_oura's
  `live_heart_rate` (and upstream `oura live-hr`) sends only the first; the
  ring ACKs and sends nothing. Beats arrive as ext `0x28` frames in bursts
  every 10-20 s with a validity nibble; only validity 1 is shown. The phone
  undoes both on exit.
- **Feature modes are re-applied on every phone sync** (daytime HR, SpO2,
  resting HR, real steps, CVA PPG automatic), which also repairs a feature
  left in live mode by a cut-off session. `exercise_hr` is left alone: asking
  for OFF returns `0x20`. The real modes are read back and sent to the HTPC
  for `feature_modes.json`.
- **No command writes the ring's body profile.** After a reset it holds
  firmware defaults; the dashboard uses `profile.json` on the HTPC (editable
  on the web page via `/api/profile`).

### Phone

- **Samsung puts background apps to sleep.** Set Health to "Unrestricted"
  battery use, or the 6-hourly syncs may not run. Not yet verified that a
  scheduled sync runs with the phone idle.
- Worn, the ring advertises every few seconds to a minute or more; finding
  it can take 20-30 s at -70 dBm and about 1 s held next to the phone.
- **Debug builds** include an exported `DebugReceiver` (fake batch, repair).
  It refuses to add fake batches once a ring is paired, but don't use it on
  a real queue. Ship a release build before relying on this long term.
- Failures surface as words on the status card and in the activity log
  (Expand → Copy all); nothing is colour-only.

## Commands

```bash
# Build the dashboard image (after bumping OPEN_HEALTH_REV or changing patches/)
cd ~/apps/bix-infra && docker compose build health && docker compose up -d health

# Build + test health-ingest and copy it out (after changing ingest/ or the pin)
cd ~/apps/health && docker build --target ingest -t health-ingest-build .
cid=$(docker create health-ingest-build) && docker cp $cid:/src/health-ingest/target/release/health-ingest bin/health-ingest && docker rm $cid

# Provision the phone (from the Mac, phone on USB; see relay/provision-phone.sh).
# -T matters: with a terminal the script refuses, and its message goes down the pipe.
ssh -T matt@192.168.0.47 '~/apps/health/relay/provision-phone.sh' \
  | adb shell "run-as computer.bix.health sh -c 'cat > files/provision.json'"

# Phone app: build, install (from the Mac; android/README.md)
cd android/rust && cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release
cd android && ./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk

# Spool state, and replaying a failed batch
ls ~/health/relay/spool/{incoming,done,failed} | head
journalctl -u health-relay -u health-ingest -n 50
mv ~/health/relay/spool/failed/<file>.json ~/health/relay/spool/incoming/
```

## Updating the pin

Review upstream's diff since `OPEN_HEALTH_REV` (and the open_oura rev it pins),
especially `crates/oura-cli/src/dashboard.rs` routes against the proxy
allowlist, the `/api/sync` reply shape `app.js` expects (nginx's static
reply), any new UI controls that call closed routes, and oura-store's
`commit_batch`/`insert_event`/`upsert_device` (health-ingest uses them; keep
the open_oura rev in `ingest/Cargo.toml` equal to upstream's). Then bump the
ARG, rebuild both the image and health-ingest (the build fails if a patch in
`patches/` no longer applies: regenerate it per `patches/README.md`).
