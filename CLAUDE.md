# health (health.bix.computer)

Self-hosted health data. Today that is an Oura ring, read straight off the ring
over Bluetooth with no Oura app or cloud. Since 2026-10-13 an **Android phone
owns the ring**: our app (`android/`) bonds to it, syncs it a few times a day,
and uploads raw events to the HTPC, which stores them and serves the dashboard.
The dashboard is upstream
[open_health](https://github.com/Th0rgal/open_health)'s `oura dashboard`, which
pins [open_oura](https://github.com/Th0rgal/open_oura) (protocol, store) in its
own Cargo.toml. Our code is the phone app, its Rust bridge, the relay, and
packaging.

Naming: generic things are `health` (project, compose services, network, logs,
data dir); things that are specifically the Oura product keep `oura` (the
upstream binary, `oura-ring.key`, `oura.db`).

## Layout

| Path | What |
|------|------|
| `android/` | Phone app (Kotlin) + `android/rust` (`ouralink` JNI crate over open_oura `4f7957d`). Build, install, provisioning: `android/README.md` |
| `docs/android-design.md` | Phone design, spike results, what's verified |
| `docs/relay-plan.md` | HTPC relay plan and **the wire contract** the phone uses |
| `Dockerfile` | Builds `oura` at the pinned `OPEN_HEALTH_REV`. `runtime` stage = the dashboard container |
| `../bix-infra/nginx/health.conf` | Front proxy: Host rewrite, route allowlist |
| Relay (`health-relay` + `health-ingest`) | Being built on the HTPC, per `docs/relay-plan.md`. Add its paths here when it lands |
| `bin/oura`, `sync.sh`, `ring_connect.py`, `systemd/oura-sync.*`, `sync-trigger.py`, `systemd/health-sync-trigger.service`, `systemd/health-sync.sudoers`, `install-host.sh` | **Retired** host-Bluetooth sync and the dashboard's Sync trigger. The HTPC can't reach the ring any more (no bond). `oura-sync.timer` is inactive. Remove once the relay is live |

Compose services `health` (dashboard) and `health-proxy` (nginx, holds
`127.0.0.1:3014`) in `bix-infra/docker-compose.yml`, on their own `health`
network.

## Split: phone syncs, HTPC stores and serves

- **Phone (only bond to the ring).** Connect, authenticate, drain, disconnect;
  no held link. Every 6 h (WorkManager, 3 min scan window) plus Sync now
  (60 s). Each batch and the cursor are committed in one SQLite transaction
  on the phone; batches are deleted only after the relay returns 2xx, so
  being away from home just queues them. **The phone owns the sync cursor.**
- **Relay contract** (`docs/relay-plan.md`): `POST /relay/v1/batch`, gzip
  JSON `{batch_id, serial, cursor_before, cursor_after, events: [[tag,
  ring_timestamp, body_hex]…], anchor?, device?, feature_modes?}`, bearer
  device secret + Cloudflare Access service token. `batch_id` is the
  idempotency key; `422` makes the phone set a batch aside (kept, shown,
  retried on Sync now). The relay must reproduce what `oura sync` wrote:
  events (decoded with the pinned decoders), cursor/last-sync for display,
  the phone `time_sync` anchor, device row, `feature_modes.json`.
- **Secrets on the phone** (ring key, relay URL, device secret, CF token) are
  in Android Keystore-backed storage. They arrive once as
  `files/provision.json` via `adb run-as`, which the app imports and deletes.
  The ring key is piped from the HTPC straight into adb, never onto the Mac's
  disk (`android/README.md`).
- The HTPC copy of the **auth key** (`~/.config/bix/oura-ring.key`) is no
  longer used for syncing; it stays outside `~/health/data` because
  `oura dashboard` auto-discovers `oura.key` / `oura-*.key` beside the
  database and the container must not see it.
- **Data** lives in `~/health/data` (outside the apps tree, like Actual and
  Materialious) so ai-router can't read health data. Pre-reset backup:
  `~/health/data/oura.db.pre-reset-bak` (keep until the relay has delivered).

## Gotchas

### Dashboard and proxy

- The dashboard binds `127.0.0.1` inside its container and 403s any `Host`
  but `127.0.0.1:<port>`; hence `network_mode: service:health-proxy`, with the
  proxy rewriting Host. The proxy owns the namespace, so recreating `health`
  is safe; **recreating `health-proxy` strands `health`**: run
  `docker compose up -d --force-recreate health` after it.
- The proxy is an **allowlist**. `/api/ring-key` (exports/replaces the key),
  `/api/live-hr`, `/api/feature`, `/dna`, `/blood` all 404; the page's Live
  HR, feature toggles and DNA/blood links fail on purpose (Live HR is on the
  phone now). A route added upstream stays closed until listed in
  `nginx/health.conf`. The **Sync button** can no longer start anything: sync
  runs on the phone.
- Host processes calling 172.17.0.1 arrive from the LAN IP and are refused by
  the docker0 daemons' IP checks; test them from a container.
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
  does (built to `~/reset-tool/oura-reset` on 2026-10-13; delete when done).
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

### Heartbeat

- `bix-infra/logs/oura-sync/last-success` stopped updating at
  2026-10-13 17:26 (last HTPC sync). The relay's ingest takes over stamping
  it (`docs/relay-plan.md`); until then the 05:00 compiler raises the 24 h
  error from about 17:26 on 2026-10-14.

## Commands

```bash
# Dashboard image (after bumping OPEN_HEALTH_REV)
cd ~/apps/bix-infra && docker compose build health

# Phone app: build, install, provision, read state (from the Mac; android/README.md)
cd android/rust && cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release
cd android && ./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Updating the pin

Review upstream's diff since `OPEN_HEALTH_REV` (and the open_oura rev it pins),
especially `crates/oura-cli/src/dashboard.rs` routes against the proxy
allowlist, then bump the ARG and rebuild. **Keep the open_oura rev in
`android/rust/Cargo.toml` equal to the one open_health pins**, so the phone's
protocol code and the HTPC's decoders/store match; rebuild the phone app and
check the relay's ingest when it moves.
