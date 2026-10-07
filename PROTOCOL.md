# BLE Motor Control Protocol

## Current ESP32-C3 Controller

The current app scans for `troy high school`. It does not use the legacy fixed MAC address.

| Action | Hex |
| --- | --- |
| Left / low-note continuous motion, speed 50 | `A1010100000002321F` |
| Right / high-note continuous motion, speed 50 | `A1020100000002321F` |
| Stop | `A10102000000011F` |
| ESP32-local homing | `A1F001` |
| Set speed 50 | `A1080132` |
| Ready / heartbeat request | `AF010203040506FF` |

Service UUID: `0000ffe0-0000-1000-8000-00805f9b34fb`. The write and notify UUIDs below are shared with the current controller.

ESP32 reads the two limit inputs locally, stops motion toward an active limit, and reports the four binary limit events listed below. Homing sends ASCII notifications: `HOME_START`, `HOME_LOW_LIMIT`, `HOME_BACKOFF`, `HOME_DONE`, `HOME_ABORT`, and `FAULT`.

See [firmware documentation](firmware/esp32_c3_piano_motor/README.md) for the current wiring, protection logic and text commands. Homing backoff is timed at 10 seconds by default; 10 revolutions is an estimate pending hardware calibration.

## Legacy Board Reference

The remaining sections preserve earlier vendor-board observations. Its startup/mode commands and original motion bytes are historical references, not the current ESP32 initialization procedure. Reliable limit feedback was not confirmed on the vendor board.

Device name: `JUXUN-88888888`

Known device address: `DE:AB:BD:EA:2F:DE`

## BLE Characteristics

Write characteristic:

`0000ffe2-0000-1000-8000-00805f9b34fb`

Notify characteristic:

`0000ffe1-0000-1000-8000-00805f9b34fb`

CCCD descriptor:

`00002902-0000-1000-8000-00805f9b34fb`

## Startup Sequence

After connecting and enabling notifications, send:

| Action | Hex |
| --- | --- |
| Init | `AF010203040506FF` |
| Jog mode | `A10302011F` |
| Speed 50 | `A1080132` |

## Heartbeat

After connection is ready, the app sends a safe heartbeat every 10 minutes:

| Action | Hex |
| --- | --- |
| Heartbeat / init keepalive | `AF010203040506FF` |

This packet does not command motor motion. It is stopped when BLE disconnects.

## Motor Commands

| Action | Hex |
| --- | --- |
| Left / reverse hold | `A1010100000003321F` |
| Right / forward hold | `A1020100000003321F` |
| Stop | `A10102000000031F` |

The app uses hold buttons: press sends left/right, release sends stop.

## Speed Commands

| Speed | Hex |
| --- | --- |
| 30 | `A108011E` |
| 50 | `A1080132` |

## Mode Commands

| Mode | Hex |
| --- | --- |
| Jog mode | `A10302011F` |
| Lock / continuous mode | `A10303011F` |

## Limit Switch Notifications

Notifications arrive from characteristic `FFE1`.

| Event | Hex |
| --- | --- |
| Left limit pressed | `A10101` |
| Left limit released | `A10102` |
| Right limit pressed | `A10201` |
| Right limit released | `A10202` |

When a limit is pressed, motor motion in the current direction should stop and that direction should be blocked until the motor reverses.
