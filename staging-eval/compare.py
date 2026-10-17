"""Score summary JSONs (staging_eval output) against reference hypnograms.

usage: compare.py <references dir> <summary.json>...

Every reference night present in a summary is scored; the last summary also gets a
confusion table (reference stage > ours, minutes). Epochs outside a window count as
wake on both sides, so a window that runs long or short is penalised.
"""
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

CODE = {1: "D", 2: "L", 3: "R", 4: "W"}


def load_refs(folder):
    refs = []
    for path in sorted(Path(folder).glob("*.json")):
        ref = json.loads(path.read_text())
        tz = timezone(timedelta(hours=ref["tz_hours"]))
        ts = lambda s: datetime.fromisoformat(s).replace(tzinfo=tz).timestamp()
        ref["start_u"], ref["end_u"] = ts(ref["start"]), ts(ref["end"])
        ref["blocks_u"] = [(s, ts(a), ts(b)) for s, a, b in ref["blocks"]]
        refs.append(ref)
    if not refs:
        sys.exit(f"no reference nights in {folder}")
    return refs


def ref_at(ref, t):
    if not ref["start_u"] <= t < ref["end_u"]:
        return "W"
    return next((s for s, a, b in ref["blocks_u"] if a <= t < b), "L")


def score(ref, night):
    st = night["stages_full"]
    ep = (night["end_unix"] - night["start_unix"]) / len(st)
    conf = {}
    t = min(ref["start_u"], night["start_unix"])
    while t < max(ref["end_u"], night["end_unix"]):
        i = int((t - night["start_unix"]) // ep)
        ours = CODE.get(st[i], "?") if 0 <= i < len(st) else "W"
        key = (ref_at(ref, t), ours)
        conf[key] = conf.get(key, 0) + 1
        t += 30
    total = sum(conf.values())
    agree = sum(v for (r, o), v in conf.items() if r == o) / total
    ref_wake = sum(v for (r, _), v in conf.items() if r == "W")
    return agree, conf.get(("W", "W"), 0) / max(1, ref_wake), conf


def main():
    refs = load_refs(sys.argv[1])
    conf = None
    for path in sys.argv[2:]:
        summary = json.loads(Path(path).read_text())
        label = Path(path).stem
        for ref in refs:
            night = next((n for n in summary["nights"]
                          if n["wake_ymd"] == ref["wake_ymd"] and n.get("stages_full")
                          and n["start_unix"] < ref["end_u"] and ref["start_u"] < n["end_unix"]), None)
            if night is None:
                print(f"{label:10} {ref['wake_ymd']}: no staged night overlaps the reference")
                continue
            m = night["metrics"]
            agree, wake_recall, conf = score(ref, night)
            print(f"{label:10} {ref['wake_ymd']} {night['start']}-{night['end']} bed {night['in_bed_h']}h "
                  f"asleep {m['asleep_min'] / 60:.2f}h eff {night['efficiency']}% SOL {m['sol_min']} "
                  f"WASO {m['waso_min']} deep {night['deep_pct']}% rem {night['rem_pct']}% "
                  f"REMlat {m['rem_latency_min']} cycles {m['cycles']} | agree {agree:.0%} "
                  f"wake-recall {wake_recall:.0%}")
    if conf:
        print("confusion, last night scored (ref>ours, min):",
              {f"{r}>{o}": v // 2 for (r, o), v in sorted(conf.items())})


if __name__ == "__main__":
    main()
