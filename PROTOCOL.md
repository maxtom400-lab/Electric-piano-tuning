# BLE Motor Control Protocol

## SAFE_V2 wire specification (development prototype)

The firmware BLE endpoint dispatches commands from its controller loop and replies `SAFE_V2`; Android accepts basic `SAFE_V1` timed control but requires `SAFE_V2` for ratchet return. Android and complete ESP32-C3 builds, host behavioral tests and structural wiring checks pass. **Radio/runtime timing, electrical and physical piano validation are not complete.** Do not test this development prototype with the wrench engaged before the [unloaded validation checklist](docs/SAFETY_VALIDATION.md).

All multibyte values are unsigned, big-endian. Frames must have exactly the specified length; truncation, extra bytes, invalid directions and out-of-range durations are rejected.

| Action | Binary frame | Bytes |
| --- | --- | --- |
| Capability query | `A1 F1 00` | 3 |
| Timed move | `A1 F1 DIR ID_H ID_L PRE_H PRE_L RUN_H RUN_L` | 9 |
| Tagged homing | `A1 F2 ID_H ID_L` | 4 |
| Tagged ratchet freewheel return (V2 only) | `A1 F3 ID_H ID_L` | 4 |

`DIR=01` means low/left and `DIR=02` high/right. `PRE` is 0..10000ms in the opposite direction; `RUN` is 1..20000ms in the final direction. An optional preload is followed by **stopped output for 130ms** before correction. Example: `A1F102123417704650` requests high direction, ID 4660, 6000ms preload and 18000ms correction. The controller owns the whole sequence, including the stop; no phone STOP is required for normal completion.

IDs are 1..65535 and strictly increase across HOME, MOVE and RATCHET in one BLE session. A duplicate or older request cannot start motion. Do not wrap IDs or automatically retry a movement. A new connection clears IDs only after stopping all outputs and invalidating homing. At exhaustion, reconnect and rehome.

Capability reply: ASCII `SAFE_V2`. Required timed-move events are `MOVE_START:ID`, `MOVE_DONE:ID`, `MOVE_ABORT:ID`, `MOVE_LIMIT:ID` and `MOVE_HIGH:ID`; ID is decimal. Tagged homing adds `:ID` to `HOME_START`, `HOME_LOW_LIMIT`, `HOME_BACKOFF`, `HOME_DONE`, `HOME_LOW_STUCK`, and `HOME_ABORT`. Ratchet events are `RATCH_START:ID`, `RATCH_DONE:ID`, and `RATCH_ABORT:ID`. Messages fit within the default 20-byte ATT payload. Busy, invalid, and stale requests must fail without restarting/overwriting a running sequence. An application must not accept an untagged or mismatched completion event.

Only a matching successful DONE can update the estimated position. A limit, abort, disconnect, missing completion, or protocol error invalidates position and blocks further automatic moves until rehoming. The sole protected exception is `MOVE_HIGH`: only a high-direction correction (not preload or lowering, and not both limits) can authorize a ratchet return. The app first requests pitch remeasurement; it must still be flat and plausible. `A1F3` then requests low-direction freewheel return only, with 130ms stopped reversal, high-limit release guard, minimum 1s full-stroke plausibility and maximum 120s seek. It stops at low and reports `RATCH_DONE`; position becomes zero. A fresh remeasurement precedes the next raising correction, with no low preload at zero and a low-origin release guard. Return is not pitch lowering or an empirical-learning sample. Both app and firmware permit at most three returns per home. STOP clears return authorization, not the cycle count. Ordinary faults do not grant return permission. The app must not silently fall back to legacy HOLD commands when capability or completion confirmation is missing. Acceptance/start is not completion.

Manual legacy HOLDs require refreshes less than 750ms apart, with a 30000ms continuous ceiling. Expiry or an attempted direction change stops and locks the manual lease until explicit STOP. READY/heartbeat does not refresh it. Motor state changes are serialized in the controller loop; stale-session/pre-STOP queued writes are discarded. Queued motion/speed requests older than 250ms are rejected; queue overflow stops motion. This is not verified hardware behavior. Only uppercase text `STOP` is retained; untagged `A1F001`, text HOME/LEFT/RIGHT/SPEED and permissive prefix matching are removed.

### 中文说明

以上 `SAFE_V2` 已接入固件与 APP，两端构建、主机行为测试及源码接入检查通过，蓝牙运行时/电气/实琴验证尚未完成。APP 只在编号一致的完成事件后更新位置。普通限位/故障仍停止并要求初始化；唯有向高音修正的 `MOVE_HIGH` 允许在复测仍偏低后，用 `A1F3` 加编号执行棘轮空回。到低音限位停止，收到 `RATCH_DONE` 才把位置置零；再次击键后继续向高音修正。每次初始化最多 3 次，回程不算降音、不参加经验学习。原高音限位须在 2 秒内释放，寻找低音限位超时为 120 秒；换向停顿 130ms，全行程暂定最短 1 秒。低音零点处无 6 圈预转，并检查再次抬离低音限位。STOP/断连/异常取消续调许可。旧 `SAFE_V1` 只支持基础定时调节，不支持棘轮回程；不允许降级为手机延时 STOP。本批尚不可直接部署。

## Previous ESP32-C3 Controller (pre-SAFE_V1 reference)

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
