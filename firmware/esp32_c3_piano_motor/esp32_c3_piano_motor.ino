/*
  ESP32-C3 Piano Tuning Motor Controller

  Board:
    ESP32-C3 SuperMini / ESP32-C3 Dev Module

  Arduino IDE settings that are useful for many ESP32-C3 SuperMini boards:
    - Board: ESP32C3 Dev Module
    - USB CDC On Boot: Enabled
    - If upload port is unstable, enter download mode:
      hold BOOT -> press/release RESET -> release BOOT
    - After upload, press RESET once to run.

  Wiring:
    ESP32 GPIO4 -> motor driver IN1
    ESP32 GPIO5 -> motor driver IN2
    ESP32 GPIO6 -> low-note / left limit switch -> GND
    ESP32 GPIO7 -> high-note / right limit switch -> GND

  Power:
    12V -> motor driver motor power
    12V -> buck converter -> 5V -> ESP32 USB-C or 5V/VBUS pin
    ESP32 GND, motor driver GND, buck GND and 12V negative must be common.

  BLE compatibility:
    Device name: troy high school
    Service: 0000FFE0-0000-1000-8000-00805F9B34FB
    Notify:  0000FFE1-0000-1000-8000-00805F9B34FB
    Write:   0000FFE2-0000-1000-8000-00805F9B34FB

  Limit notifications:
    A1 01 01 = low/left limit pressed
    A1 01 02 = low/left limit released
    A1 02 01 = high/right limit pressed
    A1 02 02 = high/right limit released

  Motor commands accepted:
    A1010100000002xx1F = left / low direction hold
    A1020100000002xx1F = right / high direction hold
    A10102000000011F   = stop
    A10801xx           = speed, xx is hex speed 0..100
    A1F001             = ESP32-side home/init:
                         run left until low limit, then right for 10 seconds.

  Text commands accepted from BLE serial tools:
    LEFT, RIGHT, STOP, HOME, SPEED 50
*/

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <esp_arduino_version.h>

// ---------- Pins ----------
static const int PIN_MOTOR_IN1 = 4;
static const int PIN_MOTOR_IN2 = 5;
static const int PIN_LIMIT_LOW = 6;
static const int PIN_LIMIT_HIGH = 7;

// Avoid GPIO9: it is BOOT on many ESP32-C3 boards.
// If the real mechanism moves opposite to the labels, change this to true.
static const bool INVERT_MOTOR_DIRECTION = false;

// ---------- Motor tuning constants ----------
static const uint8_t DEFAULT_SPEED_PERCENT = 50;
static const uint32_t PWM_FREQ_HZ = 20000;
static const uint8_t PWM_RESOLUTION_BITS = 8;
static const uint8_t PWM_MAX_DUTY = 255;

#if ESP_ARDUINO_VERSION_MAJOR < 3
static const int PWM_CHANNEL_IN1 = 0;
static const int PWM_CHANNEL_IN2 = 1;
#endif

// Current mechanical estimate: 60 rpm = 1 rev/s.
static const uint32_t MOTOR_MS_PER_REV = 1000;
static const uint8_t HOME_BACKOFF_REVS = 10;
static const uint32_t HOME_BACKOFF_MS = HOME_BACKOFF_REVS * MOTOR_MS_PER_REV;
static const uint32_t HOME_SEEK_TIMEOUT_MS = 120000;
static const uint32_t HOME_BACKOFF_START_DELAY_MS = 200;

// ---------- BLE ----------
static const char *BLE_DEVICE_NAME = "troy high school";
static const char *UUID_SERVICE = "0000ffe0-0000-1000-8000-00805f9b34fb";
static const char *UUID_NOTIFY = "0000ffe1-0000-1000-8000-00805f9b34fb";
static const char *UUID_WRITE = "0000ffe2-0000-1000-8000-00805f9b34fb";

BLEServer *bleServer = nullptr;
BLECharacteristic *notifyCharacteristic = nullptr;
bool bleConnected = false;

// ---------- Runtime state ----------
enum class MotorDirection : uint8_t {
  STOPPED,
  LEFT_LOW,
  RIGHT_HIGH
};

enum class HomeState : uint8_t {
  IDLE,
  SEEK_LOW,
  BACKOFF_DELAY,
  BACKOFF_RIGHT
};

MotorDirection motorDirection = MotorDirection::STOPPED;
HomeState homeState = HomeState::IDLE;

uint8_t speedPercent = DEFAULT_SPEED_PERCENT;
uint32_t homeStateStartedAtMs = 0;

bool lowLimitStable = false;
bool highLimitStable = false;
bool lowLimitRawLast = false;
bool highLimitRawLast = false;
uint32_t lowLimitRawChangedAtMs = 0;
uint32_t highLimitRawChangedAtMs = 0;
bool bothLimitFaultLatched = false;
static const uint32_t LIMIT_DEBOUNCE_MS = 25;

void abortHomeSequence(const char *reason);

// ---------- Utility ----------
String bytesToHex(const uint8_t *data, size_t len) {
  static const char *hex = "0123456789ABCDEF";
  String out;
  out.reserve(len * 2);
  for (size_t i = 0; i < len; i++) {
    out += hex[(data[i] >> 4) & 0x0F];
    out += hex[data[i] & 0x0F];
  }
  return out;
}

String toUpperTrimmed(String value) {
  value.trim();
  value.toUpperCase();
  return value;
}

bool startsWithHex(const String &hex, const char *prefix) {
  return hex.startsWith(prefix);
}

uint8_t percentToDuty(uint8_t percent) {
  if (percent > 100) {
    percent = 100;
  }
  return (uint16_t)percent * PWM_MAX_DUTY / 100;
}

void pwmWriteIn1(uint8_t duty) {
#if ESP_ARDUINO_VERSION_MAJOR >= 3
  ledcWrite(PIN_MOTOR_IN1, duty);
#else
  ledcWrite(PWM_CHANNEL_IN1, duty);
#endif
}

void pwmWriteIn2(uint8_t duty) {
#if ESP_ARDUINO_VERSION_MAJOR >= 3
  ledcWrite(PIN_MOTOR_IN2, duty);
#else
  ledcWrite(PWM_CHANNEL_IN2, duty);
#endif
}

void notifyBytes(const uint8_t *data, size_t len) {
  if (!bleConnected || notifyCharacteristic == nullptr) {
    return;
  }
  notifyCharacteristic->setValue((uint8_t *)data, len);
  notifyCharacteristic->notify();
}

void notifyText(const char *text) {
  if (!bleConnected || notifyCharacteristic == nullptr) {
    return;
  }
  notifyCharacteristic->setValue((uint8_t *)text, strlen(text));
  notifyCharacteristic->notify();
}

void notifyStatus(const char *text) {
  Serial.print("Status: ");
  Serial.println(text);
  notifyText(text);
}

void notifyFault(const char *reason) {
  Serial.print("FAULT: ");
  Serial.println(reason);
  notifyText("FAULT");
}

void notifyLimit(bool lowSide, bool pressed) {
  uint8_t packet[3] = {
    0xA1,
    lowSide ? (uint8_t)0x01 : (uint8_t)0x02,
    pressed ? (uint8_t)0x01 : (uint8_t)0x02
  };
  notifyBytes(packet, sizeof(packet));

  Serial.print("Limit ");
  Serial.print(lowSide ? "LOW/LEFT" : "HIGH/RIGHT");
  Serial.println(pressed ? " pressed" : " released");
}

void stopMotor(const char *reason = nullptr) {
  pwmWriteIn1(0);
  pwmWriteIn2(0);
  motorDirection = MotorDirection::STOPPED;

  if (reason != nullptr) {
    Serial.print("Motor stop: ");
    Serial.println(reason);
  }
}

void writeMotorLeftLow(uint8_t duty) {
  if (INVERT_MOTOR_DIRECTION) {
    pwmWriteIn1(0);
    pwmWriteIn2(duty);
  } else {
    pwmWriteIn2(0);
    pwmWriteIn1(duty);
  }
}

void writeMotorRightHigh(uint8_t duty) {
  if (INVERT_MOTOR_DIRECTION) {
    pwmWriteIn2(0);
    pwmWriteIn1(duty);
  } else {
    pwmWriteIn1(0);
    pwmWriteIn2(duty);
  }
}

bool readLowLimitActive() {
  return digitalRead(PIN_LIMIT_LOW) == LOW;
}

bool readHighLimitActive() {
  return digitalRead(PIN_LIMIT_HIGH) == LOW;
}

bool moveLeftLow(const char *reason = nullptr) {
  if (lowLimitStable && highLimitStable) {
    stopMotor("both limits active");
    notifyFault("both limits active");
    return false;
  }
  if (lowLimitStable) {
    stopMotor("left blocked by low limit");
    notifyLimit(true, true);
    notifyStatus("LIMIT_LOW_BLOCK");
    return false;
  }

  uint8_t duty = percentToDuty(speedPercent);
  writeMotorLeftLow(duty);
  motorDirection = MotorDirection::LEFT_LOW;

  Serial.print("Motor LEFT/LOW");
  if (reason != nullptr) {
    Serial.print(": ");
    Serial.print(reason);
  }
  Serial.print(", speed=");
  Serial.println(speedPercent);
  return true;
}

bool moveRightHigh(const char *reason = nullptr) {
  if (lowLimitStable && highLimitStable) {
    stopMotor("both limits active");
    notifyFault("both limits active");
    return false;
  }
  if (highLimitStable) {
    stopMotor("right blocked by high limit");
    notifyLimit(false, true);
    notifyStatus("LIMIT_HIGH_BLOCK");
    return false;
  }

  uint8_t duty = percentToDuty(speedPercent);
  writeMotorRightHigh(duty);
  motorDirection = MotorDirection::RIGHT_HIGH;

  Serial.print("Motor RIGHT/HIGH");
  if (reason != nullptr) {
    Serial.print(": ");
    Serial.print(reason);
  }
  Serial.print(", speed=");
  Serial.println(speedPercent);
  return true;
}

void startHomeSequence() {
  Serial.println("Home sequence start");
  notifyStatus("HOME_START");

  if (lowLimitStable && highLimitStable) {
    abortHomeSequence("both limits active at home start");
    notifyFault("both limits active at home start");
    return;
  }

  if (lowLimitStable) {
    stopMotor("already at low limit");
    homeState = HomeState::BACKOFF_DELAY;
    homeStateStartedAtMs = millis();
    notifyLimit(true, true);
    notifyStatus("HOME_LOW_LIMIT");
    return;
  }

  homeState = HomeState::SEEK_LOW;
  homeStateStartedAtMs = millis();
  moveLeftLow("home seek low limit");
}

void finishHomeSequence() {
  homeState = HomeState::IDLE;
  stopMotor("home complete");
  notifyStatus("HOME_DONE");
  Serial.println("Home sequence complete");
}

void abortHomeSequence(const char *reason) {
  homeState = HomeState::IDLE;
  stopMotor(reason);
  notifyStatus("HOME_ABORT");
  Serial.print("Home aborted: ");
  Serial.println(reason);
}

void handleLimitSafety() {
  if (lowLimitStable && highLimitStable) {
    stopMotor("both limits active");
    if (homeState != HomeState::IDLE) {
      abortHomeSequence("both limits active");
    }
    if (!bothLimitFaultLatched) {
      bothLimitFaultLatched = true;
      notifyFault("both limits active");
    }
    return;
  }
  bothLimitFaultLatched = false;

  if (lowLimitStable && motorDirection == MotorDirection::LEFT_LOW) {
    stopMotor("low limit active");

    if (homeState == HomeState::SEEK_LOW) {
      homeState = HomeState::BACKOFF_DELAY;
      homeStateStartedAtMs = millis();
      notifyStatus("HOME_LOW_LIMIT");
      Serial.println("Home low limit reached; preparing backoff");
    }
  }

  if (highLimitStable && motorDirection == MotorDirection::RIGHT_HIGH) {
    stopMotor("high limit active");

    if (homeState == HomeState::BACKOFF_RIGHT) {
      notifyFault("high limit during backoff");
      abortHomeSequence("high limit during backoff");
    }
  }
}

void updateDebouncedLimit(bool lowSide, bool rawActive, bool &rawLast, uint32_t &rawChangedAtMs, bool &stable) {
  uint32_t now = millis();

  if (rawActive != rawLast) {
    rawLast = rawActive;
    rawChangedAtMs = now;
  }

  if (rawActive != stable && now - rawChangedAtMs >= LIMIT_DEBOUNCE_MS) {
    stable = rawActive;
    notifyLimit(lowSide, stable);
    handleLimitSafety();
  }
}

void pollLimits() {
  updateDebouncedLimit(true, readLowLimitActive(), lowLimitRawLast, lowLimitRawChangedAtMs, lowLimitStable);
  updateDebouncedLimit(false, readHighLimitActive(), highLimitRawLast, highLimitRawChangedAtMs, highLimitStable);
  handleLimitSafety();
}

void runHomeStateMachine() {
  if (homeState == HomeState::IDLE) {
    return;
  }

  uint32_t now = millis();

  switch (homeState) {
    case HomeState::SEEK_LOW:
      if (lowLimitStable) {
        stopMotor("home low limit reached");
        homeState = HomeState::BACKOFF_DELAY;
        homeStateStartedAtMs = now;
        notifyStatus("HOME_LOW_LIMIT");
      } else if (now - homeStateStartedAtMs > HOME_SEEK_TIMEOUT_MS) {
        notifyFault("home seek timeout");
        abortHomeSequence("home seek timeout");
      } else if (motorDirection != MotorDirection::LEFT_LOW) {
        moveLeftLow("home seek resume");
      }
      break;

    case HomeState::BACKOFF_DELAY:
      if (now - homeStateStartedAtMs >= HOME_BACKOFF_START_DELAY_MS) {
        homeState = HomeState::BACKOFF_RIGHT;
        homeStateStartedAtMs = now;
        notifyStatus("HOME_BACKOFF");
        moveRightHigh("home backoff 10 rev");
      }
      break;

    case HomeState::BACKOFF_RIGHT:
      if (highLimitStable) {
        notifyFault("high limit during home backoff");
        abortHomeSequence("high limit during home backoff");
      } else if (now - homeStateStartedAtMs >= HOME_BACKOFF_MS) {
        finishHomeSequence();
      } else if (motorDirection != MotorDirection::RIGHT_HIGH) {
        moveRightHigh("home backoff resume");
      }
      break;

    case HomeState::IDLE:
    default:
      break;
  }
}

void setSpeedPercent(uint8_t percent) {
  if (percent > 100) {
    percent = 100;
  }
  speedPercent = percent;

  Serial.print("Speed set to ");
  Serial.println(speedPercent);

  if (motorDirection == MotorDirection::LEFT_LOW) {
    moveLeftLow("speed update");
  } else if (motorDirection == MotorDirection::RIGHT_HIGH) {
    moveRightHigh("speed update");
  }
}

void handleHexCommand(const String &hex) {
  Serial.print("BLE hex command: ");
  Serial.println(hex);

  if (startsWithHex(hex, "AF010203040506FF")) {
    notifyText("READY");
    return;
  }

  if (startsWithHex(hex, "A10801") && hex.length() >= 8) {
    String speedHex = hex.substring(6, 8);
    uint8_t speed = (uint8_t)strtoul(speedHex.c_str(), nullptr, 16);
    setSpeedPercent(speed);
    return;
  }

  if (startsWithHex(hex, "A1F001")) {
    startHomeSequence();
    return;
  }

  if (startsWithHex(hex, "A10102") || startsWithHex(hex, "A10202")) {
    homeState = HomeState::IDLE;
    stopMotor("BLE stop");
    return;
  }

  if (startsWithHex(hex, "A10101")) {
    homeState = HomeState::IDLE;
    moveLeftLow("BLE left");
    return;
  }

  if (startsWithHex(hex, "A10201")) {
    homeState = HomeState::IDLE;
    moveRightHigh("BLE right");
    return;
  }

  Serial.println("Unknown hex command ignored");
}

void handleTextCommand(String command) {
  command = toUpperTrimmed(command);
  if (command.length() == 0) {
    return;
  }

  Serial.print("BLE text command: ");
  Serial.println(command);

  if (command == "LEFT") {
    homeState = HomeState::IDLE;
    moveLeftLow("text left");
  } else if (command == "RIGHT") {
    homeState = HomeState::IDLE;
    moveRightHigh("text right");
  } else if (command == "STOP") {
    homeState = HomeState::IDLE;
    stopMotor("text stop");
  } else if (command == "HOME" || command == "INIT") {
    startHomeSequence();
  } else if (command.startsWith("SPEED ")) {
    int value = command.substring(6).toInt();
    if (value < 0) {
      value = 0;
    }
    if (value > 100) {
      value = 100;
    }
    setSpeedPercent((uint8_t)value);
  } else {
    Serial.println("Unknown text command ignored");
  }
}

bool looksLikePrintableText(const std::string &value) {
  if (value.empty()) {
    return false;
  }

  for (char ch : value) {
    uint8_t c = (uint8_t)ch;
    if (c == '\r' || c == '\n' || c == '\t') {
      continue;
    }
    if (c < 0x20 || c > 0x7E) {
      return false;
    }
  }

  return true;
}

class MotorWriteCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    std::string value = characteristic->getValue();
    if (value.empty()) {
      return;
    }

    const uint8_t *data = (const uint8_t *)value.data();
    String hex = bytesToHex(data, value.length());

    if (looksLikePrintableText(value)) {
      String text;
      text.reserve(value.length());
      for (char ch : value) {
        text += ch;
      }
      handleTextCommand(text);
    } else {
      handleHexCommand(hex);
    }
  }
};

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    bleConnected = true;
    Serial.println("BLE connected");
  }

  void onDisconnect(BLEServer *server) override {
    bleConnected = false;
    homeState = HomeState::IDLE;
    stopMotor("BLE disconnected");
    Serial.println("BLE disconnected, advertising again");
    BLEDevice::startAdvertising();
  }
};

void setupPwm() {
#if ESP_ARDUINO_VERSION_MAJOR >= 3
  ledcAttach(PIN_MOTOR_IN1, PWM_FREQ_HZ, PWM_RESOLUTION_BITS);
  ledcAttach(PIN_MOTOR_IN2, PWM_FREQ_HZ, PWM_RESOLUTION_BITS);
#else
  ledcSetup(PWM_CHANNEL_IN1, PWM_FREQ_HZ, PWM_RESOLUTION_BITS);
  ledcSetup(PWM_CHANNEL_IN2, PWM_FREQ_HZ, PWM_RESOLUTION_BITS);
  ledcAttachPin(PIN_MOTOR_IN1, PWM_CHANNEL_IN1);
  ledcAttachPin(PIN_MOTOR_IN2, PWM_CHANNEL_IN2);
#endif

  stopMotor("boot");
}

void setupBle() {
  BLEDevice::init(BLE_DEVICE_NAME);
  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new ServerCallbacks());

  BLEService *service = bleServer->createService(UUID_SERVICE);

  notifyCharacteristic = service->createCharacteristic(
    UUID_NOTIFY,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );
  notifyCharacteristic->addDescriptor(new BLE2902());
  notifyCharacteristic->setValue("BOOT");

  BLECharacteristic *writeCharacteristic = service->createCharacteristic(
    UUID_WRITE,
    BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
  );
  writeCharacteristic->setCallbacks(new MotorWriteCallbacks());

  service->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(UUID_SERVICE);
  advertising->setScanResponse(true);
  advertising->setMinPreferred(0x06);
  advertising->setMinPreferred(0x12);
  BLEDevice::startAdvertising();

  Serial.print("BLE advertising as ");
  Serial.println(BLE_DEVICE_NAME);
}

void setup() {
  Serial.begin(115200);
  delay(400);
  Serial.println();
  Serial.println("ESP32-C3 Piano Tuning Motor Controller boot");

  pinMode(PIN_LIMIT_LOW, INPUT_PULLUP);
  pinMode(PIN_LIMIT_HIGH, INPUT_PULLUP);

  lowLimitRawLast = readLowLimitActive();
  highLimitRawLast = readHighLimitActive();
  lowLimitStable = lowLimitRawLast;
  highLimitStable = highLimitRawLast;
  lowLimitRawChangedAtMs = millis();
  highLimitRawChangedAtMs = millis();

  setupPwm();
  setupBle();

  Serial.print("Initial low limit: ");
  Serial.println(lowLimitStable ? "ACTIVE" : "released");
  Serial.print("Initial high limit: ");
  Serial.println(highLimitStable ? "ACTIVE" : "released");
}

void loop() {
  pollLimits();
  runHomeStateMachine();
  delay(5);
}
