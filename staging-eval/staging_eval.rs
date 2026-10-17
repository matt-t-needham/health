//! The dashboard's model-free path: build_summary with the signal stager as the runner.
use oura_summary::{ModelInputs, ModelOutputs, ModelRunner};
use std::path::Path;

struct SignalRunner;
impl ModelRunner for SignalRunner {
    fn run(&self, input: ModelInputs) -> ModelOutputs {
        ModelOutputs {
            sleep_batch: oura_summary::stage_nights_from_signals(input.db, input.sleep_ranges),
            ..Default::default()
        }
    }
}

fn main() {
    let mut args = std::env::args().skip(1);
    let db = args.next().expect("usage: staging_eval <oura.db> <tz>");
    let tz: f64 = args.next().and_then(|v| v.parse().ok()).unwrap_or(0.0);
    let v = oura_summary::build_summary(Path::new(&db), tz, &SignalRunner).expect("summary");
    println!("{}", serde_json::to_string(&v).unwrap());
}
