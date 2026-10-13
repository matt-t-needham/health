# Android companion

Phone app that holds the only bond to the Oura ring, drains it, and pushes the
raw events to the HTPC relay. Design and status: [../docs/android-design.md](../docs/android-design.md).

| Path | What |
|------|------|
| `rust/` | `ouralink` JNI crate over open_oura's `oura-link` (auth, drain, pair), pinned to the same rev as the Dockerfile |
| `app/` | Kotlin app: `RingLink` (GATT), `SessionBridge`/`SyncRunner`/`Pairer`, `Store` (local queue), `Uploader`, `Secrets` (Keystore), workers |
| `app/src/debug/` | Debug-only: cleartext to `localhost` and a broadcast that queues a fake batch |

## Build and install

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
(cd rust && cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release)
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The wrapper pins Gradle 8.14.3 (AGP 8.x does not load on Gradle 9.6+).

## Provisioning

Secrets reach the phone once, as `files/provision.json`, which the app imports
into Keystore-backed storage and deletes. Keep the ring key off this Mac's disk
by piping it straight from the HTPC:

```bash
ssh home 'python3 -c "import json;print(json.dumps({\"ring_serial\":\"2016082503005273\",\"ring_key\":open(\"/home/matt/.config/bix/oura-ring.key\").read().strip()}))"' \
  | adb shell "run-as computer.bix.health sh -c 'cat > files/provision.json'"
```

Relay values (`relay_url`, `device_secret`, `cf_client_id`, `cf_client_secret`)
go in the same file once the relay exists. Open the app to import it.

## Pairing

Needs a factory-reset ring (see `../docs/android-design.md`). In the app: Pair
ring, with the ring held against the phone. Pairing refuses any ring whose
serial differs from `ring_serial`, before the key is installed.
