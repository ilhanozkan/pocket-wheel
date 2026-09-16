# Pocket Wheel design

Mode: Operate. Use the previously agreed landscape controller composition. This is used two-handed while looking mainly at the game, often in a dim room.

- Android: matte slate background, high-contrast white labels, large separated pedal tracks. Brake uses red with text; gas uses blue with text. Center column owns connection/arm status, steering angle, calibration, D/N/R and sequential controls.
- macOS: native SwiftUI window, system typography and controls, clear pairing details, live axis meters, explicit Start/Stop and enabled state. Avoid decoration and false claims of game readiness.
- Controls have 48 dp minimum targets, visible disabled/pressed states, accessible labels, and readable percentages. Pedals release on pointer up/cancel or app backgrounding. Calibration disarms driving.
- Connection setup/settings may scroll; driving controls fit landscape. Settings preserve user choices. Error messages name the recovery step.
- Phone gear labels reflect requested input, not confirmed game gear.
