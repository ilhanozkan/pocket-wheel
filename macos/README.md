# Mac companion

Requires an Apple silicon Mac, macOS 13 or later, and Apple Command Line Tools (`xcode-select --install`). No external Swift packages are needed. Unit tests use Swift Testing (Swift 6+) because the standalone Command Line Tools do not include XCTest; the app itself supports macOS 13+.

```sh
cd macos
bash scripts/test.sh
bash scripts/bundle.sh
open "../dist/Pocket Wheel.app"
```

1. Start the receiver. If macOS asks, allow Local Network access and incoming connections.
2. Copy a displayed IPv4 address and the pairing key into the Android app. The default LAN port is UDP 26760. Use the address of the interface connected to the same Wi-Fi; guest networks may isolate devices.
3. Connect the phone. Turn on **Enable controls** on the Mac, then tap **Arm** on the phone.
4. The output meters show effective controls sent to the local game plugin. Phone connectivity does not prove ETS2 loaded the plugin. Install and bind the plugin using the project instructions.

The pairing key persists in the app’s user defaults. Use **New pairing key** while stopped to revoke the old key. The wire data is authenticated with HMAC-SHA256 but is not encrypted. The key stays hidden until Show is clicked and is never written to logs.

The network service sends current output at 60 Hz to `127.0.0.1:26761` and never waits on the game. If frames stop for 300 ms, pedals and buttons release immediately, steering returns to center over 200 ms, and explicit rearming is required. Stop and quit send neutral frames. System sleep/wake revokes desktop permission. A disarmed frame must arrive after enabling controls before the phone can arm.

While the receiver runs, a scoped macOS activity assertion prevents App Nap from deferring the input bridge when the game covers its window; normal system sleep remains allowed. The assertion ends on Stop. **Connection diagnostics** shows the largest accepted-frame gap and output-timer gap since Start. Large gaps in both suggest a Mac scheduling stall; a receive gap with a steady output timer suggests phone/network delay. These measurements help diagnose interruptions without weakening the watchdog. They update when the next accepted frame or timer tick arrives.

### Private local diagnostic snapshot

The desktop app exports its latest transport state to `~/Library/Application Support/Pocket Wheel/diagnostics.json`. This bounded file updates at most five times per second on a separate utility queue and uses atomic replacement with owner-only permissions (`0600`). Headless receivers leave it untouched unless a caller explicitly supplies a separate `diagnosticsURL` when constructing `UDPBridge`.

The snapshot distinguishes **all received datagrams** from accepted control frames and rejected packets. It contains the last accepted sequence, requested/effective steering/pedals/buttons, current output, timestamps, gap measurements, and ACK/loopback send return values and error counts. A successful UDP send means the operating system accepted the datagram; it does not prove the phone or game received it. `updatedAt` must be recent before treating the snapshot as live; a file left after app exit can be stale.

The phone endpoint is included privately for route inspection. Pairing keys, authentication tags, session identifiers, and raw wire packets are excluded. Do not paste the entire file into shared logs; select only the numeric control/timing fields needed for diagnosis. Configuration is explicit in the desktop `UDPBridge` initializer; passing `diagnosticsURL: nil` disables export.

## Headless integration mode

```sh
swift run PocketWheel --headless --key 0123456789abcdef --port 26760 --enable --duration 20
```

The example key is for tests only. `--enable` explicitly grants desktop control permission; the phone must still send a disarmed frame and then request arm. Omit `--duration` to run until SIGINT/SIGTERM. Headless mode does not load or save the real app pairing key. It never prints the supplied key. Avoid putting a real pairing key into shell history or process arguments.

## Packaging

`scripts/bundle.sh` creates an arm64 release app under the project’s `dist` directory and applies an ad hoc signature for local use. Distribution to another Mac would require the appropriate signing/notarization process. The installed game may run as Intel/Rosetta; that affects the game plugin architecture, not this companion.
