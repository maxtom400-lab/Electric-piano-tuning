# MAX Piano Tuner

Android 钢琴调律辅助 APP，配合 ESP32-C3 蓝牙控制器、12V 有刷减速电机驱动板和两个限位开关。

项目作者：**MAX.YING · TROY HIGH SCHOOL · 2026**。

## 当前版本

- 麦克风实时音高检测及 88 键虚拟键盘。
- 圆形实时波形显示，完成标定后显示绿色波形；已移除旧的旋转圆环和指针图片。
- 蓝牙设备名称：`troy high school`，由 APP 扫描并连接，不再锁定旧控制板的 MAC 地址。
- ESP32 本地执行初始化、正反转及限位保护，并向 APP 回报状态。
- 根据所选音高和经验曲线估算调节时间，支持再次击键后的复测与修正。
- 错误琴键、行程不足或保护事件触发震动提示。

ESP32 及新的电机、限位组合尚待实物联调。转速、圈数、位移和音分之间的关系目前是预设经验参数，并非编码器测量结果。

## 硬件与接线

| ESP32-C3 引脚 | 连接 |
| --- | --- |
| GPIO4 | 电机驱动板 IN1 |
| GPIO5 | 电机驱动板 IN2 |
| GPIO6 | 低音侧/左限位开关，另一端接 GND |
| GPIO7 | 高音侧/右限位开关，另一端接 GND |
| GND | 驱动板、降压模块和 12V 电源负极共地 |

12V 电源接电机驱动板，同时通过 12V 转 5V 降压模块给 ESP32 供电。GPIO 是 3.3V 信号，不能接入 12V。限位输入使用 `INPUT_PULLUP`：未触发为 HIGH，触发为 LOW。

完整接线、Arduino 设置、调试命令及到货测试顺序见 [ESP32 固件说明](firmware/esp32_c3_piano_motor/README.md)。旧控制板的参考资料保留在 [HARDWARE.md](HARDWARE.md)。

## 初始化与调律流程

1. APP 连接 `troy high school`，用户点击目标琴键。
2. APP 提示“取下扳手”；用户确认后发送 `A1F001`。
3. ESP32 持续向低音侧运行，碰到低音限位后停止，等待 200ms，再向高音侧回退。
4. 默认回退 10 秒，按预设 60 RPM 估算为 10 圈；收到 `HOME_DONE` 后 APP 提示“放入扳手”。
5. 放入扳手，点击 APP 的目标键，再按实体琴键。APP 确认稳定、接近目标的实际琴音后才开始调节。
6. 再次击键后复测，根据上次调节结果修正；满足完成条件时波形显示为绿色。

ESP32 在蓝牙断开时停止电机；触发某侧限位后禁止继续向该侧运动。两侧限位同时触发、寻找限位超时、回退碰到另一侧限位均会停止并发送保护状态。

## 经验曲线

默认模型使用 60 RPM（1 圈/秒）、每圈推进约 1mm，以及每圈对应调律机构约 0.28° 的经验假设。音分/圈按以下锚点进行分段线性插值，音区之间连续变化：

| 琴键 | MIDI | 预设音分/圈 |
| --- | --- | --- |
| A0，低音端 | 21 | 2.8 |
| C4，中音参考 | 60 | 4.5 |
| C8，高音端 | 108 | 12.6 |

总行程按 40 圈估算。初始化回退 10 圈后，理论上保留向高音侧 30 圈的余量；默认低音方向预加载量为 6 圈。实际速度、负载、间隙及琴弦响应需要测量后校准，不能保证一次调节直接达到音准。

## 88 键录音

菜单中的 `Record 88 Keys` 支持从 A0 到 C8 的录音流程，每个键采集 3 个稳定音高样本并保存中位数。`Compute Tuning` 使用简化的拉伸调律曲线，尚未实现完整频谱非谐性分析或熵优化求解。

详细说明见 [RECORDING_WORKFLOW.md](RECORDING_WORKFLOW.md)。

## 蓝牙协议

服务 UUID：`0000ffe0-0000-1000-8000-00805f9b34fb`；通知特征为 `FFE1`，写入特征为 `FFE2`。

APP 通过通知接收限位状态和 `HOME_START`、`HOME_LOW_LIMIT`、`HOME_BACKOFF`、`HOME_DONE`、`HOME_ABORT`、`FAULT` 等状态。连接后每 10 分钟发送一次 `AF010203040506FF` 心跳，固件将其作为 READY 请求，不驱动电机。

命令及历史控制板协议见 [PROTOCOL.md](PROTOCOL.md)。

## 构建

Android：在 Android Studio 中打开本项目，构建 `app` 的 debug 版本。最低 Android SDK 为 23，编译及目标 SDK 为 35。APP 需要蓝牙扫描/连接、位置、麦克风及震动权限。

ESP32：打开 [esp32_c3_piano_motor.ino](firmware/esp32_c3_piano_motor/esp32_c3_piano_motor.ino)，安装 Arduino-ESP32 开发板支持，选择 `ESP32C3 Dev Module`，将 `USB CDC On Boot` 设为 `Enabled`。下载模式及复位方法见固件说明。

项目文档：[PIANO.docx](docs/PIANO.docx)。

## 开源作者致谢 / Acknowledgements

感谢下列开源作者、维护者及研究者分享工作，为本项目提供开发基础和调律研究参考：

- **Espressif Systems 与 Arduino-ESP32 全体贡献者**：感谢 [Arduino-ESP32](https://github.com/espressif/arduino-esp32) 提供 ESP32 的 Arduino 开发框架、GPIO/PWM 和 BLE 支持。本项目 ESP32 固件依赖该框架。
- **Neil Kolban 及 ESP32 BLE 库的后续维护者**：感谢其开源的 [ESP32 BLE 工作](https://github.com/nkolban/ESP32_BLE_Arduino)，以及 [Arduino-ESP32 BLE 库](https://github.com/espressif/arduino-esp32/tree/master/libraries/BLE) 的持续维护。本固件使用其中的 BLEDevice、BLEServer、BLECharacteristic 等接口。
- **Alain de Cheveigné、Hideki Kawahara**：感谢提出 [YIN 音高检测算法](https://pubmed.ncbi.nlm.nih.gov/12002874/)。APP 中的 `detectPitchYin` 根据 YIN 方法实现；此处注明算法研究来源。
- **Haye Hinrichsen、Christoph Wick 及 Entropy Piano Tuner 贡献者**：感谢 [Entropy Piano Tuner](https://gitlab.com/tp3/Entropy-Piano-Tuner) 在钢琴录音、拉伸调律及熵调律研究方面的开源工作；作者署名可见 [原项目版权声明镜像](https://github.com/levush/Entropy-Piano-Tuner/blob/master/COPYRIGHT)。本项目将其作为调律流程和研究方向的参考，当前未集成其熵优化求解器。

以上项目和研究成果的版权与许可证归各自作者所有；致谢不改变上游许可条款，也不代表上游作者参与本项目或为本项目背书。

## 使用注意

本软件控制钢琴调律机构，错误方向或过量调节可能损伤琴弦及硬件。首次联调应取下扳手，先验证正反转、两个限位、停止和初始化，再校准转速与音分经验值。
