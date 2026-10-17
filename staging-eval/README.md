# staging-eval

Scores the dashboard's sleep staging against reference nights: nights when a
second ring, read by the official Oura app, was worn alongside ours. Oura's
staging is well validated but scored against someone else's baseline, so a
reference is a yardstick, not ground truth.

Code lives here; **reference nights and outputs live in `~/health/eval/`**
(outside the apps tree, like the rest of the health data).

```bash
./eval.sh deployed                  # the pin + ../patches, i.e. what is live
./eval.sh try ~/some/open_health    # a checkout you are editing (patches applied by you)
```

`eval.sh` takes a snapshot of `oura.db` (SQLite backup API; the live file is
WAL and can't be opened from a read-only mount), runs `staging_eval.rs` (the
dashboard's model-free path: `build_summary` with `stage_nights_from_signals`
as the runner) in `rust:1.90-bookworm`, writes `~/health/eval/<label>.json` and
prints `compare.py`'s score for every reference night. Run it before and after
a change to `patches/` and compare the lines.

## Adding a reference night

Write `~/health/eval/references/<wake date>.json` like `2026-10-17.json`:
the window (`start`, `end`), the app's headline numbers under `summary`, and
the hypnogram as `[stage, from, to]` blocks (`W`/`D`/`R`; everything else in
the window is light). Local times, with `tz_hours` for the offset; all
references must share one. Read times off the app's hypnogram screenshot and
note the precision in `source`.

## Reading the score

- **agree:** share of 30 s epochs (over the union of both windows; outside a
  window counts as wake) where our stage matches the reference.
- **wake-recall:** share of the reference's wake epochs we also scored wake.
- The confusion table shows where minutes went (`L>R` = reference light, we
  said REM).

2026-10-17 baseline: 53% agree, 32% wake-recall before
`sleep-window-and-wake.patch`; 58% / 81% after. REM and deep barely moved:
those rules (an 88-minute REM template gated on HRV, a deep quota of 10.5%)
need more reference nights before they are worth retuning. The wake link
(7 min) and onset quiet (10 min) were tuned on two nights.
