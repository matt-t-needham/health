#!/usr/bin/env bash
# One-time host setup for health.bix.computer: everything outside the apps tree.
# Run as root (`sudo bash install-host.sh`); safe to rerun.
#
#   ~/health/data                      database dir (outside the apps tree, so
#                                      ai-router can't read health data)
#   ~/.config/bix/health-sync-token.conf  shared token, health-proxy <-> trigger
#   /etc/sudoers.d/health-sync         matt may start oura-sync.service, only
#   /etc/systemd/system/{oura-sync.service,oura-sync.timer,health-sync-trigger.service}
#   bix-infra/logs/oura-sync           sync success stamp (must exist for the unit)
#
# Enables and starts health-sync-trigger and reloads systemd, so a rerun picks
# up edited units. Does NOT enable oura-sync.timer: the ring must be paired
# first (see CLAUDE.md), or every run fails.
set -euo pipefail

[[ $EUID -eq 0 ]] || { echo "run with sudo" >&2; exit 1; }
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
U=matt

install -d -o $U -g $U -m 700 /home/$U/health /home/$U/health/data
# oura-sync.service's ReadWritePaths: its success stamp (log-compiler heartbeat).
install -d -o $U -g $U -m 775 "$HERE/../bix-infra/logs/oura-sync"

# Token: nginx `set` line, the deploy-api pattern. 644 because the
# capability-limited nginx root can't bypass file modes; the 700 dir guards it.
TOK=/home/$U/.config/bix/health-sync-token.conf
if [[ ! -s $TOK ]]; then
    umask 077
    printf 'set $health_sync_token "%s";\n' "$(python3 -c 'import secrets; print(secrets.token_hex(32))')" > "$TOK"
    chown $U:$U "$TOK"
    chmod 644 "$TOK"
    echo "token written to $TOK"
else
    echo "token already present, kept"
fi

visudo -cf "$HERE/systemd/health-sync.sudoers"
install -m 440 -o root -g root "$HERE/systemd/health-sync.sudoers" /etc/sudoers.d/health-sync

for f in oura-sync.service oura-sync.timer health-sync-trigger.service; do
    install -m 644 -o root -g root "$HERE/systemd/$f" /etc/systemd/system/$f
done
systemctl daemon-reload
systemctl enable health-sync-trigger.service
systemctl restart health-sync-trigger.service
systemctl --no-pager --lines=3 status health-sync-trigger.service

echo
if systemctl is-enabled --quiet oura-sync.timer; then
    echo "Done. oura-sync.timer is enabled; if its schedule changed: sudo systemctl restart oura-sync.timer"
else
    echo "Done. After pairing the ring: sudo systemctl enable --now oura-sync.timer"
fi
