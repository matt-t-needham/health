"""Bring up the BlueZ link to the paired ring so `oura` can reuse it.

Why this exists (measured 2026-10-11, see CLAUDE.md "Connecting"): this
adapter can't resolve the ring's rotating private address in hardware, so a
connect to the bonded identity only completes while discovery is running, on
the ring's next received advertisement. Reception here is lossy: gaps between
received adverts ranged 1-23 s, and Connect() returned 7-21 s after it was
called. A fixed "scan N s, connect, wait M s" therefore succeeds or fails on
luck; this waits for an actual advert, calls Connect() with a long timeout
while discovery keeps running, and retries until the deadline.

Usage: python3 -I ring_connect.py <identity address> <deadline seconds>
Exit 0 = connected with services resolved; 3 = not connected by the deadline;
2 = setup problem (no adapter, ring not paired). Anything else is a crash.
"""
import sys
import time

import dbus
import dbus.mainloop.glib
from gi.repository import GLib

BLUEZ = "org.bluez"
DEVICE = "org.bluez.Device1"
ADAPTER = "org.bluez.Adapter1"
PROPS = "org.freedesktop.DBus.Properties"
CONNECT_TIMEOUT_S = 45      # one Connect() call; measured completions were 7-21 s
RESOLVE_TIMEOUT_S = 20      # wait for GATT services after the link is up
RETRY_PAUSE_S = 3
NOT_CONNECTED = 3


def log(msg: str) -> None:
    print(msg, flush=True)


class Connector:
    def __init__(self, bus: dbus.SystemBus, addr: str, deadline_s: float):
        self.bus = bus
        self.addr = addr
        self.path = "/org/bluez/hci0/dev_" + addr.replace(":", "_")
        self.deadline = time.monotonic() + deadline_s
        self.loop = GLib.MainLoop()
        self.attempt = 0
        self.adverts = 0
        self.connecting = False
        self.result = NOT_CONNECTED
        obj = bus.get_object(BLUEZ, self.path)
        self.dev = dbus.Interface(obj, DEVICE)
        self.dev_props = dbus.Interface(obj, PROPS)
        ad_obj = bus.get_object(BLUEZ, "/org/bluez/hci0")
        self.adapter = dbus.Interface(ad_obj, ADAPTER)
        self.adapter_props = dbus.Interface(ad_obj, PROPS)

    def prop(self, name: str):
        return self.dev_props.Get(DEVICE, name)

    def remaining(self) -> float:
        return self.deadline - time.monotonic()

    def run(self) -> int:
        if not bool(self.adapter_props.Get(ADAPTER, "Powered")):
            log("WARN bluetooth adapter was powered off; powering it on")
            try:
                self.adapter_props.Set(ADAPTER, "Powered", dbus.Boolean(True))
            except dbus.DBusException as e:
                log(f"ERROR can't power on the bluetooth adapter ({e.get_dbus_message()}); "
                    "if it's soft-blocked: rfkill unblock bluetooth")
                return 2
            time.sleep(2)
        if not bool(self.prop("Paired")):
            log(f"ERROR ring {self.addr} is no longer paired with this host; re-pair it (see health/CLAUDE.md)")
            return 2
        if bool(self.prop("Connected")):
            # A leftover link can sit idle with the ring no longer answering
            # (auth then gets "no nonce response"), so always start fresh.
            log("ring has a leftover link; disconnecting it first")
            self.dev.Disconnect()
            time.sleep(2)

        self.bus.add_signal_receiver(self.on_props, dbus_interface=PROPS,
                                     signal_name="PropertiesChanged", path=self.path)
        self.adapter.SetDiscoveryFilter({"Transport": "le", "DuplicateData": dbus.Boolean(True)})
        self.adapter.StartDiscovery()
        log(f"scanning for ring {self.addr} (up to {int(self.remaining())} s)")
        GLib.timeout_add(500, self.check_deadline)
        try:
            self.loop.run()
        finally:
            try:
                self.adapter.StopDiscovery()
            except dbus.DBusException as e:
                # Discovery may already have stopped (adapter reset); the
                # outcome below doesn't depend on it, but say so.
                log(f"note: StopDiscovery: {e.get_dbus_message()}")
        return self.result

    def check_deadline(self) -> bool:
        if self.result == 0:
            return False
        if self.remaining() <= 0:
            log(f"ring {self.addr} not connected after {self.attempt} connect attempt(s) and "
                f"{self.adverts} advert(s) received")
            self.loop.quit()
            return False
        return True

    def on_props(self, iface, changed, _invalidated):
        if iface != DEVICE:
            return
        if "RSSI" in changed:
            self.adverts += 1
            if self.adverts == 1:
                log(f"ring seen (rssi {int(changed['RSSI'])} dBm)")
            if not self.connecting and self.remaining() > 5:
                self.start_connect(int(changed["RSSI"]))

    def start_connect(self, rssi: int) -> None:
        self.connecting = True
        self.attempt += 1
        timeout = max(5, min(CONNECT_TIMEOUT_S, int(self.remaining())))
        log(f"connect attempt {self.attempt} (rssi {rssi} dBm)")
        self.dev.Connect(reply_handler=self.on_connected, error_handler=self.on_connect_error,
                         timeout=timeout)

    def on_connect_error(self, e: dbus.DBusException) -> None:
        # Worded as a warning on purpose: a retry that later succeeds isn't a
        # failed sync. The final outcome is reported by check_deadline/sync.sh.
        log(f"WARN connect attempt {self.attempt} unsuccessful: {e.get_dbus_message()}")
        try:
            if bool(self.prop("Connected")):
                self.dev.Disconnect()
        except dbus.DBusException as de:
            log(f"note: disconnect after unsuccessful attempt: {de.get_dbus_message()}")
        GLib.timeout_add_seconds(RETRY_PAUSE_S, self.allow_retry)

    def allow_retry(self) -> bool:
        self.connecting = False
        return False

    def on_connected(self) -> None:
        start = time.monotonic()
        GLib.timeout_add(250, self.wait_resolved, start)

    def wait_resolved(self, start: float) -> bool:
        if not bool(self.prop("Connected")):
            log(f"WARN link dropped before services resolved (attempt {self.attempt})")
            GLib.timeout_add_seconds(RETRY_PAUSE_S, self.allow_retry)
            return False
        if bool(self.prop("ServicesResolved")):
            log(f"ring connected on attempt {self.attempt} ({self.adverts} advert(s) received)")
            self.result = 0
            self.loop.quit()
            return False
        if time.monotonic() - start > RESOLVE_TIMEOUT_S:
            log(f"WARN services didn't resolve within {RESOLVE_TIMEOUT_S} s (attempt {self.attempt}); reconnecting")
            self.dev.Disconnect()
            GLib.timeout_add_seconds(RETRY_PAUSE_S, self.allow_retry)
            return False
        return True


def main() -> int:
    if len(sys.argv) != 3:
        log("usage: ring_connect.py <identity address> <deadline seconds>")
        return 2
    addr, deadline = sys.argv[1].upper(), float(sys.argv[2])
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()
    objects = dbus.Interface(bus.get_object(BLUEZ, "/"),
                             "org.freedesktop.DBus.ObjectManager").GetManagedObjects()
    if ADAPTER not in objects.get("/org/bluez/hci0", {}):
        log("ERROR BlueZ has no adapter hci0 (Bluetooth blocked, or the adapter is missing)")
        return 2
    if DEVICE not in objects.get("/org/bluez/hci0/dev_" + addr.replace(":", "_"), {}):
        log(f"ERROR BlueZ doesn't know ring {addr}: the bond is gone; re-pair it (see health/CLAUDE.md)")
        return 2
    return Connector(bus, addr, deadline).run()


if __name__ == "__main__":
    sys.exit(main())
