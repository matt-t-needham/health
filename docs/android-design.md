# Android companion: design

Status: draft, 2026-10-13. Nothing here is built. Items marked **unverified**
need a spike (see the end) before the design relies on them.

## Goal

A small Android app whose only job is the Bluetooth link to the ring. It
runs the ring protocol, uploads what it gets to the HTPC, and shows status.
Decoding, storage and display stay on the HTPC (`health.bix.computer`). The
app replaces the HTPC's Bluetooth sync.

- Sync a few times a day plus on demand. No held BLE link: connect, drain,
  upload, disconnect.
- The phone is the ring's only bond. The HTPC stops connecting to the ring.
- Away from home is fine: batches wait on the phone until an upload succeeds.

## Roles

**Phone**
- Holds the 16-byte ring auth key and runs the protocol (nonce auth, history
  drain).
- Stores each batch durably before the batch is allowed to finish. The ring
  advances its cursor when a batch completes; a crash between completion and
  a durable write would lose that data permanently.
- Uploads stored batches to `/relay`, deletes a batch only after the HTPC
  acknowledges it.
- Shows status as text only (no colour-only states, no icon glyphs):
  `Ring: connected` / `Ring: not seen`, and `Last push to HTPC: <date>`,
  plus the last error in words.

**HTPC**
- New host service `health-relay` (like `health-sync-trigger`), behind the
  `/relay` route in `../bix-infra/nginx/health.conf`.
- Accepts append-only, size-limited, idempotent batches (batch ID dedupes
  retries). Executes nothing. No route to the key or to feature/key
  management.
- Decodes server-side and writes `oura.db`. Dashboard unchanged.
- Stamps `bix-infra/logs/oura-sync/last-success` when a push lands, so the
  24 h heartbeat alarm keeps working. Logs where the nightly review reads.
- Failures are loud: a rejected or malformed batch is logged as an `ERROR`
  line, never dropped silently.

## Authentication of the phone

No interactive login. Two pre-shared layers, both sent on every request:

1. **Cloudflare Access service token** (`CF-Access-Client-Id` /
   `CF-Access-Client-Secret`). A separate Access application scoped to
   `health.bix.computer/relay/*` with a Service Auth policy. The existing
   email-OTP application keeps covering the rest of the site. Tokens expire;
   the app shows a plain-text error when Cloudflare rejects it.
2. **Per-device bearer secret** (`Authorization: Bearer ...`) checked by
   `health-relay`. The HTPC stores only a hash, in a file outside the apps
   tree (like `~/.config/bix/health-sync-token.conf`). Revoking a phone is
   deleting its entry.

Optional, if wanted later: HMAC over body + timestamp + batch ID to stop
tampering and replay. Batch IDs already make retries idempotent.

### Provisioning (once, from the HTPC)

The ring key, device secret and Cloudflare token go to the phone in one QR
code or adb push. Never typed, never sent over the internet. All three are
stored in Android Keystore-backed storage.

### Stolen phone

Worst case the attacker holds all three secrets: they can push fake data and
talk to the ring, but cannot read health data or reach the dashboard
(`/relay` is append-only). Cleanup: delete the device entry, revoke the
Cloudflare token, re-key the ring.

## Finding the ring

- The phone travels with the wearer, so it may hear a worn ring that the HTPC
  did not. **Unverified**; measure it.
- Bond via CompanionDeviceManager so Android can resolve the ring's rotating
  address from its IRK. Always select by bond/identity, never by name (a
  second "Oura Ring 4" is in the house).
- Scan: filtered (manufacturer `0x02b2`, service
  `98ed0001-a541-11e4-b6a0-0002a5d5c51b`), low power, bounded windows
  (about 3 min) a few times a day, with backoff and a bias toward evening
  and charging hours.
- On demand: button in the app and a Quick Settings tile. If the ring is not
  advertising, report `Ring: not seen` and stop; do not loop.
- Optional experiment: a filtered `PendingIntent` scan. Measure battery
  before adopting.
- The dashboard Sync button cannot reach the phone without push. For now it
  records a "requested" flag the phone reads on its next wake. No FCM.

## Protocol on the phone

Preferred: compile open_oura's Rust to Android (`cargo-ndk`, JNI) and
implement its `Transport` trait in Kotlin over Android BLE. This reuses the
protocol and avoids a second implementation. **Unverified:** that it builds
for Android, and whether sync output includes raw bytes the HTPC can decode
itself (preferred, so the phone uploads raw and the HTPC decodes).

Fallback: a Kotlin port of the protocol.

### Spike 3 findings (upstream `main`, read 2026-10-13, not built)

- `oura-link` has `btleplug` (0.11) as an optional `ble` feature, on by
  default. Building with `default-features = false` is how upstream's iOS app
  gets the auth + sync logic (`OuraClient`) with CoreBluetooth supplying the
  transport. Android is not addressed anywhere upstream, but the same route
  applies: no `ble`, our own transport.
- `Transport` is `async fn write(&self, &[u8])` plus
  `fn subscribe(&self) -> tokio::sync::broadcast::Receiver<Vec<u8>>`. It
  needs a tokio runtime inside the JNI library; the Kotlin side feeds
  notification frames into the broadcast sender and receives writes through a
  callback.
- `oura-store` keeps raw events losslessly plus the per-device sync cursor,
  and `oura redecode` re-decodes stored events. So the phone can upload raw
  events and the HTPC can decode them itself. This favours the raw upload
  already preferred above.
- **Builds for Android.** At open_oura `4f7957d`,
  `cargo check -p oura-link --no-default-features --target aarch64-linux-android`
  passes (pure Rust, including the `aes` auth, no NDK needed to type-check).
  A real `.so` still needs `cargo-ndk` and the NDK; not tried.
- **The cursor is ours, not the ring's.** `OuraClient::drain_events(cursor,
  on_event, on_batch)` requests events from a client-held timestamp cursor
  (deciseconds). It deliberately sends no ack-fetch, so nothing is erased or
  checkpointed on the ring. This corrects the brief's "ring checkpoints the
  cursor": a dropped link only costs re-pulling the current batch. The
  cursor-safety rule becomes simple and is already supported by the API:
  `on_event` and `on_batch` return `bool` (false aborts "not acknowledging
  batch"). Like `oura sync`, the phone buffers a batch's events in `on_event`
  and commits them together with the new cursor in one transaction inside
  `on_batch` (per-event fsyncs are far too slow on phone storage). The phone, not the HTPC, owns the cursor.
  The only way to lose data is the ring overwriting its own buffer before a
  sync, which is the "backs up" risk the phone is meant to reduce.
- Batches are up to 4096 events (extended drain, Ring 5 style) or 255
  (legacy), roughly a minute of transfer each; a drop re-pulls one batch.
- open_health's `oura-ffi` is analysis-only (C ABI, staticlib for iOS), so it
  doesn't help; we'd write a new small FFI/JNI crate over `oura-link`.
- Still to try: a real `cargo ndk` build, and a thin JNI crate that drives
  `OuraClient` with a Kotlin-backed `Transport`.

## Android specifics

- Kotlin, min API 31. Permissions: `BLUETOOTH_SCAN` (neverForLocation),
  `BLUETOOTH_CONNECT`.
- Foreground service of type `connectedDevice` during a sync; wake lock;
  request MTU 247 and high connection priority.
- Scheduling: WorkManager / inexact alarms. No exact alarms.
- Upload retries independently of the BLE session, over any network.

## Cutover

1. Build `health-relay` and test it against a mock phone on the HTPC.
2. Pair the phone, import the key, sync alongside nothing else (the ring has
   one bond, so the HTPC timer stops working at this point).
3. Run for several days; then disable `oura-sync.timer`. Keep
   `ring_connect.py` as a manual fallback.

## Spikes (need the phone)

1. **Pairing and key reuse.** Can the phone bond to the ring and authenticate
   with the existing key, without a factory reset? Does the ring need a
   pairing mode to accept a new bond? Does the HTPC bond drop? Fallback is a
   factory reset and re-pairing with a fresh key. Never set the ring up in
   the Oura app.
2. **Reachability.** Log adverts on the phone for 24 h while the ring is worn.
3. **Android build.** Done in part: `oura-link` type-checks for Android; see
   findings above. A real NDK build is still to do.

## Open decisions

- Push for the dashboard Sync button (none for now).
- HMAC request signing (optional).
- Upload format: raw events (preferred; the store is lossless and `oura redecode` exists). Needs a look at `RingEvent`/store schema to pick the wire format.

## Status (2026-10-13)

Built and compile-tested; the ring link itself is untested because the ring
is still bonded to the HTPC (see the spike results). Built:

- `android/rust`: JNI crate with `nativePair` (serial check, `set_auth_key`,
  authenticate) and `nativeSync` (authenticate, stream setup, clock sync,
  drain, per-batch callback). Serial mismatch aborts before anything is
  written or synced.
- `android/app`: GATT link, Keystore-backed secrets, SQLite queue that commits
  each batch and the cursor in one transaction, uploader (oldest first, delete
  only after 2xx, gzip JSON, Cloudflare Access headers + bearer), WorkManager
  schedule (6 h window, 3 min scan; manual press scans 60 s) with an upload
  retry worker on network, status screen in words.
- Tested on the S22 without a ring: provisioning import, Keystore round trip,
  queueing, upload of 3 batches to a local receiver in order, queue retained
  while the relay is down and flushed when it returns, "Ring not paired" on
  Sync now, pair confirmation dialog.
- Not tested: pairing, bonded reconnect by identity address (the sync scan
  accepts adverts whose resolved address equals the bond address; unverified),
  a real drain, background scheduling under Doze, Cloudflare Access rejection
  text.
- The wire format at `POST <relay_url>/v1/batch` is provisional until the
  relay is built: gzip JSON `{batch_id, serial, cursor_before, cursor_after,
  events: [[tag, ts, hexbody], ...]}`, idempotent on `batch_id`.
- Build and provisioning steps: [../android/README.md](../android/README.md).

### Spike results, 2026-10-13 (S22, Android 16)

- **Reachability (spike 2, first reading):** worn, in an office with many BLE
  devices, ring not on the charger: 6 adverts in a 60 s low-power filtered
  scan, 1 to 19 s apart, -64 to -72 dBm. Adverts are connectable, legacy,
  carry the Oura service UUID, manufacturer data `0x02b2` `04602801`, and the
  name "Oura Ring 4". The private address rotated between attempts
  (`7A:`, `43:`, `51:`, `5F:`, `50:`). One short reading only.
- **Unbonded connect (spike 1a): fails.** Every attempt, 6 of 6, ended in GATT
  status 133 about 2 s after connecting. Matches upstream
  `docs/factory-reset.md`: a provisioned ring terminates a central that is
  neither bonded nor able to start encryption after 2-3 connection events.
  So the phone cannot reuse the HTPC's bond, and importing the auth key alone
  does not help: the BLE bond is the gate, not the key.
- **Consequence: the phone needs a factory reset first.** A reset erases the
  key, every bond and the on-ring event buffer; then the phone bonds (Android
  pairing) and installs the existing key with `set_auth_key`, so history stays
  attributable. Path A is `0x1a 0x00` sent by a bonded, authenticated host
  (the HTPC, via `oura factory-reset --yes`); path B is the dock procedure.
  Sequence: final HTPC sync, reset, phone pairs, cursor starts at 0 (the
  store dedupes on `UNIQUE(serial, tag, ring_timestamp, body)`).
  Never onboard with the Oura app afterwards.
