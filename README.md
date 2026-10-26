# MAX Piano Tuner

[English](#english) | [中文](#中文)

## English

An Android piano-tuning assistant with an ESP32-C3 BLE controller, a 12V brushed geared motor driver, and two limit switches.

Project author: **MAX.YING · TROY HIGH SCHOOL · 2026**.

### Current Version

- Real-time microphone pitch detection and an 88-key virtual keyboard.
- A circular real-time waveform display that turns green when calibration completes; the old rotating rings and pointer are no longer used by the interface.
- BLE device name: `troy high school`. The app scans for this name instead of relying on the legacy board's fixed MAC address.
- ESP32-local homing, forward/reverse control, and limit-switch protection, with status notifications to the app.
- Estimated adjustment duration based on the selected note and an empirical response curve, with remeasurement and correction after another piano-key strike.
- Vibration alerts for a wrong key, insufficient travel, or protection events.
- Protected ratchet-wrench return and continuation at the raising-side limit (`SAFE_V2`).

The new ESP32, motor-driver, and limit-switch assembly still requires physical testing. The relationships between speed, revolutions, travel, and cents are preset estimates, not encoder measurements.

Unexpected, unmeasurable, or excessive pitch responses stop automatic tuning instead of changing the motor direction. Homing backoff must release the origin limit within a provisional 2-second timeout and must not reactivate it. ESP32-local timed moves and tagged completion events are wired into firmware and Android. **Both builds and host/source checks pass; physical validation is not complete.** This remains a development prototype, not a certified safety controller. See [validation and the unloaded-test checklist](docs/SAFETY_VALIDATION.md).

### Hardware and Wiring

| ESP32-C3 Pin | Connection |
| --- | --- |
| GPIO4 | Motor driver IN1 |
| GPIO5 | Motor driver IN2 |
| GPIO6 | Low-pitch/left limit switch; other terminal to GND |
| GPIO7 | High-pitch/right limit switch; other terminal to GND |
| GND | Common ground for the driver, buck converter, and 12V supply negative |

The 12V supply powers the motor driver and a 12V-to-5V buck converter for the ESP32. GPIO signals are 3.3V and must not receive 12V. Limit inputs use `INPUT_PULLUP`: HIGH when released and LOW when activated.

See the [ESP32 firmware guide](firmware/esp32_c3_piano_motor/README.md) for wiring, Arduino settings, debug commands, and the initial test sequence. Earlier vendor-board references remain in [HARDWARE.md](HARDWARE.md).

### Homing and Tuning Workflow

1. Connect the app to `troy high school` and select the target piano key.
2. The app asks the user to remove the wrench. Confirmation sends tagged `A1F2` + a 16-bit request ID to the controller.
3. ESP32 runs continuously toward the low-pitch limit, stops when the switch activates, waits 200ms, and reverses toward the high-pitch side.
4. Backoff lasts 10 seconds by default, estimated as 10 revolutions at the assumed 60 RPM. After matching `HOME_DONE:ID`, the app asks the user to insert the wrench.
5. Insert the wrench, select the target key in the app, and strike the physical piano key. Adjustment begins only after a stable pitch near the selected note is confirmed.
6. Strike the key again to remeasure and correct using the previous adjustment result. The waveform turns green when the completion conditions are met.

ESP32 stops the motor when BLE disconnects and blocks motion toward an active limit. Both limits active, homing timeout, or reaching the opposite limit during backoff stops the motor and reports a protection status.

### Ratchet-Wrench Stroke Continuation

When a raising correction reaches the high-side limit, `SAFE_V2` firmware reports matching `MOVE_HIGH:ID`, not completion. The app stops and requests remeasurement of the selected piano key. Only if it is still flat and the response is plausible may it send `A1F3` + a new ID for a freewheel return to the low-side limit. This return does not count as a pitch-lowering correction or a response-learning sample, and does not ask the user to remove the wrench or perform the 10-turn initialization backoff.

At the low limit, the controller stops and reports `RATCH_DONE:ID`. The app establishes position zero and requests another physical-key strike before a bounded raising correction based on the remaining cents error. The six-revolution low-direction preload is clipped to zero at this origin. At most **three returns per homing** are allowed; sharp/uncertain pitch, unexpected response, STOP, disconnect, conflicting limits, or return faults stop continuation. With a raising-only ratchet selector, automatic pitch lowering is disabled; check the selector manually instead of interpreting freewheel motion as lowering.

Return protection includes a 130ms stopped reversal interval, a provisional 2-second high-limit release timeout, a 120-second seek timeout, and a provisional 1-second minimum full-stroke plausibility check. The low switch must also release when raising away from the return origin. These thresholds and the assumption that the return does not rotate the tuning pin require physical validation. Whole-system regression checks remain part of the unfinished safety validation.

### Acoustic Strike Confirmation

Motor control now requires a newly armed quiet/attack/stable-pitch cycle, not merely another frame of a sustained note. After motion, allow the key/pedal to release and the sound to become quiet for at least 200ms, then strike the selected key. A provisional amplitude rise must occur within 300ms of that quiet interval, followed by at least 220ms of pitch stability. Each cycle grants only one control measurement. Motor/preview settling, missed or stale audio frames, wrong keys, and expired attacks cannot silently reuse the previous pitch. Pause/disconnect/STOP disarm control.

This is an **acoustic heuristic**, not proof that a physical piano key produced the sound. Thresholds depend on microphone gain and room noise; a noisy environment may prevent confirmation, and piano-like external sounds remain a risk. The gate, note/range checks and all hardware interlocks require real-device validation before use with the wrench engaged.

### Empirical Response Curve

The default model assumes 60 RPM (one revolution per second), approximately 1mm of linear travel per motor revolution, and approximately 0.28° of tuning-mechanism rotation per revolution. Cents per revolution are interpolated linearly between these anchors, with continuous transitions between registers:

| Piano Key | MIDI | Preset Cents/Revolution |
| --- | --- | --- |
| A0, bass endpoint | 21 | 2.8 |
| C4, middle reference | 60 | 4.5 |
| C8, treble endpoint | 108 | 12.6 |

Total travel is estimated as 40 revolutions. Backing off 10 revolutions during homing theoretically leaves 30 revolutions toward the high-pitch side. The default low-pitch preload is 6 revolutions. Actual speed, load, backlash, and string response require calibration; a single adjustment is not guaranteed to achieve the target pitch.

### 88-Key Recording

`Record 88 Keys` records keys from A0 through C8, collecting three stable pitch samples per key and saving their median. `Compute Tuning` uses a simplified stretch-tuning curve. Full spectral inharmonicity analysis and entropy optimization are not implemented.

See [RECORDING_WORKFLOW.md](RECORDING_WORKFLOW.md).

### BLE Protocol

Service UUID: `0000ffe0-0000-1000-8000-00805f9b34fb`; notification characteristic: `FFE1`; write characteristic: `FFE2`.

Notifications report limit events and statuses including `HOME_START`, `HOME_LOW_LIMIT`, `HOME_BACKOFF`, `HOME_DONE`, `HOME_ABORT`, and `FAULT`. The app sends `AF010203040506FF` every 10 minutes while connected. Firmware treats it as a READY request, without motor motion.

See [PROTOCOL.md](PROTOCOL.md) for commands and historical vendor-board details.

### Build

Android: open the project in Android Studio and build the `app` debug variant. Minimum Android SDK: 23; compile and target SDK: 35. Required permissions include BLE scan/connect, location, microphone, and vibration.

ESP32: open [esp32_c3_piano_motor.ino](firmware/esp32_c3_piano_motor/esp32_c3_piano_motor.ino), install Arduino-ESP32 board support, select `ESP32C3 Dev Module`, and set `USB CDC On Boot` to `Enabled`. See the firmware guide for download-mode and reset instructions.

Project document: [PIANO.docx](docs/PIANO.docx).

### Open-Source Acknowledgements

Thank you to the following authors, maintainers, and researchers for sharing the development foundations and piano-tuning research used or referenced by this project:

- **Espressif Systems and all Arduino-ESP32 contributors**: [Arduino-ESP32](https://github.com/espressif/arduino-esp32) provides the Arduino framework, GPIO/PWM, and BLE support used by the ESP32 firmware.
- **Neil Kolban and subsequent ESP32 BLE maintainers**: thank you for the open-source [ESP32 BLE work](https://github.com/nkolban/ESP32_BLE_Arduino) and continued maintenance of the [Arduino-ESP32 BLE library](https://github.com/espressif/arduino-esp32/tree/master/libraries/BLE). Firmware uses its BLEDevice, BLEServer, and BLECharacteristic interfaces.
- **Alain de Cheveigné and Hideki Kawahara**: thank you for the [YIN pitch-detection algorithm](https://pubmed.ncbi.nlm.nih.gov/12002874/). The app's `detectPitchYin` implements the YIN method; this acknowledgement credits the underlying research.
- **Haye Hinrichsen, Christoph Wick, and Entropy Piano Tuner contributors**: thank you for [Entropy Piano Tuner](https://gitlab.com/tp3/Entropy-Piano-Tuner) and its open-source work on piano recording, stretch tuning, and entropy-based tuning. Author attribution is documented in the [original copyright notice mirror](https://github.com/levush/Entropy-Piano-Tuner/blob/master/COPYRIGHT). This project references its workflow and research direction; it does not currently integrate its entropy optimizer.

Copyright and licenses for these projects and research belong to their respective authors. Acknowledgement does not alter upstream license terms or imply participation or endorsement by those authors.

### Usage Notes

This software controls a piano-tuning mechanism. Incorrect direction or excessive adjustment can damage strings and hardware. For initial testing, remove the wrench and verify forward/reverse operation, both limits, stop, and homing before calibrating speed and cents-response estimates.

---

## 中文

Android 钢琴调律辅助 APP，配合 ESP32-C3 蓝牙控制器、12V 有刷减速电机驱动板和两个限位开关。

项目作者：**MAX.YING · TROY HIGH SCHOOL · 2026**。

调节后音高反向、变化过小无法判断、或变化异常过大时，自动调律停止，不再自动交换电机方向。初始化回退时，原限位必须在暂定的 2 秒内释放，且不能重新触发。ESP32 本地定时运动与编号完成通知已接入固件及 APP，**两端构建与主机/源码检查通过，实物验证尚未完成。** 当前仍为开发原型，不是经过认证的安全控制器。详见[软件验证及空载检查清单](docs/SAFETY_VALIDATION.md)。

### 当前版本

- 麦克风实时音高检测及 88 键虚拟键盘。
- 圆形实时波形显示，完成标定后显示绿色波形；界面已不再使用旧的旋转圆环和指针图片。
- 蓝牙设备名称：`troy high school`，由 APP 扫描并连接，不再锁定旧控制板的 MAC 地址。
- ESP32 本地执行初始化、正反转及限位保护，并向 APP 回报状态。
- 根据所选音高和经验曲线估算调节时间，支持再次击键后的复测与修正。
- 错误琴键、行程不足或保护事件触发震动提示。
- 高音侧到限位后的棘轮扳手受保护回程及续调（`SAFE_V2`）。

ESP32 及新的电机、限位组合尚待实物联调。转速、圈数、位移和音分之间的关系目前是预设经验参数，并非编码器测量结果。

### 硬件与接线

| ESP32-C3 引脚 | 连接 |
| --- | --- |
| GPIO4 | 电机驱动板 IN1 |
| GPIO5 | 电机驱动板 IN2 |
| GPIO6 | 低音侧/左限位开关，另一端接 GND |
| GPIO7 | 高音侧/右限位开关，另一端接 GND |
| GND | 驱动板、降压模块和 12V 电源负极共地 |

12V 电源接电机驱动板，同时通过 12V 转 5V 降压模块给 ESP32 供电。GPIO 是 3.3V 信号，不能接入 12V。限位输入使用 `INPUT_PULLUP`：未触发为 HIGH，触发为 LOW。

完整接线、Arduino 设置、调试命令及到货测试顺序见 [ESP32 固件说明](firmware/esp32_c3_piano_motor/README.md)。旧控制板的参考资料保留在 [HARDWARE.md](HARDWARE.md)。

### 初始化与调律流程

1. APP 连接 `troy high school`，用户点击目标琴键。
2. APP 提示“取下扳手”；用户确认后发送 `A1F2` 加 16 位递增请求编号。
3. ESP32 持续向低音侧运行，碰到低音限位后停止，等待 200ms，再向高音侧回退。
4. 默认回退 10 秒，按预设 60 RPM 估算为 10 圈；收到编号一致的 `HOME_DONE:编号` 后 APP 提示“放入扳手”。
5. 放入扳手，点击 APP 的目标键，再按实体琴键。APP 确认稳定、接近目标的实际琴音后才开始调节。
6. 再次击键后复测，根据上次调节结果修正；满足完成条件时波形显示为绿色。

ESP32 在蓝牙断开时停止电机；触发某侧限位后禁止继续向该侧运动。两侧限位同时触发、寻找限位超时、回退碰到另一侧限位均会停止并发送保护状态。

### 棘轮扳手回程续调

向高音修正时碰到高音限位，`SAFE_V2` 固件发送编号一致的 `MOVE_HIGH:编号`，不是调律完成。APP 停机后要求重新击打所选实体键；确认仍偏低且变化合理，才发送 `A1F3` 加新编号，执行到低音限位的棘轮空回。空回不算“降音调节”，不学习为音分变化，也不要求取下扳手或回退 10 圈重新初始化。

到低音限位后，ESP32 停止并发送 `RATCH_DONE:编号`，APP 将该处作为位置零点。再次击键确认后，按剩余音分继续向高音侧定时调节；零点处不会再先往低音预转 6 圈。每次初始化后最多允许 **3 次回程**。音偏高、琴音不确定、变化异常、STOP、断连或回程保护均会停止。棘轮拨片处于调高方向时，反向为空回，不能依靠电机反转自动降低音高，需人工检查拨片方向。

回程包含 130ms 停机换向、高音原限位 2 秒释放超时、120 秒寻找低音限位超时，以及暂定的 1 秒最短全行程合理性检查。从低音零点再次向高音移动时，也检查低音限位确已释放。阈值、棘轮回程是否完全不带动弦轴均待实测，完整集成检查仍未完成，不能直接用于装着扳手的实琴自动调律。

### 声学新击键确认

电机控制不再只依据“又收到一帧稳定频率”，而是每次重新等待“安静—新起音—音高稳定”。停机后请松开琴键/踏板，至少安静 200ms，再击打所选琴键；暂定要求在安静后 300ms 内出现明显幅度上升，再经过至少 220ms 稳定音高，每次只产生一份控制测量。旧音尾音、电机/试听音期间、过期或丢失的音频帧不能刷新控制音高；按错键需重新安静再击键。后台、断连或 STOP 均取消等待许可。

这只是**声学启发式检测**，不能证明声音一定来自实体钢琴键。手机增益、环境噪声会影响阈值；持续噪声可能使系统一直等待，类似琴音的外部声音仍有风险。声音门控、音高确认及硬件保护都必须经过实物验证。

### 经验曲线

默认模型使用 60 RPM（1 圈/秒）、每圈推进约 1mm，以及每圈对应调律机构约 0.28° 的经验假设。音分/圈按以下锚点进行分段线性插值，音区之间连续变化：

| 琴键 | MIDI | 预设音分/圈 |
| --- | --- | --- |
| A0，低音端 | 21 | 2.8 |
| C4，中音参考 | 60 | 4.5 |
| C8，高音端 | 108 | 12.6 |

总行程按 40 圈估算。初始化回退 10 圈后，理论上保留向高音侧 30 圈的余量；默认低音方向预加载量为 6 圈。实际速度、负载、间隙及琴弦响应需要测量后校准，不能保证一次调节直接达到音准。

### 88 键录音

菜单中的 `Record 88 Keys` 支持从 A0 到 C8 的录音流程，每个键采集 3 个稳定音高样本并保存中位数。`Compute Tuning` 使用简化的拉伸调律曲线，尚未实现完整频谱非谐性分析或熵优化求解。

详细说明见 [RECORDING_WORKFLOW.md](RECORDING_WORKFLOW.md)。

### 蓝牙协议

服务 UUID：`0000ffe0-0000-1000-8000-00805f9b34fb`；通知特征为 `FFE1`，写入特征为 `FFE2`。

APP 通过通知接收限位状态和 `HOME_START`、`HOME_LOW_LIMIT`、`HOME_BACKOFF`、`HOME_DONE`、`HOME_ABORT`、`FAULT` 等状态。连接后每 10 分钟发送一次 `AF010203040506FF` 心跳，固件将其作为 READY 请求，不驱动电机。

命令及历史控制板协议见 [PROTOCOL.md](PROTOCOL.md)。

### 构建

Android：在 Android Studio 中打开本项目，构建 `app` 的 debug 版本。最低 Android SDK 为 23，编译及目标 SDK 为 35。APP 需要蓝牙扫描/连接、位置、麦克风及震动权限。

ESP32：打开 [esp32_c3_piano_motor.ino](firmware/esp32_c3_piano_motor/esp32_c3_piano_motor.ino)，安装 Arduino-ESP32 开发板支持，选择 `ESP32C3 Dev Module`，将 `USB CDC On Boot` 设为 `Enabled`。下载模式及复位方法见固件说明。

项目文档：[PIANO.docx](docs/PIANO.docx)。

### 开源作者致谢

感谢下列开源作者、维护者及研究者分享工作，为本项目提供开发基础和调律研究参考：

- **Espressif Systems 与 Arduino-ESP32 全体贡献者**：感谢 [Arduino-ESP32](https://github.com/espressif/arduino-esp32) 提供 ESP32 的 Arduino 开发框架、GPIO/PWM 和 BLE 支持。本项目 ESP32 固件依赖该框架。
- **Neil Kolban 及 ESP32 BLE 库的后续维护者**：感谢其开源的 [ESP32 BLE 工作](https://github.com/nkolban/ESP32_BLE_Arduino)，以及 [Arduino-ESP32 BLE 库](https://github.com/espressif/arduino-esp32/tree/master/libraries/BLE) 的持续维护。本固件使用其中的 BLEDevice、BLEServer、BLECharacteristic 等接口。
- **Alain de Cheveigné、Hideki Kawahara**：感谢提出 [YIN 音高检测算法](https://pubmed.ncbi.nlm.nih.gov/12002874/)。APP 中的 `detectPitchYin` 根据 YIN 方法实现；此处注明算法研究来源。
- **Haye Hinrichsen、Christoph Wick 及 Entropy Piano Tuner 贡献者**：感谢 [Entropy Piano Tuner](https://gitlab.com/tp3/Entropy-Piano-Tuner) 在钢琴录音、拉伸调律及熵调律研究方面的开源工作；作者署名可见 [原项目版权声明镜像](https://github.com/levush/Entropy-Piano-Tuner/blob/master/COPYRIGHT)。本项目将其作为调律流程和研究方向的参考，当前未集成其熵优化求解器。

以上项目和研究成果的版权与许可证归各自作者所有；致谢不改变上游许可条款，也不代表上游作者参与本项目或为本项目背书。

### 使用注意

本软件控制钢琴调律机构，错误方向或过量调节可能损伤琴弦及硬件。首次联调应取下扳手，先验证正反转、两个限位、停止和初始化，再校准转速与音分经验值。
