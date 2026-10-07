# ESP32-C3 Piano Motor 固件

这版固件用于 `ESP32-C3 SuperMini + 双路直流电机驱动板 + 两个限位开关`。

## Arduino IDE 设置

建议先用这些设置：

- Board：`ESP32C3 Dev Module`
- USB CDC On Boot：`Enabled`
- 如果看不到 COM 口，按住 `BOOT` 再插 USB，或 `按住 BOOT → 按一下 RESET → 松开 RESET → 松开 BOOT`
- 上传成功后，按一下 `RESET` 才会运行
- 串口监视器：`115200`

## 接线

先用这四个 GPIO：

| ESP32-C3 | 连接 |
|---|---|
| GPIO4 | 电机驱动 IN1 |
| GPIO5 | 电机驱动 IN2 |
| GPIO6 | 低音/左限位开关，另一端接 GND |
| GPIO7 | 高音/右限位开关，另一端接 GND |
| GND | 电机驱动 GND / 降压模块 GND / 12V 负极 |

限位开关程序使用 `INPUT_PULLUP`：

- 没触发：HIGH
- 触发：LOW

## 供电

推荐：

```text
12V 电源输入
├── 电机驱动板电机电源
└── 12V 转 5V 降压模块
      └── ESP32-C3 USB-C 或 5V/VBUS

12V负极 / 降压GND / ESP32 GND / 电机驱动GND 必须共地
```

不要把 12V 接到 ESP32 GPIO。

## BLE

ESP32 会广播成：

```text
troy high school
```

BLE UUID：

```text
Service: 0000FFE0-0000-1000-8000-00805F9B34FB
Notify:  0000FFE1-0000-1000-8000-00805F9B34FB
Write:   0000FFE2-0000-1000-8000-00805F9B34FB
```

限位通知：

| 事件 | 通知 |
|---|---|
| 低音/左限位触发 | `A1 01 01` |
| 低音/左限位释放 | `A1 01 02` |
| 高音/右限位触发 | `A1 02 01` |
| 高音/右限位释放 | `A1 02 02` |

## 支持命令

兼容 APP 的二进制命令：

| 动作 | 命令 |
|---|---|
| 左/低音方向连续转 | `A1010100000002xx1F` |
| 右/高音方向连续转 | `A1020100000002xx1F` |
| 停止 | `A10102000000011F` |
| 设置速度 | `A10801xx` |
| ESP32 自己执行初始化 | `A1F001` |

也支持 BLE 调试工具发送文字：

```text
LEFT
RIGHT
STOP
HOME
INIT
SPEED 50
```

## 初始化逻辑

发送 `HOME` 或 `A1F001` 后：

```text
1. 电机向低音/左方向连续转
2. 低音限位触发后立即停止
3. 等 200ms
4. 向高音/右方向反转 10 秒
5. 停止，通知 HOME_DONE
```

当前默认假设：

```text
60 rpm = 1 圈/秒
反向 10 圈 = 10 秒
```

后面实际测试后，可以改 `MOTOR_MS_PER_REV`。

## 安全逻辑

ESP32 本地负责真正的电机安全，不依赖手机实时反应：

- BLE 断开：立即停止电机
- 低音限位触发时，禁止继续向低音方向转
- 高音限位触发时，禁止继续向高音方向转
- 两个限位同时触发：认为接线/机构异常，立即停止并发送 `FAULT`
- 初始化寻找低音限位超过 `HOME_SEEK_TIMEOUT_MS`：停止并发送 `HOME_ABORT`
- 初始化反向回退时碰到高音限位：停止并发送 `HOME_ABORT`

APP 现在只发送一个高级初始化命令：

```text
A1 F0 01
```

然后等待 ESP32 通知：

| 通知 | 含义 |
|---|---|
| `HOME_START` | 初始化开始 |
| `HOME_LOW_LIMIT` | 已碰到低音侧限位 |
| `HOME_BACKOFF` | 正在反向回退 |
| `HOME_DONE` | 初始化完成 |
| `HOME_ABORT` | 初始化失败 |
| `FAULT` | 限位/接线/机构保护 |

## 如果方向反了

先不要改接线，可以在固件里改这一行：

```cpp
static const bool INVERT_MOTOR_DIRECTION = false;
```

如果实际低音/高音方向反了，把它改成：

```cpp
static const bool INVERT_MOTOR_DIRECTION = true;
```

然后重新上传。

## 到货后的第一轮测试

1. 只接 ESP32，不接电机：确认手机能搜索到 `troy high school`
2. 接限位开关，不接电机：手按两个限位，看 APP Notify 和串口是否变化
3. 接电机驱动，不接电机：用万用表看 IN1/IN2 是否按命令变化
4. 接电机但不装扳手：测试 `LEFT`、`RIGHT`、`STOP`
5. 测初始化：低音方向转到限位，停，反向 10 秒
6. 如果方向反了，改 `INVERT_MOTOR_DIRECTION`
7. 如果 10 圈不等于 10 秒，修改 `MOTOR_MS_PER_REV`
