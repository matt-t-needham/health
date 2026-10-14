#!/usr/bin/env bash
# One-time host setup for health.bix.computer: everything outside the apps tree.
# Run as root (`sudo bash install-host.sh`); safe to rerun.
#
#   ~/health/data                      database dir (outside the apps tree, so
#                                      ai-router can't read health data)
#   bix-infra/logs/oura-sync           heartbeat stamp health-ingest rewrites
#   ~/health/relay/spool/{tmp,incoming,done,failed}  health-relay's spool
#   ~/.config/bix/health-relay.conf    device-secret hashes (provision-phone.sh adds them)
#   /etc/systemd/system/{health-relay.service,health-ingest.service,health-ingest.path}
#   /etc/systemd/system/health-relay.service.d/access.conf
#                                      HEALTH_RELAY_ACCESS_AUD, copied from bix-infra/.env
#
# Also removes what the retired host ring sync left behind (oura-sync
# service/timer, health-sync-trigger, its sudoers rule and token), if present.
# Enables and (re)starts health-relay and health-ingest.path, and reloads
# systemd, so a rerun picks up edited units.
set -euo pipefail

[[ $EUID -eq 0 ]] || { echo "run with sudo" >&2; exit 1; }
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
U=matt

install -d -o $U -g $U -m 700 /home/$U/health /home/$U/health/data
# health-ingest's ReadWritePaths: the heartbeat stamp (log-compiler). The path
# predates the phone; the old host oura-sync unit wrote it.
install -d -o $U -g $U -m 775 "$HERE/../bix-infra/logs/oura-sync"
# health-relay's spool (also bind-mounted into its otherwise empty /home).
install -d -o $U -g $U -m 700 /home/$U/health/relay /home/$U/health/relay/spool
for d in tmp incoming done failed; do
    install -d -o $U -g $U -m 700 /home/$U/health/relay/spool/$d
done
[[ -x $HERE/bin/health-ingest ]] || { echo "build bin/health-ingest first (CLAUDE.md, Commands)" >&2; exit 1; }

# Device-secret hashes. Empty until provision-phone.sh runs: every upload 401s.
RELAY_CONF=/home/$U/.config/bix/health-relay.conf
if [[ ! -e $RELAY_CONF ]]; then
    install -o $U -g $U -m 600 /dev/null "$RELAY_CONF"
    echo "created empty $RELAY_CONF"
fi

# The health.bix.computer Access app's AUD tag: only this one line of .env reaches the
# relay, as a unit drop-in. Not secret, but not printed either.
AUD=$(sed -n 's/^HEALTH_RELAY_ACCESS_AUD=["'"'"']\{0,1\}\([0-9a-f]\{64\}\)["'"'"']\{0,1\}[[:space:]]*$/\1/p' "$HERE/../bix-infra/.env")
[[ -n $AUD ]] || { echo "HEALTH_RELAY_ACCESS_AUD (64 hex) missing from bix-infra/.env" >&2; exit 1; }
install -d -m 755 /etc/systemd/system/health-relay.service.d
printf '[Service]\nEnvironment=HEALTH_RELAY_ACCESS_AUD=%s\n' "$AUD" > /etc/systemd/system/health-relay.service.d/access.conf
chmod 644 /etc/systemd/system/health-relay.service.d/access.conf

# Retired 2026-10-13: the phone app owns the ring, so the host sync, the Sync
# button's trigger daemon and its sudoers rule go. nginx answers /api/sync.
for u in oura-sync.timer oura-sync.service health-sync-trigger.service; do
    if [[ -e /etc/systemd/system/$u ]]; then
        systemctl disable --now "$u"
        rm /etc/systemd/system/$u
        echo "removed $u"
    fi
done
if [[ -e /etc/sudoers.d/health-sync ]]; then
    rm /etc/sudoers.d/health-sync
    echo "removed /etc/sudoers.d/health-sync"
fi
if [[ -e /home/$U/.config/bix/health-sync-token.conf ]]; then
    rm /home/$U/.config/bix/health-sync-token.conf
    echo "removed health-sync-token.conf (health-proxy no longer mounts it)"
fi

for f in health-relay.service health-ingest.service health-ingest.path; do
    install -m 644 -o root -g root "$HERE/systemd/$f" /etc/systemd/system/$f
done
systemctl daemon-reload
systemctl reset-failed oura-sync.service health-sync-trigger.service 2>/dev/null || true  # intentional: only clears leftover failed state of removed units
systemctl enable health-relay.service health-ingest.path
systemctl restart health-relay.service health-ingest.path
systemctl --no-pager --lines=3 status health-relay.service health-ingest.path

echo
echo "Done."
