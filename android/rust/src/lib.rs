//! JNI bridge between the Kotlin BLE layer and open_oura's sync logic.
//!
//! Kotlin owns the GATT link. It hands this library a `Bridge` object whose
//! `write(byte[])` sends one request frame, and feeds every notification back
//! through `nativePush`. `nativeSync` then runs the same sequence as
//! `oura sync` (authenticate, stream setup, clock sync, drain) and reports each
//! batch to the bridge's `onBatch`, which must persist it durably and return
//! true before the sync continues.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use async_trait::async_trait;
use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JValue};
use jni::sys::{jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};
use oura_link::client::OuraClient;
use oura_link::error::{Error, Result};
use oura_link::transport::Transport;
use oura_protocol::protocol::Packet;
use tokio::sync::broadcast;

static VM: OnceLock<JavaVM> = OnceLock::new();
static SESSIONS: OnceLock<Mutex<HashMap<i64, broadcast::Sender<Vec<u8>>>>> = OnceLock::new();
static STOPS: OnceLock<Mutex<HashMap<i64, Arc<AtomicBool>>>> = OnceLock::new();
static NEXT_ID: Mutex<i64> = Mutex::new(1);

fn stops() -> &'static Mutex<HashMap<i64, Arc<AtomicBool>>> {
    STOPS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn sessions() -> &'static Mutex<HashMap<i64, broadcast::Sender<Vec<u8>>>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

struct JniTransport {
    bridge: GlobalRef,
    tx: broadcast::Sender<Vec<u8>>,
}

#[async_trait]
impl Transport for JniTransport {
    async fn write(&self, data: &[u8]) -> Result<()> {
        let bridge = self.bridge.clone();
        let data = data.to_vec();
        // Kotlin's write blocks until the GATT write completes; keep that off
        // the async worker threads.
        tokio::task::spawn_blocking(move || {
            let vm = VM.get().ok_or_else(|| Error::Protocol("JVM not set".into()))?;
            let mut env = vm
                .attach_current_thread()
                .map_err(|e| Error::Protocol(format!("attach: {e}")))?;
            let arr = env
                .byte_array_from_slice(&data)
                .map_err(|e| Error::Protocol(format!("byte array: {e}")))?;
            let ok = env
                .call_method(&bridge, "write", "([B)Z", &[JValue::Object(&arr)])
                .and_then(|v| v.z())
                .map_err(|e| Error::Protocol(format!("bridge.write: {e}")))?;
            if ok {
                Ok(())
            } else {
                Err(Error::Protocol("BLE write failed".into()))
            }
        })
        .await
        .map_err(|e| Error::Protocol(format!("write task: {e}")))?
    }

    fn subscribe(&self) -> broadcast::Receiver<Vec<u8>> {
        self.tx.subscribe()
    }
}

#[no_mangle]
pub extern "system" fn Java_computer_bix_health_NativeSync_nativeOpen(
    env: JNIEnv,
    _class: JClass,
) -> jlong {
    if let Ok(vm) = env.get_java_vm() {
        let _ = VM.set(vm);
    }
    let (tx, _) = broadcast::channel(4096);
    let mut next = NEXT_ID.lock().unwrap();
    let id = *next;
    *next += 1;
    sessions().lock().unwrap().insert(id, tx);
    stops().lock().unwrap().insert(id, Arc::new(AtomicBool::new(false)));
    id
}

#[no_mangle]
pub extern "system" fn Java_computer_bix_health_NativeSync_nativeClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    sessions().lock().unwrap().remove(&handle);
    stops().lock().unwrap().remove(&handle);
}

/// Asks a running live session to stop; it restores the ring's feature mode on the way out.
#[no_mangle]
pub extern "system" fn Java_computer_bix_health_NativeSync_nativeStop(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if let Some(flag) = stops().lock().unwrap().get(&handle) {
        flag.store(true, Ordering::Relaxed);
    }
}

#[no_mangle]
pub extern "system" fn Java_computer_bix_health_NativeSync_nativePush(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    frame: JByteArray,
) {
    let Ok(bytes) = env.convert_byte_array(&frame) else { return };
    if let Some(tx) = sessions().lock().unwrap().get(&handle) {
        let _ = tx.send(bytes);
    }
}

fn call_bool(env: &mut JNIEnv, obj: &JObject, name: &str, sig: &str, args: &[JValue]) -> bool {
    env.call_method(obj, name, sig, args)
        .and_then(|v| v.z())
        .unwrap_or(false)
}

fn push_info(env: &mut JNIEnv, bridge: &JObject, key: &str, value: &str) {
    if let (Ok(k), Ok(v)) = (env.new_string(key), env.new_string(value)) {
        let _ = call_bool(
            env,
            bridge,
            "onInfo",
            "(Ljava/lang/String;Ljava/lang/String;)Z",
            &[JValue::Object(&k), JValue::Object(&v)],
        );
    }
}

/// Features to hold on the ring, matching the HTPC's feature_modes.json
/// (all automatic). Exercise HR is left alone: a reset ring has it off already, and
/// asking for OFF is refused with result 0x20.
const FEATURES: &[(&str, u8, u8)] = &[
    ("daytime_hr", 0x02, 1),
    ("spo2", 0x04, 1),
    ("resting_hr", 0x08, 1),
    ("real_steps", 0x0b, 1),
    ("cva_ppg", 0x0d, 1),
];

/// Applies [`FEATURES`]; returns "ok" or a list of the ones that failed.
async fn configure_features(client: &OuraClient<JniTransport>) -> String {
    let mut failed = Vec::new();
    for (name, id, mode) in FEATURES {
        if let Err(e) = client.set_feature_mode(*id, *mode).await {
            failed.push(format!("{name}: {e}"));
        }
    }
    if failed.is_empty() { "ok".into() } else { failed.join("; ") }
}

/// Runs one sync. Returns null on success, otherwise a one-line error message.
/// Blocks; call from a background thread.
#[no_mangle]
pub extern "system" fn Java_computer_bix_health_NativeSync_nativeSync(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key: JByteArray,
    cursor: jint,
    bridge: JObject,
) -> jstring {
    let result = run_sync(&mut env, handle, &key, cursor as u32, &bridge);
    match result {
        Ok(()) => std::ptr::null_mut(),
        Err(msg) => env
            .new_string(msg)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
    }
}

fn run_sync(
    env: &mut JNIEnv,
    handle: i64,
    key: &JByteArray,
    cursor: u32,
    bridge: &JObject,
) -> std::result::Result<(), String> {
    let key_bytes = env.convert_byte_array(key).map_err(|e| e.to_string())?;
    let key: [u8; 16] = key_bytes
        .try_into()
        .map_err(|_| "ring key must be 16 bytes".to_string())?;
    let tx = sessions()
        .lock()
        .unwrap()
        .get(&handle)
        .cloned()
        .ok_or("unknown session handle")?;
    let bridge_ref = env.new_global_ref(bridge).map_err(|e| e.to_string())?;
    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_time()
        .build()
        .map_err(|e| e.to_string())?;

    let client = OuraClient::new(JniTransport { bridge: bridge_ref, tx });
    // The callbacks run on this thread (drain_events awaits them inline), so the
    // JNIEnv can be used directly. The batch buffer mirrors `oura sync`: events
    // are collected and handed to Kotlin together with the cursor, so a batch is
    // either fully stored or not at all.
    let pending: std::cell::RefCell<Vec<(u8, u32, Vec<u8>)>> = Default::default();
    let env_cell = std::cell::RefCell::new(env);

    rt.block_on(async {
        client.authenticate(&key).await.map_err(|e| format!("auth: {e}"))?;
        client.setup_app_stream().await.map_err(|e| format!("stream setup: {e}"))?;
        if client.sync_time_app().await.is_err() {
            let _ = client.sync_time().await;
        }
        let _ = client.check_sleep_analysis(false).await;
        let serial = client.serial().await.unwrap_or_else(|_| "unknown".into());
        {
            let mut env = env_cell.borrow_mut();
            let s = env.new_string(&serial).map_err(|e| e.to_string())?;
            if !call_bool(&mut env, bridge, "onSerial", "(Ljava/lang/String;)Z", &[JValue::Object(&s)]) {
                return Err(format!("serial {serial} is not the expected ring"));
            }
        }
        if let Ok(b) = client.battery().await {
            push_info(&mut env_cell.borrow_mut(), bridge, "battery", &b.percent.to_string());
        }
        if let Ok(f) = client.firmware().await {
            push_info(&mut env_cell.borrow_mut(), bridge, "firmware", &f.firmware_version);
        }
        // Idempotent; repairs a feature left in live mode by an interrupted session.
        let features = configure_features(&client).await;
        push_info(&mut env_cell.borrow_mut(), bridge, "features", &features);
        // Snapshot the real modes, as `oura sync` did for the dashboard's feature_modes.json.
        let mut modes = Vec::new();
        for (id, name) in [
            (0x02u8, "daytime_hr"),
            (0x03, "exercise_hr"),
            (0x04, "spo2"),
            (0x08, "resting_hr"),
            (0x0b, "real_steps"),
            (0x0c, "experimental"),
            (0x0d, "cva_ppg"),
        ] {
            if let Ok(st) = client.feature_status(id).await {
                modes.push(format!("\"{name}\":{}", st.mode));
            }
        }
        if !modes.is_empty() {
            push_info(&mut env_cell.borrow_mut(), bridge, "feature_modes", &format!("{{{}}}", modes.join(",")));
        }

        client
            .drain_events(
                cursor,
                |ev| {
                    pending.borrow_mut().push((ev.tag, ev.timestamp, ev.body.clone()));
                    true
                },
                |p| {
                    let events = std::mem::take(&mut *pending.borrow_mut());
                    let mut env = env_cell.borrow_mut();
                    let n = events.len() as i32;
                    let (Ok(tags), Ok(stamps), Ok(bodies)) = (
                        env.new_byte_array(n),
                        env.new_int_array(n),
                        env.new_object_array(
                            n,
                            "[B",
                            JObject::null(),
                        ),
                    ) else {
                        return false;
                    };
                    let tag_vals: Vec<i8> = events.iter().map(|e| e.0 as i8).collect();
                    let stamp_vals: Vec<i32> = events.iter().map(|e| e.1 as i32).collect();
                    if env.set_byte_array_region(&tags, 0, &tag_vals).is_err()
                        || env.set_int_array_region(&stamps, 0, &stamp_vals).is_err()
                    {
                        return false;
                    }
                    for (i, e) in events.iter().enumerate() {
                        let Ok(b) = env.byte_array_from_slice(&e.2) else { return false };
                        if env.set_object_array_element(&bodies, i as i32, &b).is_err() {
                            return false;
                        }
                    }
                    call_bool(
                        &mut env,
                        bridge,
                        "onBatch",
                        "([B[I[[BII)Z",
                        &[
                            JValue::Object(&tags),
                            JValue::Object(&stamps),
                            JValue::Object(&bodies),
                            JValue::Int(p.next_cursor as i32),
                            JValue::Int(p.bytes_left as i32),
                        ],
                    )
                },
            )
            .await
            .map(|_| ())
            .map_err(|e| format!("sync: {e}"))
    })
}

/// Pairs with a factory-reset ring: checks the serial, installs `key`, then proves it
/// by authenticating. Returns null on success, otherwise a one-line error.
#[no_mangle]
pub extern "system" fn Java_computer_bix_health_NativeSync_nativePair(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key: JByteArray,
    bridge: JObject,
) -> jstring {
    let result = run_pair(&mut env, handle, &key, &bridge);
    match result {
        Ok(()) => std::ptr::null_mut(),
        Err(msg) => env
            .new_string(msg)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
    }
}

fn run_pair(
    env: &mut JNIEnv,
    handle: i64,
    key: &JByteArray,
    bridge: &JObject,
) -> std::result::Result<(), String> {
    let key_bytes = env.convert_byte_array(key).map_err(|e| e.to_string())?;
    let key: [u8; 16] = key_bytes
        .try_into()
        .map_err(|_| "ring key must be 16 bytes".to_string())?;
    let tx = sessions()
        .lock()
        .unwrap()
        .get(&handle)
        .cloned()
        .ok_or("unknown session handle")?;
    let bridge_ref = env.new_global_ref(bridge).map_err(|e| e.to_string())?;
    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_time()
        .build()
        .map_err(|e| e.to_string())?;
    let client = OuraClient::new(JniTransport { bridge: bridge_ref, tx });

    rt.block_on(async {
        let serial = client.serial().await.map_err(|e| format!("serial: {e}"))?;
        let s = env.new_string(&serial).map_err(|e| e.to_string())?;
        if !call_bool(env, bridge, "onSerial", "(Ljava/lang/String;)Z", &[JValue::Object(&s)]) {
            return Err(format!("serial {serial} is not the expected ring; key not installed"));
        }
        client
            .set_auth_key(&key)
            .await
            .map_err(|e| format!("set_auth_key (is the ring factory-reset?): {e}"))?;
        client.authenticate(&key).await.map_err(|e| format!("verifying auth: {e}"))?;
        let features = configure_features(&client).await;
        push_info(env, bridge, "features", &features);
        if let Ok(b) = client.battery().await {
            push_info(env, bridge, "battery", &b.percent.to_string());
        }
        if let Ok(f) = client.firmware().await {
            push_info(env, bridge, "firmware", &f.firmware_version);
        }
        Ok(())
    })
}

/// Streams live heart rate for up to `seconds`, calling `bridge.onBeat(bpm, ibi)`.
/// Returns null on success (including a requested stop), otherwise a one-line error.
#[no_mangle]
pub extern "system" fn Java_computer_bix_health_NativeSync_nativeLiveHr(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    key: JByteArray,
    seconds: jint,
    bridge: JObject,
) -> jstring {
    let result = run_live(&mut env, handle, &key, seconds.max(1) as u64, &bridge);
    match result {
        Ok(()) => std::ptr::null_mut(),
        Err(msg) => env
            .new_string(msg)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
    }
}

fn run_live(
    env: &mut JNIEnv,
    handle: i64,
    key: &JByteArray,
    seconds: u64,
    bridge: &JObject,
) -> std::result::Result<(), String> {
    let key_bytes = env.convert_byte_array(key).map_err(|e| e.to_string())?;
    let key: [u8; 16] = key_bytes
        .try_into()
        .map_err(|_| "ring key must be 16 bytes".to_string())?;
    let tx = sessions().lock().unwrap().get(&handle).cloned().ok_or("unknown session handle")?;
    let stop = stops().lock().unwrap().get(&handle).cloned().ok_or("unknown session handle")?;
    stop.store(false, Ordering::Relaxed);
    let bridge_ref = env.new_global_ref(bridge).map_err(|e| e.to_string())?;
    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_time()
        .build()
        .map_err(|e| e.to_string())?;
    let client = OuraClient::new(JniTransport { bridge: bridge_ref, tx });
    let env_cell = std::cell::RefCell::new(env);

    rt.block_on(async {
        client.authenticate(&key).await.map_err(|e| format!("auth: {e}"))?;
        // The official app (and our sync) run this before asking the ring to push live events.
        let setup = match client.setup_app_stream().await {
            Ok(()) => "stream setup ok".to_string(),
            Err(e) => format!("stream setup failed: {e}"),
        };
        push_info(&mut env_cell.borrow_mut(), bridge, "note", &setup);
        push_info(&mut env_cell.borrow_mut(), bridge, "stage", "streaming");
        // The official app pairs live mode with a "latest" subscription on daytime HR
        // (upstream docs/ring-5-observations.md: `2f03220203` + `2f03260202`).
        // open_oura's live_heart_rate only sends the first, and the ring stays silent.
        let mut rx = client.transport().subscribe();
        let mode = match client.set_feature_mode(0x02, 3).await {
            Ok(()) => "ok".to_string(),
            Err(e) => e.to_string(),
        };
        push_info(&mut env_cell.borrow_mut(), bridge, "note", &format!("live mode: {mode}"));
        let sub = match client.set_feature_subscription(0x02, 0x02).await {
            Ok(0) => "ok".to_string(),
            Ok(r) => format!("result {r:#04x}"),
            Err(e) => e.to_string(),
        };
        push_info(&mut env_cell.borrow_mut(), bridge, "note", &format!("HR subscription: {sub}"));

        let deadline = tokio::time::Instant::now() + Duration::from_secs(seconds);
        let mut unrecognised = 0;
        let mut sources: Vec<u8> = Vec::new();
        while !stop.load(Ordering::Relaxed) {
            let now = tokio::time::Instant::now();
            if now >= deadline {
                break;
            }
            let slice = (deadline - now).min(Duration::from_millis(250));
            match tokio::time::timeout(slice, rx.recv()).await {
                Ok(Ok(frame)) => {
                    let mut beat = false;
                    for p in Packet::parse_many(&frame) {
                        let Some((source, ibi)) = ibi_from_packet(&p) else { continue };
                        let Some(bpm) = bpm_from_ibi(ibi) else { continue };
                        beat = true;
                        let mut env = env_cell.borrow_mut();
                        if !sources.contains(&source) {
                            sources.push(source);
                            push_info(&mut env, bridge, "note", &format!("beats arriving as ext {source:#04x}"));
                        }
                        call_bool(&mut env, bridge, "onBeat", "(II)Z", &[JValue::Int(bpm as i32), JValue::Int(ibi as i32)]);
                    }
                    // Log what the ring sends that we don't decode, to see the real live format.
                    if !beat && unrecognised < 20 {
                        unrecognised += 1;
                        let hex: String = frame.iter().map(|b| format!("{b:02x}")).collect();
                        push_info(&mut env_cell.borrow_mut(), bridge, "frame", &hex);
                    }
                }
                Ok(Err(broadcast::error::RecvError::Lagged(_))) => continue,
                Ok(Err(_)) => break,
                Err(_) => continue,
            }
        }
        let _ = client.set_feature_subscription(0x02, 0x00).await;
        let _ = client.set_feature_mode(0x02, 1).await;
        Ok(())
    })
}

/// Beat interval from a daytime-HR packet: the live stream (ext `0x28`, IBI with a
/// validity nibble) or a pushed latest value (ext `0x25`, IBI at data offset 0).
/// Returns the ext tag it came from with the IBI in ms.
fn ibi_from_packet(p: &Packet) -> Option<(u8, u16)> {
    if p.tag != 0x2f {
        return None;
    }
    let d = &p.payload;
    match (d.first().copied(), d.get(1).copied()) {
        (Some(0x28), Some(0x02)) if d.len() >= 8 => {
            let (lo, hi) = (d[6], d[7]);
            if (hi >> 4) & 0x0f != 1 {
                return None;
            }
            Some((0x28, (((hi & 0x0f) as u16) << 8) | lo as u16))
        }
        (Some(0x25), Some(0x02)) if d.len() >= 9 => Some((0x25, u16::from_le_bytes([d[7], d[8]]))),
        _ => None,
    }
}

fn bpm_from_ibi(ibi_ms: u16) -> Option<u16> {
    (300..=2000).contains(&ibi_ms).then(|| (60_000u32 / ibi_ms as u32) as u16)
}
