# Pocket Wheel — quick setup

## Files

Unzip `PocketWheel-mac.zip` on the Mac. It contains `Pocket Wheel.app` and `pocketwheel.dylib`. Transfer the separate `PocketWheel-android.apk` to your Android phone and install it. Android may ask you to allow installing apps from the file manager/browser used to open it.

## Install the ETS2 plugin

1. Quit Euro Truck Simulator 2.
2. In Steam, select ETS2 → Manage → Browse local files.
3. In Finder, right-click `Euro Truck Simulator 2.app` → **Show Package Contents**.
4. Open `Contents/MacOS`. Create a folder named `plugins` if it does not exist.
5. Copy `pocketwheel.dylib` into `plugins`.
6. Restart ETS2. Acknowledge its SDK/plugin notice if shown.

If your Steam library is on an external drive, mount it before installing. If macOS blocks the automated installer from modifying the app bundle, use the Finder steps above. The plugin targets Intel ETS2 running under Rosetta; the companion app is native Apple Silicon.

## Pair

1. Open **Pocket Wheel** on the Mac. Click **Start receiver** and allow local network access if asked.
2. Open **Pocket Wheel** on Android. Tap **Pair Mac**, enter the Mac's displayed Wi-Fi IPv4 address and 16-character pairing key, and connect.
3. Both devices must use the same Wi-Fi. Guest networks can block local communication. The receiver uses UDP port **26760**.
4. Hold the phone horizontally with the screen facing you. Tap **Center** in your natural straight-ahead position.
5. Switch on **Enable controls** on the Mac, then tap **Arm** on Android.

## Bind in ETS2

Under **Options → Controls**, select/add **Pocket Wheel**.

- Assign steering by turning the phone.
- Assign acceleration by sliding the right **Gas** pedal.
- Assign braking by sliding the left **Brake** pedal.
- Set acceleration and braking axis modes to **Normal**. Check that released pedals are at zero and full touch travel reaches full input. The current plugin reports standard pedal travel: -1 released, +1 fully pressed.
- Choose **Real automatic** transmission. Assign **Automatic gearbox: Drive** to D and **Automatic gearbox: Reverse** to R. D and R stay held after selection; N releases both. For manual binding, tap N before opening a capture field, select D or R, then tap N to release it if capture is still waiting. The repair tool below assigns these directly.
- In **Keys & Buttons**, optionally assign N to **Shift to neutral** and Park to **Parking brake**.
- To drive with sequential gears instead, select **Sequential** in ETS2 and bind **+ / −** to **Shift Up / Shift Down**. A sequential shift clears the automatic selection. No clutch is included.

The phone shows your requested selector state, not the game's actual gear. The Mac's meters show forwarded input, not proof that ETS2 loaded the plugin. A successful load writes `[Pocket Wheel] Input device registered` to the game's log.

### Repair saved bindings

If the device is selected but an axis says **missing**, click that axis field and move the corresponding control through most of its travel. Keep the phone steady while assigning pedals. A default joystick assignment such as `joy.y` does not refer to Pocket Wheel's pedals.

The included `configure-ets2.py` can set the three axes and six buttons in an existing profile. Quit ETS2 first, then pass the exact path to that profile's `controls_osx.sii`:

```sh
python3 configure-ets2.py --controls "/path/to/your/profile/controls_osx.sii"
```

This previews the changes. Add `--apply` to save them with a timestamped backup. Use the tool with the matching updated plugin: it selects **Normal** pedal mode and replaces Pocket Wheel's existing button assignments while preserving keyboard, Steam Input and other-controller alternatives. It requires Pocket Wheel to be selected already and refuses to write while ETS2 is running. On macOS, local Steam Cloud profile controls are usually under `~/Library/Application Support/Euro Truck Simulator 2/steam_profiles/<profile>/`. It does not change the transmission setting; select **Real automatic** in the game for D/N/R.

## Driving controls

Turn the phone clockwise to steer right. Use **Tune** for range, sensitivity curve, smoothing, dead zone and inversion. Hold and slide each pedal with a thumb; releasing the thumb releases the pedal. Gas and brake work independently with two touches. Calibration and opening settings disarm controls.

Pause ETS2 before putting the phone down. If the Mac stops receiving control frames, it releases input and requires Arm again. Missing replies alone leave the phone sending controls, with a **Mac replies delayed** status. Neither behavior pauses the game or stops a moving truck automatically.

## If something does not connect

- Confirm the Mac receiver is started and both devices share Wi-Fi. Use the Wi-Fi interface address, not a VPN address.
- If Android is paired but controls are inactive, enable controls on the Mac, then tap Arm again.
- In Android **0.1.3**, more than 750 ms without a fresh authenticated reply shows **Mac replies delayed**. The phone keeps sending steering, pedals and gear input; reply loss alone no longer disarms. If the Mac itself stops receiving valid control frames for 300 ms, its watchdog still releases controls and requires rearming. Restarting the receiver also requires Arm again.
- Version 0.1.1 keeps the last disarm reason visible after reconnection. Install the new APK over the old one to preserve pairing and tuning. A delayed reply can no longer hide a newer disarm reason.
- Version 0.1.2 adds **Details → Copy** on Android. After an unexpected reply timeout, pause the game, then copy the report. It preserves the measurements at the last timeout across reconnection and app restarts, including the age of the last received packet and valid reply, packet counts and the longest network-worker pause. Opening Details disarms the controls. The report excludes addresses, pairing keys and packet contents; it does not by itself identify a network fault or change timeout behavior.
- The Mac receiver requests uninterrupted timer scheduling while running; Stop releases that request. **Connection diagnostics** compares the largest gap between received phone frames with the largest output-timer gap. A receive gap with a steady output timer points toward phone/network delivery, while gaps in both can indicate Mac scheduling delays. These are observations, not proof of a router fault.
- Android requests low-latency Wi-Fi while the controller connection runs in the foreground, and releases the request on pause or disconnect. This reduces Wi-Fi power-saving delays on supported devices; it cannot prevent radio interference or network outages. Keep the phone app open during driving.
- Returning from another Android app requires Center and Arm again.
- If the phone changes IP address, stop and restart the Mac receiver before reconnecting.
- If Pocket Wheel is absent from ETS2, check the plugin location, restart the game, and inspect its log. Device bindings must be verified in the game.
- A game update can replace its app bundle; reinstall the plugin if necessary.

To remove the plugin, quit ETS2 and remove only `Contents/MacOS/plugins/pocketwheel.dylib`. Your game profiles and other plugins are unaffected.

## Test status

The initial builds passed automated motion/protocol tests and an Android emulator → Swift receiver → compiled Intel plugin smoke test. The installed plugin has since loaded in the user's ETS2, and steering assignment was confirmed with the phone. The updated pedal mapping passes an actual SDK-callback regression under Rosetta and a real Swift receiver → Intel plugin integration test, including all six buttons and watchdog releases. Configuration tests cover replacing an incorrect Reverse-to-Park assignment while keeping keyboard and other-controller bindings. After Android 0.1.3, the owner reports that the earlier issues appear resolved on a Mac mini M4 and Samsung S24.

Intermittent phone reply delivery remains under investigation. Android 0.1.3 separates delayed replies from lost control input: a reply outage can show a warning while control transmission continues. The Mac and plugin input-loss watchdogs remain unchanged. An automated reply-loss test can verify this behavior, but it does not identify where replies are lost on a particular network.
