# FFT Pitch Engine / FFT 音高核心

## English

### Scope and signal path

`FftPitchDetector` replaces the previous quadratic time-domain detector. It consumes one **4096-sample, 44.1 kHz, mono PCM16** frame (about **92.9 ms**, unchanged from the old frame size), and returns a frequency or zero for an unconfirmed frame. Its **24–4800 Hz** range includes detuning below A0 and above C8. It has no selected-key, motor, Android or Bluetooth dependency, no previous-pitch lock, and no target-frequency pulling. It estimates monophonic periodicity, not the identity of a piano/string/voice.

1. Remove the frame's DC mean. Reject DC-removed RMS below 0.01, incomplete input, and frames with more than 1% of samples at the PCM rails (absolute value at least 32760).
2. Compute linear autocorrelation using an **8192-point radix-2 FFT**, squared magnitudes and inverse FFT. Zero padding prevents circular wrap; it does not add audio samples or remove the frame acquisition delay. Prefix sums provide exact paired energies. The difference is corrected for the number of overlapping pairs:

   ```text
   d(tau) = [E(0,N-tau) + E(tau,N) - 2*autocorrelation(tau)] / (N-tau)
   cmnd(tau) = d(tau)*tau / sum(d(1)..d(tau))
   ```

3. Normalize **all** lags before valley selection or interpolation. The old code compared normalized values with not-yet-normalized neighbors; its interpolation could use those raw values as well. Shorter periods have priority, with bounded, materially better multiple-period checks. Locally centered/normalized correlation reduces amplitude-decay bias when refining a low-note period.
4. Use a second, Hann-windowed spectrum for partial consistency. The greatest common divisor of supported partial indices helps reject suboctave/submultiple aliases; a 3/6/9 pattern must not invent a base frequency three times too low. High notes use bounded logarithmic spectral peak interpolation instead of relying on coarse integer lag spacing. A missing fundamental can be refined only when at least two coprime partial indices agree within 3 cents. Ordinary refinement is limited to 20 cents; correcting a supported period multiple permits a 100-cent local peak search. These are detector rules, not guarantees about acoustic source identity or all possible piano spectra.
5. Reuse all DSP arrays and FFT plans in one capture-thread-owned instance per recording generation. The measured warmed Java core allocates no per-frame objects on the tested HotSpot counter; the Android/UI pipeline as a whole is **not** claimed allocation-free. No microphone PCM array is queued to the UI now that the view uses a needle rather than a waveform.
6. `PcmFrameBuffer` assembles short reads into complete frames in sample order. Backward time, a read gap over 500 ms, or an incomplete frame spanning over 500 ms discards the old partial data. A discontinuity resets pitch stability/new-strike gating. Generation, paused-state and stale-frame guards remain in place, as do the quiet/attack/new-key-strike motor interlocks. Firmware, motor timing, BLE commands and the dial/needle geometry are unchanged.

This is a project-specific **YIN-style** implementation, not a bit-identical implementation of the original paper. Research credit: Alain de Cheveigné and Hideki Kawahara, [YIN, a fundamental frequency estimator for speech and music (2002)](https://pubmed.ncbi.nlm.nih.gov/12002874/), DOI 10.1121/1.1458024. The local radix-2 FFT does not introduce a third-party DSP binary or copied library implementation.

### Reproduce

```powershell
./tools/test-pitch.ps1
./tools/test-safety.ps1
./tools/test-motor-core.ps1
# Optional: explicit, authorized USB phone only. Synthetic data, no APK/UI/mic/BLE/root access.
./tools/test-pitch-device.ps1 -Serial YOUR_USB_SERIAL
```

Scripts accept Java/SDK paths. The device diagnostic requires Android SDK Build Tools (default 34.0.0), android-35 platform files, and an already authorized USB serial. It compiles a small DEX diagnostic under ignored `build/`, runs it in Android ART through `app_process`, and removes only its uniquely named temporary device archive. It neither installs a test APK nor invokes the app activity. It is a core-runtime test, not an Android capture/UI integration or physical test.

`LegacyYinBaseline.java` freezes the old `b5d6629` method (including its bugs and old frequency endpoints) for comparison **only**; it is not compiled into the APP. No baseline detector is used as a production fallback.

### Observed results (2026-10-26)

- **4717 synthetic pitch/numerical assertions** pass on the host and Xiaomi M2101K9C ART. Cases cover all 88 keys, detuning, phase changes, dominant harmonics, selected missing-fundamental fixtures, seeded random-frequency holdouts, noise/decay/DC, mild stretched partials, rejection fixtures, recovery and all 1839 computed difference lags against direct sums (absolute error below 1e-10). Passing fixtures is not proof for arbitrary noise or piano recordings.
- **24692 PCM framing assertions** pass on both runtimes; most are sample-by-sample ordering comparisons, not independent acoustic scenarios. They also cover short reads, full-frame reuse, long gaps, excessive total frame span, backward time and invalid input.
- Existing 459 motor/audio behavioral checks and 56 image/geometry checks pass. There are now 46 **structural** source contracts. Structural checks are not runtime integration tests.

One deterministic 264-frame comparison (88 keys × pure/harmonic/noisy fixtures) produced:

| Metric | Frozen old YIN | New FFT core |
| --- | ---: | ---: |
| Median absolute pitch error | 63.039 cents | 0.007 cents |
| 95th-percentile absolute error | 169.461 cents | 0.112 cents |
| Rejected frames | 4 | 0 |
| Accepted errors greater than 600 cents | 4 | 0 |

The old/new endpoints differ, and synthetic spectra are intentionally controlled; these numbers must not be represented as real-piano accuracy. In the separate noiseless pure-tone grid the new worst error was 0.0578 cents, and in the fixed harmonic/decay grid it was 0.0504 cents. Neither is an advertised microphone or piano tuning specification.

Warmed single-thread timing observations, with alternating old/new measurement order:

| Runtime | Old median / p95 | FFT median / p95 | Median ratio |
| --- | ---: | ---: | ---: |
| Host amd64, Java 21.0.9, 240 timed frames each | 6548.8 / 7753.1 us | 756.8 / 1022.7 us | 8.65× |
| Xiaomi M2101K9C Android ART, 96 timed frames each | 4572.6 / 4607.2 us | 847.3 / 896.0 us | 5.40× |

These are **core computation** timings for generated frames, not end-to-end microphone/display latency, battery measurements or hard real-time deadlines. JIT, thermal state and other workloads affect repeated runs. The approximately 92.9 ms acquisition window, multi-frame stability and fresh-strike waiting still apply. No performance ratio is used as a flaky test pass/fail gate.

### Still unverified

The v1.4 APK (version code 6) was installed on the same USB Xiaomi phone and launched. A startup screenshot confirmed the textured dial, original pivot geometry and foreground note medallion are retained; the crash buffer showed no app startup crash. This smoke check does not validate microphone pitch accuracy or a physical key-to-target workflow.

Real piano recordings across registers, inharmonic bass strings, beating multi-string unisons, changing room noise, voiced speech, phone microphone frequency response/clock error and end-to-end capture delay still need measurement. The algorithm is not an entropy/stretch-curve optimizer, polyphonic separator, or voice-removal system. Human voices and other periodic sources can be mistaken for a pitch; strong noise or ambiguity can produce no measurement. Do not weaken the motor safety gates to compensate. No test here connects BLE or drives a motor.

## 中文

### 范围与处理链

`FftPitchDetector` 替换旧的逐点时域检测。输入为 **4096 点、44.1 kHz、单声道 PCM16**（约 **92.9 ms**，未加长采样窗），输出频率或未确认时的零值。检测范围为 **24–4800 Hz**，包括低于 A0、高于 C8 的跑音范围。不依赖用户选定琴键、历史音高、电机或蓝牙，不把测量值拉向目标音；只能估计单音周期，不能证明声音来自钢琴或某根弦。

1. 去除直流均值；直流去除后 RMS 低于 0.01、输入不完整、超过 1% 样本达到 PCM 极限（绝对值至少 32760）时拒绝。
2. 用 **8192 点 radix-2 FFT、功率谱及逆变换**计算线性自相关，前缀和计算配对能量。补零防止循环卷积混叠，并不增加实际录音长度或消除采样等待。差分除以每个延迟实际重叠的样本对数，再做累积均值归一化，公式见英文部分。
3. **所有延迟完整归一化后**才寻谷和插值。旧版可能把已经归一化的点与尚未归一化的相邻点比较，插值也可能读到原始值。默认优先较短周期，仅在有明显改善与谐波支持时检查倍周期；局部去均值、归一化相关降低低音衰减造成的周期偏差。
4. 另用 Hann 窗频谱检查分音一致性，通过有支持的分音序号最大公约数处理低八度/三倍周期等误判。高音区用有边界的对数谱峰插值；基音缺失时，至少两个互质分音序号推算的基频须相差不超过 3 音分才用于细化。普通细化限 20 音分，确认倍周期纠正时允许 100 音分局部峰搜索。这些规则不能保证识别人声或任意钢琴频谱。
5. 每次录音代次由收音线程独占一个检测器，复用全部 FFT 与工作数组。测试主机 HotSpot 计数器显示预热后的核心无逐帧分配，但**不声称整个 Android/UI 流程零分配**。由于显示已改为指针，不再每帧复制 PCM 数组到 UI。
6. `PcmFrameBuffer` 按顺序拼接短读取，仅分析完整帧；时间倒退、读取间隔超过 500 ms 或未完成帧总跨度超过 500 ms 时丢弃旧片段，并重置音高稳定/新击键门控。保留录音代次、后台及旧帧过滤和电机静音/起音/真实新击键规则。固件、电机时序、蓝牙命令以及表盘/指针定位均未改变。

这是本项目自行编写的 **YIN 型**实现，不是论文原版逐位相同的参考代码。感谢 Alain de Cheveigné 与 Hideki Kawahara 的 [YIN 研究](https://pubmed.ncbi.nlm.nih.gov/12002874/)（2002，DOI 10.1121/1.1458024）。本地 FFT 未新增第三方 DSP 二进制依赖或复制库实现。

### 复现与验证结果（2026-10-26）

运行英文部分列出的脚本。可选 `test-pitch-device.ps1` 必须明确指定已授权的 USB 手机，默认需 Build Tools 34.0.0 及 android-35 平台。脚本只在忽略的 `build/` 内编译合成音诊断，通过 ART 的 `app_process` 执行并清理本次唯一命名的临时文件；不安装测试 APK、不启动 APP Activity、不访问录音/蓝牙、不使用 root。它不是完整 APP 收音或实体测试。

`LegacyYinBaseline.java` 仅冻结 `b5d6629` 的旧方法（保留旧问题及频率边界）用于对照，不进入 APP，也不作为生产降级算法。

- 主机和小米 M2101K9C ART 均通过 **4717 项合成音/数值断言**：88 键、跑音、相位、强谐波、部分缺失基音样本、固定随机种子的不同频率样本、噪声/衰减/直流、轻度分音拉伸、拒绝与恢复；全部 1839 个差分延迟与直接求和相比绝对误差小于 1e-10。
- 两端均通过 **24692 项 PCM 拼帧断言**，多数是逐采样顺序比较，并非数万个独立声学场景；另覆盖短读取、复用、长间隔、总跨度过长、时间倒退与非法输入。
- 原 459 项电机/音频行为检查、56 项图片/定位检查通过；源码结构检查增至 46 项。源码检查不是运行时集成验证。

同一组 264 帧（88 键 × 纯音/谐波/含噪样本）中，旧版音分绝对误差中位数 63.039、95 分位 169.461，拒绝 4 帧、已接受结果中超过 600 音分误差 4 帧；新核心对应中位数 0.007、95 分位 0.112，未拒绝、未见超过 600 音分误差。纯音网格最坏误差 0.0578 音分，固定谐波/衰减网格最坏误差 0.0504 音分。新旧频率边界不同、合成频谱受控，**这些数字绝非真实钢琴/麦克风精度指标**。

预热后的主机核心中位耗时 6.549 ms → 0.757 ms（约 8.65 倍）；小米手机 ART 中位耗时 4.573 ms → 0.847 ms（约 5.40 倍），详情与 95 分位见英文表格。测量交替执行新旧代码，但仍受 JIT、温度和其它任务影响。这只是**核心计算时间**，不是完整收音/显示延迟、功耗或硬实时承诺；92.9 ms 采样窗、多帧稳定和新击键等待仍然存在，不把速度比例作为自动测试通过门槛。

### 尚未验证

v1.4 APK（versionCode 6）已安装至同一台 USB 小米手机并启动，启动截图确认蓝色底纹、原圆心定位和前景音名圆圈仍在，崩溃缓冲区未见本应用启动崩溃。此冒烟检查不验证麦克风测音精度或实体琴键与目标音的完整交互。

仍需实测各音区钢琴录音、低音弦非谐性、多弦同音拍频、房间噪声、人声、手机麦克风响应/时钟偏差及完整收音延迟。此算法不是熵/拉伸曲线优化器、多音分离或人声消除系统。人声等周期性声音仍可能被当作有效音高；强噪声和歧义可能导致无读数，不能为此放松电机安全门控。上述测试未连接蓝牙、未驱动电机。
