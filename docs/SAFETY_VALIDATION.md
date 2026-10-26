# Safety Validation / 安全验证

## English

### Software checks (2026-10-26)

Both builds succeed for this development snapshot:

- Android: Gradle 8.7, Android Gradle Plugin 8.5, compile/target SDK 35, debug APK. Existing SDK/plugin and Java 8-target deprecation warnings remain.
- Firmware: Arduino-ESP32 **3.3.8**, `esp32:esp32:esp32c3:CDCOnBoot=cdc`. Compile only, no serial port or device access. Current image uses 629,687 / 1,310,720 bytes flash (48%) and 18,980 / 327,680 bytes static RAM (5%).

Motor/audio behavioral tests: **459 checks**, including 16 tuning-response checks, 80 basic request/session checks, 82 ratchet-session checks, 95 acoustic-strike-gate checks, 81 C++ motor-core checks, and 105 C++ parser/manual-lease checks. Another **40 structural source contracts** cover production control wiring and the needle display. Structural checks are not runtime integration tests.

The same C++ core/parser sources used by firmware are compiled to WebAssembly with Android NDK LLVM and run on Node for host simulation. The Java tests run without Android or Bluetooth. They exercise local time limits, replay rejection, limit/disconnect/abort behavior, ratchet eligibility/cycle caps, callback wiring, and quiet/onset/one-sample gating. Passing them does not verify radio delivery, real-time scheduling, electrical signals, physical travel, torque, acoustic source identity, or piano tuning accuracy.

### Needle display 1.3 (2026-10-26)

The Android debug build passes with the original supplied `zhen.png` and `rotating_dial.png`, copied byte-for-byte without raster edits. An additional **56 geometry/asset checks** cover 100 cents = 20 degrees, sign, display clamping at ±200 cents / ±40 degrees, invalid numbers, aspect ratio, transparency and the black-dot pivots. The foreground note medallion is drawn after the needle and covers its tail; no fixed 12-o'clock line is drawn. The dial artwork retains its original angle numerals, not cents labels; the digital cents readout remains authoritative. Waiting/stale measurements dim the needle rather than reporting a zero-cent success.

Version code 4 / version 1.3 was installed over USB on a Xiaomi M2101K9C (1080×2400), launched, and visually checked in a device screenshot for alignment, foreground layering and the waiting state. No app crash was observed in the crash buffer. This is an installation/static-display smoke check, **not** an acoustic accuracy, live needle-response, BLE or motor test. Motor/audio regression checks also pass; firmware sources are unchanged.

### Texture refresh 1.3.1 (2026-10-26)

The updated 1024×1024 dial PNG includes the user's blue interior texture. Only the outside is circularly clipped; the interior is no longer cut out. The original dial pivot **(514.5, 512.5)** and needle pivot **(40.5, 401.5)**, scale and foreground note circle remain unchanged. Tests assert the locked dial coordinates rather than searching for its now-covered black dot; the 56 geometry/asset checks and 40 source contracts pass. Android builds successfully, and version code 5 / 1.3.1 was installed and screenshot-checked on the same USB Xiaomi phone. No motor command was sent; live acoustic/motor verification remains incomplete.

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

共 **459 项电机/音频行为逻辑测试**通过：音高响应 16、基础会话/编号 80、棘轮会话 82、声学起音门控 95、C++ 电机核心 81、C++ 协议/手动租约 105。另有 **40 项源码结构检查**覆盖生产保护入口与指针显示；结构检查不是运行时集成测试。C++ 核心在主机编译为 WebAssembly 后模拟执行，Java 测试不使用 Android/蓝牙，不能代表实物验证。

### 指针界面 1.3（2026-10-26）

Android debug 构建通过；`zhen.png`、`rotating_dial.png` 原图逐字节复制，未修改像素。新增 **56 项几何/图片检查**，覆盖每 100 音分转 20 度、左右方向、显示范围 ±200 音分/±40 度、非法数字、宽高比、透明度与两个黑点圆心。音名圆圈后绘制，盖住针尾；不再绘制固定 12 点直线。表盘保留原图角度数字，并非音分标签，精确偏差以数字音分为准。等待/过期读数时指针变暗，不显示为零音分成功。

版本号 1.3 / versionCode 4 已通过 USB 安装至小米 M2101K9C（1080×2400）并启动，已查看真机截图确认圆心、遮盖层级及等待状态；崩溃缓冲区未见本应用崩溃。这仅是安装与静态界面检查，**不代表收音精度、实时指针响应、蓝牙或电机测试**。电机/音频回归检查通过，固件源码未改动。

### 底纹更新 1.3.1（2026-10-26）

更新后的 1024×1024 大圈 PNG 带用户提供的蓝色底纹，仅裁剪圆外部分，不再挖空圆内底纹。沿用原大圈圆心 **(514.5, 512.5)**、指针圆心 **(40.5, 401.5)**、缩放比例与前景音名圆圈。测试改为检查锁定的大圈坐标，不再查找被底纹盖住的黑点；56 项几何/图片检查及 40 项源码检查通过。Android 构建成功，versionCode 5 / 1.3.1 已安装至同一台 USB 小米手机并查看截图确认显示。未发送电机指令，实琴收音及电机验证仍未完成。

### 复现及实物门槛

运行上方三个脚本，并在 Android Studio 构建 app 的 debug 版本。固件脚本不执行上传；先单独安装固定版本的开发板支持。构建产物与缓存不应提交到仓库。

**尚未完成实物验证，不能直接装着扳手在钢琴上自动测试。** 必须先空载检查启动无输出、正反方向、STOP、两个限位、卡住/重触发/缺失开关、手动租约、手机后台/崩溃、断连重连、初始化和棘轮回程上限。确认棘轮空回确实不带动弦轴、拨片处于调高方向；回程能释放高音限位，续调能释放低音限位。

再测负载转速、停车距离、间隙、圈数/位移/音分曲线及手机声音阈值。现有 60 RPM、每圈 1mm、0.28° 与音区响应系数均为估算。麦克风不能证明声音来自哪个实体键/琴弦；噪声、相近音仍有误判风险。软件/GPIO 限位不能代替独立硬件限位、过流/扭矩保护与实体急停，时间推算位置不等于编码器测量。
