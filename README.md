# Pocket Wheel

Use an Android phone as a steering wheel, gas/brake pedals, and gear selector for Steam's macOS **Euro Truck Simulator 2**. The phone and Mac communicate directly on the same Wi-Fi.

The Android app uses fused accelerometer/gyroscope orientation. The Mac companion authenticates and forwards controls to an SCS Input SDK plugin inside ETS2. It does not install a virtual joystick or require a kernel extension.

## Start with the built apps

Build artifacts are in `dist/`:

- `Pocket Wheel.app` — native Apple Silicon Mac companion.
- `PocketWheel-android.apk` — Android controller (Android 8+; gyroscope and accelerometer required).
- `pocketwheel.dylib` — Intel plugin matching the installed Intel/Rosetta ETS2 build.

### 1. Install the game plugin

Quit ETS2. From this project folder:

```sh
python3 scripts/install-plugin.py
```

The installer discovers Steam libraries, checks the game architecture, and installs only Pocket Wheel. It backs up a previous Pocket Wheel build before replacement. It does not edit your profiles or bindings.

**On this Mac, macOS blocked the installer from modifying the installed game app bundle.** If the script reports that restriction, use Finder:

1. Open your Steam library's `Euro Truck Simulator 2` folder.
2. Right-click `Euro Truck Simulator 2.app` → **Show Package Contents**.
3. Open `Contents/MacOS`. Create a folder named `plugins` if needed.
4. Copy `dist/pocketwheel.dylib` into that folder.

If your Steam library is on an external drive, mount it before installing or playing. Do not install both a `.so` and `.dylib` copy: this game's macOS loader expects `.dylib`.

Restart ETS2. If it shows the SDK/plugin notice, acknowledge it. In its `game.log.txt`, successful registration includes:

```text
[Pocket Wheel] Input device registered: 3 analog axes, 6 buttons.
```

The script `python3 scripts/install-plugin.py --inspect` shows the exact destination. `--uninstall` removes only the file installed by the script after checking its receipt. For a manual Finder installation, remove only `pocketwheel.dylib` manually while the game is closed.

### 2. Install the Android app

Transfer `dist/PocketWheel-android.apk` to the phone and open it. Allow installation from the app you used to open the APK if Android requests it. For a phone already connected with USB debugging:

```sh
adb install -r dist/PocketWheel-android.apk
```

No Google Play account, browser motion permissions, or USB connection is needed during play.

### 3. Pair the phone and Mac

1. Open `dist/Pocket Wheel.app` and start its receiver. Allow Local Network access/incoming connections if macOS asks.
2. On Android, enter the Mac's displayed Wi-Fi IPv4 address and pairing key. UDP port is **26760**. Keep both devices on the same network; guest Wi-Fi can block device-to-device traffic.
3. Connect. The phone reports an authenticated connection and round-trip latency.
4. Hold the phone horizontally, with its screen facing you like a small wheel. Tap **Center** in your comfortable straight-ahead position.
5. Enable controls in the Mac companion, then **Arm** on the phone. Turn clockwise to steer right; use Invert if your preferred grip needs it.

The Mac's meters show output sent to the plugin. They do not confirm that the game loaded it. The phone shows requested gear selection, not the game's current gear.

### 4. Bind the controls in ETS2

Open **Options → Controls** and select/add **Pocket Wheel** as an input device. Exact labels can vary by game language/version.

- Bind **Steering axis** to Pocket Wheel **Steering** by rotating the phone. Use a centered axis, start with the game's additional dead zone/nonlinearity at zero, then tune to taste.
- Bind **Acceleration axis** to **Gas**, and **Brake axis** to **Brake**, using **Normal** axis mode for both. Verify released pedals read zero and fully pressed pedals read full. The plugin converts the phone's 0–100% pedal position into the game's −1 to +1 axis range.
- Choose **Real automatic** transmission for D/N/R controls. Bind **Automatic gearbox: Drive** to **Drive** and **Automatic gearbox: Reverse** to **Reverse**. D/R remain held after tapping. Tap N to clear both, selecting neutral.
- Optionally bind **Neutral** to **Shift to neutral** under Keys & Buttons.
- Bind **Parking Brake** under Keys & Buttons. This button sends a tap; the game toggles the parking brake.
- For **Sequential** transmission, bind the app's **+** and **−** buttons to **Shift Up** and **Shift Down**. Tapping either clears held D/R selection. This version has no clutch or H-pattern gear selector.

During binding, arm while stationary and tap N between assigning D and R. Release other controls to avoid assigning the wrong input. Physical keyboard bindings remain available. Steering range defaults to 90° each side, adjustable in Android settings; this is a hand-held controller rather than a multi-turn physical wheel.

The held D/R behavior follows the SCS developer's explanation of [automatic selector versus momentary shift bindings](https://forum.scssoft.com/viewtopic.php?t=189888).

## Build from source

Requirements: macOS 13+, Apple Command Line Tools, Python 3, and network access for first-time dependencies. The Android bootstrap installs its JDK, SDK and caches under this project's `.tools/` without changing the system Java installation. Initial downloads, including the optional development emulator, take several GB.

```sh
bash scripts/build-plugin.sh
cd macos
swift test --disable-xctest
bash scripts/bundle.sh
cd ..
bash scripts/build-android.sh
```

The plugin fetches the official [SCS SDK 1.14](https://modding.scssoft.com/wiki/Documentation/Engine/SDK/Telemetry) with a pinned checksum and retains its license in `dist/`. It compiles for x86_64 because the inspected ETS2 executable is Intel, even though the Mac is M4. Revisit that target if a future game update ships an ARM executable.

## Tests

```sh
bash scripts/build-plugin.sh                  # C++ parser/state/watchdog tests + actual plugin build
swift test --package-path macos --disable-xctest
bash scripts/build-android.sh                 # Android unit/network tests + APK
python3 tests/end_to_end.py                   # Swift receiver -> Intel SCS plugin host under Rosetta
```

For the integration test, quit ETS2 and stop the Mac receiver first so only the test host uses loopback UDP 26761. The test uses a public test key and its own LAN port 26770; it never reads the saved pairing key. It verifies authentication, arm gates, analog axes, buttons, stale/replayed packets, gradual centering, explicit rearm, and companion-crash handling.

**Verified:** the actual Android APK in an emulator paired with the Swift receiver and drove the compiled Intel plugin in an SCS ABI host. Gas and brake reached full scale, synthetic motion changed steering, gear/parking controls arrived, backgrounding released all channels, and returning required reactivation. Swift protocol tests, Android motion/network tests and plugin watchdog tests pass.

**Physical check:** the plugin loaded successfully in Steam's macOS ETS2. With Android 0.1.3, the owner reports that the earlier issues appear resolved on a Mac mini M4 and Samsung S24. Other phones and networks remain unverified; emulator testing cannot establish real-phone sensor feel or actual Wi-Fi latency. No road-driving proficiency claim is made.

## Behavior on interruptions

- Stop, app backgrounding, calibration and disconnect disarm the phone.
- Delayed Mac replies show a warning while the phone keeps sending current inputs. They no longer disarm the phone by themselves. A receiver restart or a negative acknowledgement of armed input still requires Arm again.
- After 300 ms without valid frames, pedals/buttons release and steering centers over 200 ms. The plugin independently applies this if the Mac companion crashes.
- Reconnecting never resumes driving automatically: enable controls as needed and tap Arm again.
- Lifting a thumb releases that pedal. Brake and gas can be touched independently at the same time. Sliders measure thumb position, not pressure.
- Traffic is authenticated with a fresh session challenge and HMAC-SHA256. It is local-only but not encrypted. Keep the pairing key private and regenerate it to revoke pairing.
- A watchdog releases input; it does **not** pause ETS2 or automatically stop a moving truck. Pause the game before leaving the controller.

## Project map

`android/` contains the Kotlin controller and tests. `macos/` contains the SwiftUI companion, UDP service and protocol tests. `plugin/` contains the C++ SCS device. `PROTOCOL.md` defines both LAN and loopback messages. `scripts/` handles reproducible builds and plugin installation.

Data flow: **Android sensors + touch → authenticated UDP → Swift companion → loopback UDP → SCS input device → ETS2**.
