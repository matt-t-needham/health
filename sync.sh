#!/usr/bin/env bash
# ExecStart of oura-sync.service: connect to the ring and drain its history,
# retrying with a fresh connection until SYNC_DEADLINE_S runs out.
#
# - Each round: ring_connect.py (waits for a real advert, connects while
#   discovery runs; see its docstring), then `oura sync` reusing that link.
# - A success stamps $STAMP; the nightly log-compiler raises an error when the
#   stamp is older than 24 h (services.toml `heartbeat` source), which also
#   catches this unit or its timer never running.
# - A run that doesn't sync exits non-zero (the unit fails). Its last line
#   says how long it's been since the last successful sync: WARN while that's
#   under 24 h, ERROR after.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ADDR=A0:38:F8:41:98:18
PLATFORM_ID="hci0/dev_${ADDR//:/_}"
DB=/home/matt/health/data/oura.db
KEY=/home/matt/.config/bix/oura-ring.key
STAMP=${SYNC_STAMP:-/home/matt/apps/bix-infra/logs/oura-sync/last-success}
DEADLINE_S=${SYNC_DEADLINE_S:-900}
STALE_H=24
# oura's tracing output is coloured even into the journal; an escape code
# glued to "ERROR" defeats the registry's \bERROR\b flag.
export NO_COLOR=1

start=$(date +%s)
end=$((start + DEADLINE_S))
round=0

since_last() {
    if [[ ! -f $STAMP ]]; then
        echo "never"
        return
    fi
    local age=$(( $(date +%s) - $(stat -c %Y "$STAMP") ))
    printf '%d.%d h ago (%s)' $((age / 3600)) $((age % 3600 * 10 / 3600)) "$(head -c 40 "$STAMP")"
}

stale() {
    [[ ! -f $STAMP ]] || (( $(date +%s) - $(stat -c %Y "$STAMP") > STALE_H * 3600 ))
}

while :; do
    left=$((end - $(date +%s)))
    (( left < 30 )) && break
    round=$((round + 1))
    echo "round $round: connecting (${left} s left)"
    python3 -I "$HERE/ring_connect.py" "$ADDR" "$left"
    rc=$?
    case $rc in
        0) ;;
        3) break ;;                     # not reachable before the deadline; logged by ring_connect.py
        2) echo "ERROR sync can't run: setup problem above; last successful sync $(since_last)"
           exit 2 ;;
        *) echo "ERROR ring_connect.py crashed (exit $rc); last successful sync $(since_last)"
           exit 1 ;;
    esac
    if "$HERE/bin/oura" --db "$DB" --key-file "$KEY" --name "" --address "$PLATFORM_ID" \
            --scan-timeout 15 sync; then
        date -Is > "$STAMP.tmp" && mv "$STAMP.tmp" "$STAMP"
        echo "sync complete on round $round, $(( $(date +%s) - start )) s after start"
        exit 0
    fi
    echo "WARN round $round: oura sync didn't complete (output above); retrying with a fresh connection"
    sleep 5
done

if stale; then
    echo "ERROR no successful ring sync in over ${STALE_H} h: last $(since_last); this run made $round round(s) in ${DEADLINE_S} s"
else
    echo "WARN no sync this run ($round round(s) in ${DEADLINE_S} s); last successful sync $(since_last)"
fi
exit 1
