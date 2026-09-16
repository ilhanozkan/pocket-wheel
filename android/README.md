# Android controller

Native Kotlin app for Android 8+ with a gyroscope and accelerometer. Hold the phone in landscape with the screen facing you, in a comfortable steering position. The Android game rotation vector fuses gyro/accelerometer readings; devices without it use the regular fused rotation vector if available.

## Build and install

From the project root, run `./scripts/build-android.sh` to install/use the local build tools and build the APK. Or use Android Studio / Java 17 + Android SDK 35 and run:

```sh
cd android
./gradlew testDebugUnitTest assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Install by copying it to your phone and opening it (allow installation from your file app), or `adb install -r app/build/outputs/apk/debug/app-debug.apk`. This is a personal debug build, not a Play Store release.

## Drive

1. Start the Mac companion, keep both devices on the same Wi-Fi, and open **Pair Mac** on the phone. Enter the companion's Wi-Fi IP and 16-character pairing key.
2. Hold the phone in its driving position and tap **Center**. This calibrates its current orientation as straight ahead and disarms controls.
3. Enable controls in the Mac companion, then tap **Arm** on the phone. ETS2 controls must also be bound as explained in the project README.
4. Rotate the phone clockwise to steer right. Hold a thumb in a pedal track and slide up for more gas/brake; release the thumb to release the pedal. Both pedals work simultaneously with steering.
5. **D / R** hold mutually exclusive automatic gearbox selector inputs, as ETS2 requires. **N** releases both selectors and briefly presses the optional Shift to Neutral binding. **− / +** release the automatic selection and send one sequential shift. **Park** toggles the game's parking brake binding. Disarming resets the selection. These are requests, not confirmation of the game's current gear.
6. **Tune** adjusts range (30–160° each side), center dead zone, smoothing, response curve and inversion. Save, Center, and Arm again.

Returning from another app, opening setup/tuning, losing focus, an explicit receiver rejection or replacement of the receiver session disarms controls. Gas, brake and button inputs release. Reconnection never automatically arms. If the gyro stops delivering updates for 250 ms, output disarms. The Mac and plugin enforce independent watchdogs too.

In 0.1.3, missing Mac replies for 750 ms shows **Mac replies delayed** while the phone keeps sending current controls. It does not release held pedals or the selected gear by itself. The Mac still releases controls after 300 ms without valid incoming frames. **Details → Copy** preserves measurements from the latest reply outage; opening Details disarms controls.

Pairing settings and steering preferences stay on the phone. Android backup is disabled so the pairing key is not exported through automatic app backup. Traffic is authenticated with HMAC-SHA256 but not encrypted; use your trusted local Wi-Fi.

## Tests and physical checks

Unit tests cover tilted-center quaternion steering, direction/inversion, calibration, filtering, invalid sensor inputs, signed protocol vectors, malformed/authentication failures, button pulse release, and real loopback UDP handshake/ack-timeout cycles. Regression tests cover a receiver that starts late or restarts and a closed worker returning from delayed DNS without touching a replacement controller's state. An emulator can verify layout and lifecycle but cannot validate real phone sensor quality, wireless latency, multi-touch grip or in-game bindings. Those need the user's phone and ETS2.

### Primary references

- [Android game rotation vector](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position#sensors-pos-gamerot)
- [Android multi-touch pointer handling](https://developer.android.com/develop/ui/views/touch-and-input/gestures/multi)
- [Android Gradle Plugin 8.7 compatibility](https://developer.android.com/build/releases/agp-8-7-0-release-notes)
