# Safety Validation / 安全验证

## English

### Software checks (2026-10-26)

Both builds succeed for this development snapshot:

- Android: Gradle 8.7, Android Gradle Plugin 8.5, compile/target SDK 35, debug APK. Existing SDK/plugin and Java 8-target deprecation warnings remain.
- Firmware: Arduino-ESP32 **3.3.8**, `esp32:esp32:esp32c3:CDCOnBoot=cdc`. Compile only, no serial port or device access. Current image uses 629,687 / 1,310,720 bytes flash (48%) and 18,980 / 327,680 bytes static RAM (5%).

Behavioral tests: **459 checks**, including 16 tuning-response checks, 80 basic request/session checks, 82 ratchet-session checks, 95 acoustic-strike-gate checks, 81 C++ motor-core checks, and 105 C++ parser/manual-lease checks. Another **32 structural source contracts** check that these helpers are connected to production control paths. Structural checks are not runtime integration tests.

The same C++ core/parser sources used by firmware are compiled to WebAssembly with Android NDK LLVM and run on Node for host simulation. The Java tests run without Android or Bluetooth. They exercise local time limits, replay rejection, limit/disconnect/abort behavior, ratchet eligibility/cycle caps, callback wiring, and quiet/onset/one-sample gating. Passing them does not verify radio delivery, real-time scheduling, electrical signals, physical travel, torque, acoustic source identity, or piano tuning accuracy.

### Reproduce

```powershell
./tools/test-safety.ps1
./tools/test-motor-core.ps1
./tools/build-firmware.ps1
```

Build the Android `app` debug variant in Android Studio or with an installed compatible Gradle. The scripts accept installed runtime paths as parameters where needed. Install the pinned Espressif core separately; the firmware script only compiles and never uploads. Generated APKs, firmware binaries and caches belong under ignored build directories, not source control.

### Physical validation is NOT complete

Do not test automatically on a piano with the wrench engaged. First test unloaded: startup output disabled, correct direction, explicit STOP, both limit switches, stuck/retriggered switch, missing switch, lease expiry, phone pause/crash, BLE disconnect/reconnect, homing, and ratchet return/cycle exhaustion. Verify that freewheel return truly does not rotate the tuning pin and that the selector matches the raising direction. Confirm the original high limit releases during return and the low limit releases on the next raising stroke.

Measure loaded speed, stopping distance, mechanical backlash, RPM/travel/cents response, and microphone thresholds. The 60 RPM, 1mm/revolution, 0.28° and register response anchors are estimates. A microphone heuristic cannot prove which physical key/string made a sound. Similar tones or noise may still cause false detection; continuous noise may prevent any confirmation. GPIO/software interlocks are not a replacement for independent hardware limits, current/torque protection and an accessible physical stop. Timer-based travel estimates are not encoder measurements.

## 中文

### 软件验证（2026-10-26）

Android debug 构建及 Arduino-ESP32 **3.3.8** 的 ESP32-C3 固件编译均通过。固件配置为 `esp32:esp32:esp32c3:CDCOnBoot=cdc`，只编译、不选串口、不连接设备、不烧录。当前固件占用 Flash 629,687 / 1,310,720 字节（48%），静态 RAM 18,980 / 327,680 字节（5%）。Android 原有工具链兼容/弃用警告仍存在。

共 **459 项行为逻辑测试**通过：音高响应 16、基础会话/编号 80、棘轮会话 82、声学起音门控 95、C++ 电机核心 81、C++ 协议/手动租约 105。另有 **32 项源码结构检查**确认生产入口连接到这些保护逻辑；结构检查不是运行时集成测试。C++ 核心在主机编译为 WebAssembly 后模拟执行，Java 测试不使用 Android/蓝牙，不能代表实物验证。

### 复现及实物门槛

运行上方三个脚本，并在 Android Studio 构建 app 的 debug 版本。固件脚本不执行上传；先单独安装固定版本的开发板支持。构建产物与缓存不应提交到仓库。

**尚未完成实物验证，不能直接装着扳手在钢琴上自动测试。** 必须先空载检查启动无输出、正反方向、STOP、两个限位、卡住/重触发/缺失开关、手动租约、手机后台/崩溃、断连重连、初始化和棘轮回程上限。确认棘轮空回确实不带动弦轴、拨片处于调高方向；回程能释放高音限位，续调能释放低音限位。

再测负载转速、停车距离、间隙、圈数/位移/音分曲线及手机声音阈值。现有 60 RPM、每圈 1mm、0.28° 与音区响应系数均为估算。麦克风不能证明声音来自哪个实体键/琴弦；噪声、相近音仍有误判风险。软件/GPIO 限位不能代替独立硬件限位、过流/扭矩保护与实体急停，时间推算位置不等于编码器测量。
