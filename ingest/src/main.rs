//! health-ingest: commit spooled phone batches into oura.db.
//!
//! health-relay (network-facing, never touches the database) writes each batch
//! the phone uploads to `$HEALTH_SPOOL/incoming/`. This drains that directory,
//! oldest cursor first, reproducing what `oura sync` (open_health 08e1b86
//! `cmd_sync`) did with the same batch on the HTPC:
//!
//! - events are rebuilt with `RingEvent::from_packet`, so names and decodes come
//!   from the pinned decoders, and committed with the cursor in one transaction
//!   (`Store::commit_batch`; inserts are `INSERT OR IGNORE`, so replays are
//!   harmless). The cursor is `max(stored, cursor_after)`: the phone owns the
//!   real one, this is display only, and a replay must not move it back;
//! - `anchor` becomes the synthetic phone `time_sync` (0x42) row;
//! - `device.firmware` refreshes the device row;
//! - `feature_modes` is written to `feature_modes.json` beside the database.
//!
//! Each file then moves to `done/`, or to `failed/` with one ERROR line naming
//! it (never deleted). A batch that added a new event rewrites `$HEALTH_STAMP`
//! (the nightly compiler's ring-data heartbeat). `done/` files older than 30
//! days are pruned. Exits non-zero if anything failed.
//!
//! The phone uploads a backlog back to back, and the path unit would start a
//! run per arrival, faster than systemd's start limit. So one run keeps
//! draining until `incoming/` has stayed empty for `SETTLE`.

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use anyhow::{anyhow, bail, Context, Result};
use oura_protocol::device::DeviceInfo;
use oura_protocol::events::{event_name, RingEvent};
use oura_protocol::protocol::Packet;
use oura_store::Store;
use serde::Deserialize;

const DONE_KEEP: Duration = Duration::from_secs(30 * 86400);
const SETTLE: Duration = Duration::from_secs(5);
const MAX_EVENTS: usize = 4096;
// The earliest plausible anchor wall-clock time (2020-01-01), as in health-relay.
const MIN_UNIX: u32 = 1_577_836_800;
const FEATURES: [&str; 7] = [
    "daytime_hr",
    "exercise_hr",
    "spo2",
    "resting_hr",
    "real_steps",
    "experimental",
    "cva_ppg",
];

#[derive(Deserialize, Debug)]
#[serde(deny_unknown_fields)]
struct Batch {
    batch_id: String,
    serial: String,
    cursor_before: u32,
    cursor_after: u32,
    events: Vec<(u8, u32, String)>,
    anchor: Option<Anchor>,
    device: Option<Device>,
    feature_modes: Option<BTreeMap<String, u8>>,
}

#[derive(Deserialize, Debug)]
#[serde(deny_unknown_fields)]
struct Anchor {
    ring_timestamp: u32,
    unix_time: u32,
}

#[derive(Deserialize, Debug)]
#[serde(deny_unknown_fields)]
struct Device {
    firmware: Option<String>,
    battery: Option<u8>,
}

struct Config {
    db: PathBuf,
    spool: PathBuf,
    stamp: PathBuf,
    serials: Vec<String>,
    settle: Duration,
}

fn env_var(name: &str) -> Result<String> {
    match std::env::var(name) {
        Ok(v) if !v.trim().is_empty() => Ok(v.trim().to_string()),
        _ => bail!("{name} not set"),
    }
}

/// Parse and re-check a spool file. health-relay validated it already; this
/// trusts nothing it reads from disk either.
fn parse(raw: &[u8], serials: &[String]) -> Result<Batch> {
    let b: Batch = serde_json::from_slice(raw).context("bad JSON")?;
    if !serials.iter().any(|s| s == &b.serial) {
        bail!("serial {:?} not allowed", b.serial);
    }
    if b.cursor_before > b.cursor_after {
        bail!("cursor_before {} > cursor_after {}", b.cursor_before, b.cursor_after);
    }
    if b.batch_id != format!("{}:{}:{}", b.serial, b.cursor_before, b.cursor_after) {
        bail!("batch_id {:?} doesn't match serial and cursors", b.batch_id);
    }
    if b.events.len() > MAX_EVENTS {
        bail!("{} events, over {MAX_EVENTS}", b.events.len());
    }
    if let Some(a) = &b.anchor {
        if a.ring_timestamp == 0 || a.unix_time < MIN_UNIX {
            bail!("implausible anchor {a:?}");
        }
    }
    if let Some(fm) = &b.feature_modes {
        if let Some(k) = fm.keys().find(|k| !FEATURES.contains(&k.as_str())) {
            bail!("unknown feature {k:?}");
        }
    }
    Ok(b)
}

fn ring_events(b: &Batch) -> Result<Vec<RingEvent>> {
    b.events
        .iter()
        .enumerate()
        .map(|(i, (tag, ts, body_hex))| {
            if body_hex.bytes().any(|c| c.is_ascii_uppercase()) {
                bail!("event {i}: body hex not lowercase");
            }
            let body = hex::decode(body_hex).with_context(|| format!("event {i}: bad body hex"))?;
            let mut payload = ts.to_le_bytes().to_vec();
            payload.extend_from_slice(&body);
            Ok(RingEvent::from_packet(&Packet::new(*tag, payload)))
        })
        .collect()
}

/// The synthetic `time_sync` row `oura sync` writes after each sync
/// (`phone_anchor_event`), built from the phone's anchor instead of the host clock.
fn anchor_event(a: &Anchor) -> RingEvent {
    let mut body = a.unix_time.to_le_bytes().to_vec();
    body.extend_from_slice(b"phone");
    RingEvent {
        tag: 0x42,
        name: event_name(0x42),
        timestamp: a.ring_timestamp,
        body,
        decoded: Some(serde_json::json!({ "unix_time": a.unix_time, "source": "phone" })),
    }
}

/// Same file and shape as `oura_summary::write_feature_modes` (`{feature: mode,
/// "_at": unix}`, pretty JSON beside the database), but written atomically and
/// with errors reported rather than dropped.
fn write_feature_modes(db: &Path, modes: &BTreeMap<String, u8>) -> Result<()> {
    let dir = db.parent().unwrap_or(Path::new("."));
    let mut m = serde_json::Map::new();
    for (k, v) in modes {
        m.insert(k.clone(), serde_json::json!(v));
    }
    m.insert("_at".into(), serde_json::json!(now_unix()));
    let tmp = dir.join(".feature_modes.json.tmp");
    fs::write(&tmp, serde_json::to_vec_pretty(&serde_json::Value::Object(m))?)
        .with_context(|| format!("writing {}", tmp.display()))?;
    fs::rename(&tmp, dir.join("feature_modes.json")).context("replacing feature_modes.json")?;
    Ok(())
}

struct Outcome {
    received: usize,
    inserted: u32,
    cursor: u32,
}

fn ingest(store: &Store, cfg: &Config, raw: &[u8]) -> Result<(String, Outcome)> {
    let b = parse(raw, &cfg.serials)?;
    let events = ring_events(&b)?;
    let cursor = store.cursor(&b.serial)?.max(b.cursor_after);
    let mut inserted = store
        .commit_batch(&b.serial, &events, cursor)
        .context("committing events")?;
    if let Some(a) = &b.anchor {
        inserted += u32::from(store.insert_event(&b.serial, &anchor_event(a)).context("saving phone anchor")?);
    }
    if let Some(d) = &b.device {
        if let Some(fw) = &d.firmware {
            // upsert_device overwrites every column it's given, so carry the
            // stored api_version / mac over rather than blanking them.
            let (api_version, mac) = match store.device_info()? {
                Some((s, _, _, api, mac, ..)) if s == b.serial => (api, mac),
                _ => (String::new(), String::new()),
            };
            let info = DeviceInfo {
                api_version,
                firmware_version: fw.clone(),
                bootloader_version: String::new(),
                bt_stack_version: String::new(),
                mac,
            };
            store.upsert_device(&b.serial, None, Some(&info)).context("updating device row")?;
        }
        if let Some(bat) = d.battery {
            // Logged only: the dashboard reads battery from the ring's own
            // debug_data events, which arrive in `events`.
            println!("  {} reports battery {bat}%", b.batch_id);
        }
    }
    if let Some(fm) = &b.feature_modes {
        write_feature_modes(&cfg.db, fm)?;
    }
    Ok((b.batch_id, Outcome { received: events.len(), inserted, cursor }))
}

fn now_unix() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

/// RFC 3339 UTC for the heartbeat stamp (the compiler reads its mtime; the
/// text is for people).
fn rfc3339_utc(secs: u64) -> String {
    let days = (secs / 86400) as i64;
    let rem = secs % 86400;
    // Howard Hinnant's civil_from_days.
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}Z", rem / 3600, rem % 3600 / 60, rem % 60)
}

fn stamp(path: &Path) -> Result<()> {
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, format!("{}\n", rfc3339_utc(now_unix())))?;
    fs::rename(&tmp, path)?;
    Ok(())
}

fn incoming(spool: &Path) -> Result<Vec<PathBuf>> {
    let mut files: Vec<PathBuf> = fs::read_dir(spool.join("incoming"))?
        .map(|e| e.map(|e| e.path()))
        .collect::<std::io::Result<_>>()?;
    files.retain(|p| p.extension().is_some_and(|x| x == "json"));
    // Names are serial_<cursor_before:010>_<cursor_after:010>.json: name order is cursor order.
    files.sort();
    Ok(files)
}

fn move_to(file: &Path, spool: &Path, dir: &str) -> Result<()> {
    let name = file.file_name().ok_or_else(|| anyhow!("no file name"))?;
    fs::rename(file, spool.join(dir).join(name))
        .with_context(|| format!("moving {} to {dir}/", file.display()))
}

fn prune_done(spool: &Path) -> Result<usize> {
    let mut n = 0;
    for e in fs::read_dir(spool.join("done"))? {
        let p = e?.path();
        let age = fs::metadata(&p)?.modified()?.elapsed().unwrap_or_default();
        if age > DONE_KEEP {
            fs::remove_file(&p).with_context(|| format!("pruning {}", p.display()))?;
            n += 1;
        }
    }
    Ok(n)
}

fn run(cfg: &Config) -> Result<usize> {
    let mut failed = 0;
    let mut store: Option<Store> = None;
    loop {
        let mut files = incoming(&cfg.spool)?;
        if files.is_empty() {
            // Wait out the gap between back-to-back uploads before exiting.
            std::thread::sleep(cfg.settle);
            files = incoming(&cfg.spool)?;
            if files.is_empty() {
                break;
            }
        }
        if store.is_none() {
            store = Some(Store::open(&cfg.db).with_context(|| format!("opening {}", cfg.db.display()))?);
        }
        let store = store.as_ref().expect("opened above");
        for f in &files {
            let name = f.file_name().unwrap_or_default().to_string_lossy().into_owned();
            let result = fs::read(f).context("reading").and_then(|raw| ingest(&store, cfg, &raw));
            match result {
                Ok((id, o)) => {
                    move_to(f, &cfg.spool, "done")?;
                    println!(
                        "ingested {id}: {} events, {} new rows, cursor {}",
                        o.received, o.inserted, o.cursor
                    );
                    if o.inserted > 0 {
                        stamp(&cfg.stamp).with_context(|| format!("stamping {}", cfg.stamp.display()))?;
                    }
                }
                Err(e) => {
                    failed += 1;
                    move_to(f, &cfg.spool, "failed")?;
                    println!("ERROR ingest failed for {name}, moved to failed/: {e:#}");
                }
            }
        }
    }
    let pruned = prune_done(&cfg.spool)?;
    if pruned > 0 {
        println!("pruned {pruned} done/ files older than 30 days");
    }
    Ok(failed)
}

fn main() -> std::process::ExitCode {
    let cfg = (|| -> Result<Config> {
        Ok(Config {
            db: env_var("HEALTH_DB")?.into(),
            spool: env_var("HEALTH_SPOOL")?.into(),
            stamp: env_var("HEALTH_STAMP")?.into(),
            serials: env_var("HEALTH_SERIALS")?.split(',').map(str::to_string).collect(),
            settle: SETTLE,
        })
    })();
    let result = cfg.and_then(|cfg| run(&cfg));
    match result {
        Ok(0) => std::process::ExitCode::SUCCESS,
        Ok(n) => {
            println!("ERROR {n} batch(es) moved to failed/");
            std::process::ExitCode::FAILURE
        }
        Err(e) => {
            println!("ERROR health-ingest: {e:#}");
            std::process::ExitCode::FAILURE
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SERIAL: &str = "2016082503005273";

    fn cfg(dir: &Path) -> Config {
        for d in ["incoming", "done", "failed"] {
            fs::create_dir_all(dir.join("spool").join(d)).unwrap();
        }
        Config {
            db: dir.join("oura.db"),
            spool: dir.join("spool"),
            stamp: dir.join("last-success"),
            serials: vec![SERIAL.into()],
            settle: Duration::ZERO,
        }
    }

    fn tmpdir(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("health-ingest-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    fn put(cfg: &Config, before: u32, after: u32, extra: &str) -> PathBuf {
        let p = cfg.spool.join("incoming").join(format!("{SERIAL}_{before:010}_{after:010}.json"));
        fs::write(
            &p,
            format!(
                r#"{{"batch_id":"{SERIAL}:{before}:{after}","serial":"{SERIAL}","cursor_before":{before},"cursor_after":{after},"events":[[65,{},"0a1b2c"],[72,{},""]]{extra}}}"#,
                before + 6,
                before + 7
            ),
        )
        .unwrap();
        p
    }

    fn rows(db: &Path) -> i64 {
        rusqlite::Connection::open(db)
            .unwrap()
            .query_row("SELECT COUNT(*) FROM events", [], |r| r.get(0))
            .unwrap()
    }

    #[test]
    fn commits_dedupes_and_never_moves_cursor_back() {
        let dir = tmpdir("commit");
        let cfg = cfg(&dir);
        put(&cfg, 2000, 3000, "");
        assert_eq!(run(&cfg).unwrap(), 0);
        assert_eq!(rows(&cfg.db), 2);
        assert!(cfg.stamp.exists());
        assert!(cfg.spool.join("done").join(format!("{SERIAL}_0000002000_0000003000.json")).exists());

        // A replay adds nothing and leaves the stamp alone.
        fs::remove_file(&cfg.stamp).unwrap();
        put(&cfg, 2000, 3000, "");
        assert_eq!(run(&cfg).unwrap(), 0);
        assert_eq!(rows(&cfg.db), 2);
        assert!(!cfg.stamp.exists());

        // An older batch arriving late doesn't move the cursor back.
        put(&cfg, 1000, 1500, "");
        assert_eq!(run(&cfg).unwrap(), 0);
        assert_eq!(Store::open(&cfg.db).unwrap().cursor(SERIAL).unwrap(), 3000);
    }

    #[test]
    fn anchor_device_and_feature_modes() {
        let dir = tmpdir("extras");
        let cfg = cfg(&dir);
        put(
            &cfg,
            2000,
            3000,
            r#","anchor":{"ring_timestamp":2999,"unix_time":1791950000},"device":{"firmware":"2.12.5","battery":98},"feature_modes":{"daytime_hr":1,"spo2":0}"#,
        );
        assert_eq!(run(&cfg).unwrap(), 0);
        let conn = rusqlite::Connection::open(&cfg.db).unwrap();
        let (ts, body, decoded): (i64, Vec<u8>, String) = conn
            .query_row(
                "SELECT ring_timestamp, body, decoded_json FROM events WHERE tag = 66",
                [],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .unwrap();
        assert_eq!(ts, 2999);
        let mut want = 1_791_950_000u32.to_le_bytes().to_vec();
        want.extend_from_slice(b"phone");
        assert_eq!(body, want);
        let d: serde_json::Value = serde_json::from_str(&decoded).unwrap();
        assert_eq!(d, serde_json::json!({"unix_time": 1791950000u32, "source": "phone"}));
        let fw: String = conn.query_row("SELECT firmware FROM device", [], |r| r.get(0)).unwrap();
        assert_eq!(fw, "2.12.5");
        let fm: serde_json::Value =
            serde_json::from_slice(&fs::read(dir.join("feature_modes.json")).unwrap()).unwrap();
        assert_eq!(fm["daytime_hr"], 1);
        assert_eq!(fm["spo2"], 0);
        assert!(fm["_at"].as_u64().unwrap() > 0);
    }

    #[test]
    fn bad_files_go_to_failed() {
        let dir = tmpdir("failed");
        let cfg = cfg(&dir);
        fs::write(cfg.spool.join("incoming/a.json"), b"{not json").unwrap();
        fs::write(
            cfg.spool.join("incoming/b.json"),
            r#"{"batch_id":"999:1:2","serial":"999","cursor_before":1,"cursor_after":2,"events":[]}"#,
        )
        .unwrap();
        put(&cfg, 2000, 3000, "");
        assert_eq!(run(&cfg).unwrap(), 2);
        assert!(cfg.spool.join("failed/a.json").exists());
        assert!(cfg.spool.join("failed/b.json").exists());
        assert_eq!(rows(&cfg.db), 2);
    }

    #[test]
    fn locked_database_fails_the_batch() {
        let dir = tmpdir("locked");
        let cfg = cfg(&dir);
        Store::open(&cfg.db).unwrap();
        let lock = rusqlite::Connection::open(&cfg.db).unwrap();
        lock.execute_batch("BEGIN EXCLUSIVE").unwrap();
        put(&cfg, 2000, 3000, "");
        assert_eq!(run(&cfg).unwrap(), 1);
        assert!(cfg.spool.join("failed").join(format!("{SERIAL}_0000002000_0000003000.json")).exists());
    }

    #[test]
    fn drains_files_that_arrive_while_running() {
        let dir = tmpdir("drain");
        let mut cfg = cfg(&dir);
        cfg.settle = Duration::from_millis(1500);
        put(&cfg, 2000, 3000, "");
        let late = {
            let incoming = cfg.spool.join("incoming");
            std::thread::spawn(move || {
                std::thread::sleep(Duration::from_millis(500));
                fs::write(
                    incoming.join(format!("{SERIAL}_0000003000_0000004000.json")),
                    format!(r#"{{"batch_id":"{SERIAL}:3000:4000","serial":"{SERIAL}","cursor_before":3000,"cursor_after":4000,"events":[[65,3006,"aa"]]}}"#),
                )
                .unwrap();
            })
        };
        assert_eq!(run(&cfg).unwrap(), 0);
        late.join().unwrap();
        assert_eq!(rows(&cfg.db), 3);
        assert!(fs::read_dir(cfg.spool.join("incoming")).unwrap().next().is_none());
    }

    #[test]
    fn stamp_format() {
        assert_eq!(rfc3339_utc(0), "1970-01-01T00:00:00Z");
        assert_eq!(rfc3339_utc(1_791_950_000), "2026-10-14T03:53:20Z");
    }
}
