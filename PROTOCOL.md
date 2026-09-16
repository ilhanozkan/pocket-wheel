# Pocket Wheel protocol v1

All UDP datagrams are ASCII, no newline on LAN, at most 512 bytes. Integers use decimal, no spaces. Fields separated with `|`. Authentication appends `|` plus lowercase hex HMAC-SHA256 of all preceding bytes. HMAC key is the UTF-8 bytes of the pairing key (16 lowercase hexadecimal characters), NOT hex-decoded. Mac generates and persists this random 64-bit key and permits regeneration while stopped. This authenticates controls; payloads are not encrypted.

## LAN: Android -> Mac port 26760
Handshake: `PWH1|CLIENT_NONCE|MAC` where nonce is random 16 lowercase hex. Mac replies `PWC1|CLIENT_NONCE|SESSION|MAC` with a fresh 16-hex server-generated session. Retry same nonce returns same session (no reset). New handshake while a live session exists is rejected except matching client's reconnect after timeout. A fresh session starts disarmed. This challenge prevents replay across receiver restarts. Android retries every 500 ms until paired.

Frame: `PW1|SESSION|SEQ|STEERING|THROTTLE|BRAKE|BUTTONS|ARMED|MAC`
- SEQ: strictly increasing nonnegative integer <= 2147483647; new session before wrap.
- STEERING: -10000..10000, negative left, positive right.
- THROTTLE and BRAKE: 0..10000.
- BUTTONS: 0..63. bit 0 Drive, bit 1 Neutral, bit 2 Reverse, bit 3 Shift up, bit 4 Shift down, bit 5 Parking brake.
- ARMED: 0 or 1. Disarmed frames output zero pedals/buttons and center steering.
- Send latest full state at 60 Hz. No queued historic states. Drive/Reverse are mutually exclusive latched bits: tap D to hold bit0 until N/R/disarm; tap R to hold bit2 until N/D/disarm. N clears D/R and pulses bit1. This matches ETS2's held automatic gearbox selector bindings. Disarm resets selection to neutral. Shift up/down and parking-brake taps hold for 180 ms then release, one transition per press; hold does not repeat shifts.

Ack: `PWA1|SESSION|SEQ|ARMED|MAC` signed with same key. ARMED is receiver's effective armed state, never game confirmation. Phone uses monotonic send time per seq to calculate round-trip time. Mac sends an ACK for every accepted frame. Starting with Android 0.1.3, 750 ms without a fresh authenticated ACK marks replies as delayed and freezes a diagnostic snapshot; it does not disarm or interrupt current control frames. A fresh ACK clears the delay indication. A negative ACK for a frame from the current armed interval still disarms the phone.

While replies are delayed, Android also probes for a restarted receiver using a fresh random handshake nonce for that outage. It repeats the probe every 500 ms with the same nonce. A live Mac receiving the existing control stream rejects this alternate handshake; a restarted Mac can accept it. Only a challenge matching the current probe nonce and a different session may replace the active session. Session replacement disarms, starts with neutral frames and requires explicit human Arm. Once normal ACKs recover, the probe nonce is discarded so delayed challenge replies cannot replace a healthy session. The Mac's independent 300 ms input watchdog remains unchanged.

During a reply outage, a healthy Mac may therefore count about two rejected probe packets per second while continuing to accept control frames. These rejections are expected session protection and do not by themselves indicate an invalid pairing key or corrupted controls.

Receiver: verify MAC in constant time, then session, source address/port, strictly increasing sequence and ranges. Reject malformed or old frames without refreshing watchdog. After >300 ms without accepted state, zero throttle/brake/buttons, ramp steering to center within 200 ms and latch disarmed. Require an accepted ARMED=0 frame before another ARMED=1. Mac Stop also zeros all controls. Network input never blocks game thread. Mac's Enable controls switch is required for effective arming. Fresh session / app resume starts disarmed.

## Mac -> plugin: loopback UDP port 26761
`PWL1|BRIDGE_SESSION|SEQ|STEERING|THROTTLE|BRAKE|BUTTONS|ARMED` with optional trailing newline. BRIDGE_SESSION is random 16 lowercase hex per companion start; independent output seq increments at 60 Hz. Ranges as above. Trusted local IPC binds only 127.0.0.1; this boundary is not a system virtual joystick. Companion emits neutral/disarmed state even when no phone is connected. Plugin additionally enforces 300 ms watchdog, sequence/range checks, neutral pedals/buttons on timeout, 200 ms steering centering, and explicit disarm-before-rearm. New bridge session is accepted only when packet ARMED=0 and is followed by monotonic seq.

Disarmed loopback frames may retain a nonzero STEERING solely to convey the companion's 200 ms watchdog centering ramp. The plugin preserves that steering value but always forces pedals and buttons to zero. A deliberate disarm from Android is fully neutralized by the companion before forwarding.

At the ETS2 SDK callback boundary, the plugin maps each internal 0–1 pedal value to `2 * value - 1`. The game's **Normal** pedal mode therefore receives −1 when released and +1 at full travel. Startup, disarm and timeout also report −1 for each pedal. This conversion does not change either network protocol or the Mac's percentage meters.

## SCS device channels
Persistent generic device `pocketwheel`, display `Pocket Wheel`. Index 0 `steering` float -1..1; index 1 `throttle` float -1..1; index 2 `brake` float -1..1. Pedals use -1 released and +1 fully pressed. Indexes 3..8 booleans `drive`, `neutral`, `reverse`, `shiftup`, `shiftdown`, `parkingbrake` in button-bit order. SCS axis configuration and gear bindings are documented against the actual supported game settings. D/N/R inputs are requests, never telemetry-confirmed gear.
