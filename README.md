
```text
# BLE Motor Control Protocol

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


```

