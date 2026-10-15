#!/usr/bin/env bash
# Provision the phone app for health-relay: make a new device secret, record
# only its SHA-256 hash in ~/.config/bix/health-relay.conf, and print the
# app's provisioning JSON to stdout, meant to be piped straight into adb so the
# plaintext never lands on disk. Run from the Mac with the phone plugged in:
#
#   ssh -T matt@192.168.0.47 '~/apps/health/relay/provision-phone.sh' \
#     | adb shell "run-as computer.bix.health sh -c 'cat > files/provision.json'"
#
# Then open the app. Needs ~/.config/bix/health-relay-cf.conf (0600), pasted
# once from the Cloudflare service token:
#   CF_ACCESS_CLIENT_ID=<id>.access
#   CF_ACCESS_CLIENT_SECRET=<secret>
#
# Each run adds a hash line labelled with the date; a run whose output never
# reached the phone leaves an unused line, which is safe to delete. Revoking a
# phone means deleting its line (health-relay rereads the file per request).
set -euo pipefail

CONF=/home/matt/.config/bix/health-relay.conf
CF=/home/matt/.config/bix/health-relay-cf.conf
RELAY_URL=https://health.bix.computer/relay

if [[ -t 1 ]]; then
    echo "refusing to print the device secret to a terminal; pipe it into adb (see the header)" >&2
    exit 1
fi
[[ -f $CF ]] || { echo "missing $CF (Cloudflare service token, see header)" >&2; exit 1; }
[[ $(stat -c %a "$CF") == 600 ]] || { echo "$CF must be mode 600" >&2; exit 1; }

python3 -I - "$CONF" "$CF" "$RELAY_URL" <<'EOF'
import base64, datetime, hashlib, json, os, re, secrets, sys

conf, cf, url = sys.argv[1:]
vals = dict(re.findall(r"^\s*(CF_ACCESS_CLIENT_ID|CF_ACCESS_CLIENT_SECRET)\s*=\s*(\S+)\s*$",
                       open(cf).read(), re.M))
missing = {"CF_ACCESS_CLIENT_ID", "CF_ACCESS_CLIENT_SECRET"} - set(vals)
if missing:
    sys.exit(f"{cf}: missing {', '.join(sorted(missing))}")

secret = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode()
line = f"{hashlib.sha256(secret.encode()).hexdigest()}  phone {datetime.date.today()}\n"
fd = os.open(conf, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
with os.fdopen(fd, "a") as f:
    f.write(line)
    f.flush()
    os.fsync(f.fileno())
os.chmod(conf, 0o600)

json.dump({"relay_url": url, "device_secret": secret,
           "cf_client_id": vals["CF_ACCESS_CLIENT_ID"],
           "cf_client_secret": vals["CF_ACCESS_CLIENT_SECRET"]}, sys.stdout)
sys.stdout.write("\n")
print(f"added device hash to {conf}", file=sys.stderr)
EOF
