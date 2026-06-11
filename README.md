# MAX Piano Tuner

Android app for piano tuning assistance with BLE motor control.

The app combines:

- microphone pitch detection
- a piano-style tuning dial
- 88-key recording workflow
- BLE control for a 12V geared motor board
- left/right limit switch handling
- safe hold-to-run motor controls
- guarded automatic tuning pulses for the selected key

## Hardware

Known BLE board:

- Device name: `JUXUN-88888888`
- Known address: `DE:AB:BD:EA:2F:DE`
- Motor: 12V geared motor, about 90 RPM
- Limit switches: left and right limit inputs

See [`PROTOCOL.md`](PROTOCOL.md) for BLE UUIDs, motor commands, stop command, speed commands, and limit switch notifications.

See [`HARDWARE.md`](HARDWARE.md) for the tested board/motor reference links and hardware notes.

## Tuning Dial

The 12 o'clock zero mark means the selected note is in tune.

If the detected pitch is outside the supported tuning span, the app moves the dial to the limit and triggers a red edge alarm plus vibration.

## 88-Key Recording

Open the top-left menu and tap `Record 88 Keys`.

The workflow records all keys from `A0` to `C8`. Each key requires 3 stable pitch samples; the app saves the median value and then advances automatically.

After all 88 keys are recorded, tap `Compute Tuning` to enable a lightweight stretch tuning map. The current implementation is intentionally simple and can later be replaced with full spectral/inharmonicity analysis similar to Entropy Piano Tuner.

See [`RECORDING_WORKFLOW.md`](RECORDING_WORKFLOW.md).

## Auto Tuning

Open the top-left menu and tap `Auto Tune Selected Key`.

The app only starts or continues automatic motor pulses after it hears a stable pitch near the selected key. This avoids using room noise or a wrong piano key as the tuning signal. If the sound is not stable, the auto workflow waits and asks you to play the selected key again.

Use `Stop Auto Tune` from the menu to cancel the automatic workflow. Limit switch notifications still stop the motor and block that direction until the opposite direction is used.

Before auto tuning starts, the app shows a safety confirmation checklist for bracket locking, limit switches, manual direction testing, and selected-key pitch stability.

Use `Calibration` from the same menu to adjust and save:

- motor speed percent
- probe pulse length
- long / medium / short auto pulse lengths
- settle time after each pulse
- maximum auto steps
- done tolerance in cents

## BLE Heartbeat

After a successful BLE connection and initialization, the app sends a safe heartbeat packet every 10 minutes:

`AF010203040506FF`

This is the same init/keepalive-style packet and does not command motor motion. The heartbeat stops automatically when BLE disconnects.

## Build

Open this folder in Android Studio and run the `app` debug build.

Minimum Android SDK: 23

Target Android SDK: 35

Required permissions:

- Bluetooth scan/connect
- Location on older Android versions
- Microphone
- Vibration

## Disclaimer

This software controls a motor attached to piano tuning hardware. Use carefully. Incorrect operation can break piano strings or damage hardware. You are responsible for safe testing and mechanical limits.

TORY HIGH SCHOOL MAX.YING 2026.
