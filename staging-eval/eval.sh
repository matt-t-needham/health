#!/bin/sh
# Score the dashboard's sleep staging against the reference nights in
# ~/health/eval/references (see README.md).
#
#   eval.sh <label> [<open_health checkout>]
#
# Without a checkout it builds the pin from ../Dockerfile with ../patches applied,
# i.e. what is deployed. With one (e.g. a clone you are editing) it builds that.
# Reads a snapshot of oura.db, never the live file; output lands in ~/health/eval.
set -eu
label=$1
here=$(cd "$(dirname "$0")" && pwd)
eval_dir=$HOME/health/eval
work=$eval_dir/work
mkdir -p "$work/db" "$work/cargo"
chmod 700 "$eval_dir"

if [ $# -ge 2 ]; then
  src=$(cd "$2" && pwd)
else
  rev=$(sed -n 's/^ARG OPEN_HEALTH_REV=//p' "$here/../Dockerfile")
  src=$work/src
  if [ ! -d "$src/.git" ]; then
    git clone -q https://github.com/Th0rgal/open_health "$src"
  fi
  git -C "$src" fetch -q origin
  git -C "$src" checkout -q --force --detach "$rev"
  git -C "$src" clean -qfd
  git -C "$src" apply "$here"/../patches/*.patch
fi
cp "$here/staging_eval.rs" "$src/crates/oura-summary/examples/staging_eval.rs"

# A consistent copy via SQLite's backup API: the live db is in WAL mode, which a
# read-only mount can't open.
python3 -I -c "
import sqlite3, sys
src = sqlite3.connect('file:$HOME/health/data/oura.db?mode=ro', uri=True)
dst = sqlite3.connect('$work/db/oura.db')
src.backup(dst)
dst.close()
"
tz=$(python3 -I -c "
import json, pathlib
tzs = {json.loads(p.read_text())['tz_hours'] for p in pathlib.Path('$eval_dir/references').glob('*.json')}
if len(tzs) != 1:
    raise SystemExit(f'references need one shared tz_hours, found {sorted(tzs)}')
print(tzs.pop())
")

docker run --rm -v "$src":/src -v "$work/db":/data -v "$work/cargo":/cargo \
  -e CARGO_HOME=/cargo -w /src --user "$(id -u):$(id -g)" rust:1.90-bookworm \
  cargo run -q --release --locked -p oura-summary --example staging_eval -- /data/oura.db "$tz" \
  > "$eval_dir/$label.json"
python3 -I "$here/compare.py" "$eval_dir/references" "$eval_dir/$label.json"
