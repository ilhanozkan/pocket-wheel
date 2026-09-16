#!/usr/bin/env python3
"""Phone-protocol simulator -> real Swift UDP service -> Intel SCS plugin ABI host."""
import hashlib
import hmac
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
KEY = b"0123456789abcdef"  # Deliberately public test key, never a saved app key.

def signed(payload):
    data = payload.encode("ascii")
    return data + b"|" + hmac.new(KEY, data, hashlib.sha256).hexdigest().encode()

def verified(data):
    payload, tag = data.rsplit(b"|", 1)
    assert hmac.compare_digest(tag, hmac.new(KEY, payload, hashlib.sha256).hexdigest().encode())
    return payload.decode().split("|")

def main():
    candidates = [ROOT / "macos/.build/out/Products/Debug/PocketWheel", ROOT / "macos/.build/debug/PocketWheel"]
    companion = next((p for p in candidates if p.exists()), None)
    assert companion, "Build macOS debug executable before running this test."
    # Use an alternate LAN port to avoid disturbing an already-running desktop app.
    port = 26770
    log_dir = ROOT / "build"
    log_dir.mkdir(exist_ok=True)
    host_log = (log_dir / "plugin-host.log").open("w")
    bridge_log = (log_dir / "bridge-test.log").open("w")
    frames = []
    host = subprocess.Popen([str(ROOT / "build/plugin-host"), str(ROOT / "dist/pocketwheel.dylib"), "12"], stdout=subprocess.PIPE, stderr=host_log, text=True)
    bridge = None
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(.2)
    try:
        assert host.stdout.readline().strip() == "READY", "Plugin ABI host did not initialize (is ETS2 already holding port26761?)"
        def consume():
            for line in host.stdout:
                if line.startswith("["):
                    frames.append((time.monotonic(), json.loads(line)))
        thread = threading.Thread(target=consume, daemon=True)
        thread.start()
        bridge = subprocess.Popen([str(companion), "--headless", "--key", KEY.decode(), "--port", str(port), "--enable", "--duration", "10"], stdout=bridge_log, stderr=bridge_log)
        target = ("127.0.0.1", port)
        handshake = signed("PWH1|1122334455667788")
        session = None
        deadline = time.monotonic() + 4
        while time.monotonic() < deadline:
            sock.sendto(handshake, target)
            try:
                parts = verified(sock.recv(512))
                if parts[:2] == ["PWC1", "1122334455667788"]:
                    session = parts[2]; break
            except socket.timeout:
                pass
        assert session, "No authenticated server challenge"
        seq = 0
        def send(armed, steer=0, gas=0, brake=0, buttons=0):
            nonlocal seq
            payload = f"PW1|{session}|{seq}|{steer}|{gas}|{brake}|{buttons}|{armed}"
            sock.sendto(signed(payload), target)
            expected = seq
            seq += 1
            while True:
                ack = verified(sock.recv(512))
                if ack[0] == "PWA1" and int(ack[2]) == expected:
                    return ack
        assert send(1, gas=10000)[3] == "0", "Fresh session armed without a disarmed frame"
        send(0)
        time.sleep(.07) # Ensure disarmed bridge state is observed by plugin.
        for _ in range(8):
            assert send(1, -7500, 8500, 2500, 21)[3] == "1"
            time.sleep(.02)
        time.sleep(.03)
        # SDK pedal axes use Normal-mode [-1,+1]; phone/bridge remain [0,10000].
        assert any(v == [-.75, .7, -.5, 1, 0, 1, 0, 1, 0] for _, v in frames), frames
        # Bad signatures and out-of-order frames must not refresh the receiver timeout.
        start_loss = time.monotonic()
        while time.monotonic() - start_loss < .65:
            sock.sendto(signed(f"PW1|{session}|0|10000|10000|10000|63|1"), target)
            sock.sendto(b"PW1|forged|999|10000|10000|0|0|1|bad", target)
            time.sleep(.025)
        released = [0, -1, -1, 0, 0, 0, 0, 0, 0]
        assert frames[-1][1] == released, frames[-1]
        assert any(0 < abs(v[0]) < .75 and v[1:] == released[1:] for t, v in frames if t >= start_loss), "No gradual disarmed steering centering"
        assert send(1, gas=10000)[3] == "0", "Timeout silently rearmed"
        send(0); time.sleep(.07)
        assert send(1, 2500, 6000, 0, 2)[3] == "1"
        time.sleep(.07)
        assert any(v == [.25, .2, -1, 0, 1, 0, 0, 0, 0] for _, v in frames), frames
        # Hard-kill the companion, exercising the plugin's independent watchdog.
        bridge.kill(); bridge.wait(timeout=3)
        time.sleep(.65)
        assert frames[-1][1] == released, frames[-1]
        print("PASS: authenticated pairing, arm gate, three axes, six buttons, replay/bad-MAC rejection, gradual centering, explicit rearm, and companion-crash watchdog across real Swift + Intel plugin.")
    finally:
        sock.close()
        if bridge and bridge.poll() is None:
            bridge.terminate(); bridge.wait(timeout=3)
        if host.poll() is None:
            host.terminate(); host.wait(timeout=3)
        host_log.close(); bridge_log.close()

if __name__ == "__main__":
    main()
