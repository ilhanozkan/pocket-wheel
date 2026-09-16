# Pocket Wheel

<!-- impeccable:product-schema 1 -->

## Platform

adaptive

A local Android motion controller for Steam's macOS Euro Truck Simulator 2 on a Mac mini M4. The phone and Mac share Wi-Fi. The user holds the phone horizontally like a wheel and uses both thumbs for gas, brake, automatic D/N/R selection, optional sequential shifts, and parking brake.

## Confirmed brief
- Native Android controller, native Mac companion, and an SCS input plugin.
- Calibration, adjustable range/dead zone/smoothing, responsive analog controls.
- Local authenticated networking, visible connection state, stale-input watchdog and explicit reactivation.
- Deliver runnable builds and installation instructions, verifying actual game architecture.
- Landscape layout: brake left, gas right, gears center, as agreed before implementation.

## Assumptions
- Automatic transmission is intended; autonomous driving is out of scope.
- Android 8+ with a gyroscope and accelerometer; exact phone model is unconfirmed.
- Personal-use prototype; physical phone/game validation must be distinguished from automated tests.

## Success
The phone can steer and operate pedals together, reconnect without stuck controls, and bind controls in ETS2. Desktop status must distinguish phone connectivity from verified game integration.
