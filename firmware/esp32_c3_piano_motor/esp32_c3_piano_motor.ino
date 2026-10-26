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

  Motor commands accepted (exact binary lengths; see PROTOCOL.md):
    A1F100            = capabilities -> SAFE_V2
    A1F2hhhh          = tagged HOME (nonzero increasing request ID)
    A1F3hhhh          = protected ratchet return after raising-side end event
    A1F1ddhhhhpppprrrr = timed sequence: final direction, ID, preload ms, run ms
    A1010100000002xx1F = left / low manual hold (750ms lease, 30s ceiling)
    A1020100000002xx1F = right / high manual hold (same lease)
    A10102000000011F   = stop
    A10801xx           = speed, xx is hex speed 0..100
  Text STOP is also accepted. Old untagged HOME/text motion commands are rejected.
*/

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <esp_arduino_version.h>
#include "MotorSafetyCore.h"
#include "SafeMotorProtocol.h"
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>

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
bool controllerHomed = false;
bool pwmReady = false;
piano::TimedMove timedMove;
piano::RatchetReturn ratchetReturn;
piano::HomeReleaseGuard ratchetLowRelease;
bool ratchetResultReported = true;
bool atRatchetLow = false;
bool timedOriginReleaseFault = false;
piano::RequestIds requestIds;
piano::ManualHoldLease manualHold;
bool timedResultReported = true;
uint16_t homeRequestId = 0;

// BLE callbacks never mutate PWM, homing, or motion state. A session/STOP epoch
// prevents queued commands from an old connection or before STOP restarting it.
struct QueuedCommand {
  uint8_t data[20];
  uint8_t size;
  uint32_t session;
  uint32_t epoch;
  uint32_t queuedAtMs;
};
QueueHandle_t commandQueue = nullptr;
portMUX_TYPE linkMux = portMUX_INITIALIZER_UNLOCKED;
bool callbackConnected = false;
bool stopRequested = false;
bool queueFailed = false;
uint32_t callbackSession = 0;
uint32_t callbackEpoch = 0;
uint32_t controlSession = 0;
uint32_t controlEpoch = 0;

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
MotorDirection lastDriveDirection = MotorDirection::STOPPED;
uint32_t lastDriveStoppedAtMs = 0;
HomeState homeState = HomeState::IDLE;

uint8_t speedPercent = DEFAULT_SPEED_PERCENT;
uint32_t homeStateStartedAtMs = 0;
piano::HomeReleaseGuard homeReleaseGuard;

bool lowLimitStable = false;
bool highLimitStable = false;
bool lowLimitRawLast = false;
bool highLimitRawLast = false;
uint32_t lowLimitRawChangedAtMs = 0;
uint32_t highLimitRawChangedAtMs = 0;
bool bothLimitFaultLatched = false;
static const uint32_t LIMIT_DEBOUNCE_MS = 25;

void abortHomeSequence(const char *reason);
void abortAllMotion(const char *reason, bool report);
void runRatchetMotion();

bool isCurrentLink() {
  portENTER_CRITICAL(&linkMux);
  bool current = callbackConnected && !stopRequested && !queueFailed
      && callbackSession == controlSession && callbackEpoch == controlEpoch;
  portEXIT_CRITICAL(&linkMux);
  return current;
}

// ---------- Utility ----------
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
  if (!bleConnected || !isCurrentLink() || notifyCharacteristic == nullptr) {
    return;
  }
  notifyCharacteristic->setValue((uint8_t *)data, len);
  notifyCharacteristic->notify();
}

void notifyText(const char *text) {
  if (!bleConnected || !isCurrentLink() || notifyCharacteristic == nullptr) {
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

void notifyTagged(const char *event, uint16_t id) {
  char packet[21];
  snprintf(packet, sizeof(packet), "%s:%u", event, (unsigned)id);
  notifyStatus(packet);
}

void notifyHome(const char *event) {
  notifyTagged(event, homeRequestId);
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
  if (motorDirection != MotorDirection::STOPPED) lastDriveStoppedAtMs = millis();
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
  if (!pwmReady || !bleConnected || !isCurrentLink()) return false;
  if (lowLimitStable && highLimitStable) {
    stopMotor("both limits active");
    notifyFault("both limits active");
    return false;
  }
  if (lowLimitStable || readLowLimitActive()) {
    stopMotor("left blocked by low limit");
    notifyLimit(true, true);
    notifyStatus("LIMIT_LOW_BLOCK");
    return false;
  }

  if (motorDirection == MotorDirection::RIGHT_HIGH) { stopMotor("reversal interlock"); return false; }
  if (lastDriveDirection == MotorDirection::RIGHT_HIGH
      && millis() - lastDriveStoppedAtMs < piano::TimedMove::ReverseDelayMs) return false;

  uint8_t duty = percentToDuty(speedPercent);
  writeMotorLeftLow(duty);
  motorDirection = MotorDirection::LEFT_LOW;
  lastDriveDirection = motorDirection;

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
  if (!pwmReady || !bleConnected || !isCurrentLink()) return false;
  if (lowLimitStable && highLimitStable) {
    stopMotor("both limits active");
    notifyFault("both limits active");
    return false;
  }
  if (highLimitStable || readHighLimitActive()) {
    stopMotor("right blocked by high limit");
    notifyLimit(false, true);
    notifyStatus("LIMIT_HIGH_BLOCK");
    return false;
  }

  if (motorDirection == MotorDirection::LEFT_LOW) { stopMotor("reversal interlock"); return false; }
  if (lastDriveDirection == MotorDirection::LEFT_LOW
      && millis() - lastDriveStoppedAtMs < piano::TimedMove::ReverseDelayMs) return false;

  uint8_t duty = percentToDuty(speedPercent);
  writeMotorRightHigh(duty);
  motorDirection = MotorDirection::RIGHT_HIGH;
  lastDriveDirection = motorDirection;

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
  stopMotor("new home request");
  if (speedPercent == 0) {
    abortHomeSequence("home speed is zero");
    notifyFault("home speed is zero");
    return;
  }
  Serial.println("Home sequence start");
  controllerHomed = false;
  notifyHome("HOME_START");

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
    notifyHome("HOME_LOW_LIMIT");
    return;
  }

  homeState = HomeState::SEEK_LOW;
  homeStateStartedAtMs = millis();
  moveLeftLow("home seek low limit");
}

void finishHomeSequence() {
  homeState = HomeState::IDLE;
  stopMotor("home complete");
  controllerHomed = true;
  ratchetReturn.newTuningSession();
  atRatchetLow = false;
  notifyHome("HOME_DONE");
  homeRequestId = 0;
  Serial.println("Home sequence complete");
}

void abortHomeSequence(const char *reason) {
  homeState = HomeState::IDLE;
  stopMotor(reason);
  controllerHomed = false;
  notifyHome("HOME_ABORT");
  homeRequestId = 0;
  Serial.print("Home aborted: ");
  Serial.println(reason);
}

void handleLimitSafety() {
  if (lowLimitStable && highLimitStable) {
    stopMotor("both limits active");
    controllerHomed = false;
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
      notifyHome("HOME_LOW_LIMIT");
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
  if (!isCurrentLink()) { abortAllMotion("stale HOME link", false); return; }

  uint32_t now = millis();

  switch (homeState) {
    case HomeState::SEEK_LOW:
      if (lowLimitStable) {
        stopMotor("home low limit reached");
        homeState = HomeState::BACKOFF_DELAY;
        homeStateStartedAtMs = now;
        notifyHome("HOME_LOW_LIMIT");
      } else if (now - homeStateStartedAtMs > HOME_SEEK_TIMEOUT_MS) {
        abortHomeSequence("home seek timeout");
        notifyFault("home seek timeout");
      } else if (motorDirection != MotorDirection::LEFT_LOW) {
        moveLeftLow("home seek resume");
      }
      break;

    case HomeState::BACKOFF_DELAY:
      if (now - homeStateStartedAtMs >= HOME_BACKOFF_START_DELAY_MS) {
        homeState = HomeState::BACKOFF_RIGHT;
        homeStateStartedAtMs = now;
        homeReleaseGuard.begin(now);
        notifyHome("HOME_BACKOFF");
        moveRightHigh("home backoff 10 rev");
      }
      break;

    case HomeState::BACKOFF_RIGHT:
      if (highLimitStable) {
        abortHomeSequence("high limit during home backoff");
        notifyFault("high limit during home backoff");
      } else if (!homeReleaseGuard.update(now, lowLimitStable)) {
        stopMotor("origin limit stuck or reactivated");
        notifyHome("HOME_LOW_STUCK");
        abortHomeSequence("origin limit stuck or reactivated");
      } else if (now - homeStateStartedAtMs >= HOME_BACKOFF_MS) {
        if (homeReleaseGuard.released() && !lowLimitStable) {
          finishHomeSequence();
        } else {
          abortHomeSequence("origin limit release not confirmed");
        }
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

void reportTimedResult() {
  if (timedResultReported || timedMove.active()) return;
  stopMotor("timed move terminal");
  timedResultReported = true;
  const char *event = "MOVE_ABORT";
  if (timedMove.result() == piano::MoveResult::Done && !timedOriginReleaseFault) {
    event = "MOVE_DONE";
  } else {
    controllerHomed = false;
    if (timedMove.result() == piano::MoveResult::Limit) event = "MOVE_LIMIT";
    if (timedMove.result() == piano::MoveResult::HighLimit) {
      event = ratchetReturn.armAtHigh() ? "MOVE_HIGH" : "MOVE_LIMIT";
    }
  }
  atRatchetLow = false;
  notifyTagged(event, timedMove.id());
}

void abortAllMotion(const char *reason, bool report) {
  stopMotor(reason);  // always remove drive before serial/BLE reporting
  controllerHomed = false;
  manualHold.stop();
  timedMove.cancel();
  ratchetReturn.cancel();
  atRatchetLow = false;
  if (report) reportTimedResult();
  else timedResultReported = true;
  if (report) runRatchetMotion();
  else ratchetResultReported = true;
  if (homeState != HomeState::IDLE) {
    if (report) abortHomeSequence(reason);
    else { homeState = HomeState::IDLE; homeRequestId = 0; }
  }
}

void runTimedMotion() {
  if (!timedMove.active()) return;
  if (atRatchetLow && !ratchetLowRelease.update(millis(), lowLimitStable)) {
    timedOriginReleaseFault = true;
    timedMove.cancel();
  }
  // Raw activation inhibits drive immediately; stable edges are used for HOME.
  timedMove.tick(millis(), lowLimitStable || readLowLimitActive(),
                 highLimitStable || readHighLimitActive(), bleConnected && isCurrentLink());
  if (atRatchetLow && timedMove.result() == piano::MoveResult::Done && !ratchetLowRelease.released()) {
    timedOriginReleaseFault = true;
  }
  piano::Direction output = timedMove.output();
  if (output == piano::Direction::Stop) {
    stopMotor();
  } else if (output == piano::Direction::Low && motorDirection != MotorDirection::LEFT_LOW) {
    if (!moveLeftLow("local timed move")) timedMove.cancel(piano::MoveResult::Limit);
  } else if (output == piano::Direction::High && motorDirection != MotorDirection::RIGHT_HIGH) {
    if (!moveRightHigh("local timed move")) timedMove.cancel(piano::MoveResult::Limit);
  }
  reportTimedResult();
}

void runRatchetMotion() {
  if (ratchetResultReported) return;
  if (ratchetReturn.active()) {
    ratchetReturn.tick(millis(), lowLimitStable, highLimitStable, bleConnected && isCurrentLink());
    if (ratchetReturn.output() == piano::Direction::Low && !readLowLimitActive()) {
      if (motorDirection != MotorDirection::LEFT_LOW) moveLeftLow("ratchet freewheel return");
    } else stopMotor();
  }
  if (!ratchetReturn.active()) {
    stopMotor("ratchet return terminal");
    ratchetResultReported = true;
    bool done = ratchetReturn.result() == piano::RatchetResult::Done;
    controllerHomed = done;
    atRatchetLow = done;
    notifyTagged(done ? "RATCH_DONE" : "RATCH_ABORT", ratchetReturn.id());
  }
}

void runManualMotion() {
  if (!isCurrentLink()) { abortAllMotion("stale manual link", false); return; }
  piano::Direction before = manualHold.output();
  manualHold.tick(millis());
  piano::Direction output = manualHold.output();
  if (output == piano::Direction::Stop) {
    if (before != piano::Direction::Stop) {
      stopMotor("manual lease expired");
      controllerHomed = false;
      notifyStatus("HOLD_TIMEOUT");
    }
    return;
  }
  bool blocked = (output == piano::Direction::Low && (lowLimitStable || readLowLimitActive()))
      || (output == piano::Direction::High && (highLimitStable || readHighLimitActive()));
  if (blocked) {
    stopMotor("manual limit");
    manualHold.stop();
    controllerHomed = false;
    notifyStatus("HOLD_LIMIT");
    return;
  }
  if (output == piano::Direction::Low && motorDirection != MotorDirection::LEFT_LOW) moveLeftLow("manual hold");
  if (output == piano::Direction::High && motorDirection != MotorDirection::RIGHT_HIGH) moveRightHigh("manual hold");
}

bool motionBusy() {
  return timedMove.active() || ratchetReturn.active() || homeState != HomeState::IDLE
      || manualHold.output() != piano::Direction::Stop;
}

void dispatchCommand(const QueuedCommand &packet) {
  piano::Command command = piano::decodeCommand(packet.data, packet.size);
  if (command.type == piano::CommandType::Capabilities) {
    notifyStatus("SAFE_V2");
    notifyLimit(true, lowLimitStable);
    notifyLimit(false, highLimitStable);
    return;
  }
  if (command.type == piano::CommandType::Ready) { notifyStatus("READY"); return; }
  if (command.type == piano::CommandType::Invalid) {
    abortAllMotion("invalid command", true);
    notifyStatus("ERR_FRAME");
    return;
  }
  if (command.type == piano::CommandType::Stop) {
    if (motionBusy()) abortAllMotion("STOP", true);
    else { stopMotor("STOP idle"); manualHold.stop(); ratchetReturn.cancel(); }
    return;
  }
  if (!pwmReady || !bleConnected || !isCurrentLink()) { notifyTagged("ERR_OUTPUT", command.id); return; }
  if (millis() - packet.queuedAtMs > 250) {
    abortAllMotion("expired queued command", true);
    notifyTagged("ERR_EXPIRED", command.id);
    return;
  }
  if (command.type == piano::CommandType::Speed) {
    if (motionBusy()) { notifyTagged("ERR_BUSY", command.id); return; }
    setSpeedPercent(command.speed);
    return;
  }
  if (speedPercent == 0) { notifyTagged("ERR_SPEED", command.id); return; }
  if (command.type == piano::CommandType::Hold) {
    if (timedMove.active() || ratchetReturn.active() || homeState != HomeState::IDLE) {
      notifyTagged("ERR_BUSY", 0);
      return;
    }
    controllerHomed = false;
    ratchetReturn.cancel();
    atRatchetLow = false;
    if (!manualHold.refresh(command.direction, millis())) {
      stopMotor("hold rejected");
      notifyStatus("ERR_HOLD");
      return;
    }
    runManualMotion();
    return;
  }
  if (!requestIds.isFresh(command.id)) { notifyTagged("ERR_STALE", command.id); return; }
  if (motionBusy()) { notifyTagged("ERR_BUSY", command.id); return; }
  if (manualHold.expired()) { notifyTagged("ERR_HOLD", command.id); return; }
  if (command.type == piano::CommandType::Home) {
    requestIds.accept(command.id);
    ratchetReturn.cancel();
    atRatchetLow = false;
    homeRequestId = command.id;
    startHomeSequence();
    return;
  }
  if (command.type == piano::CommandType::Ratchet) {
    if (!ratchetReturn.start(command.id, lowLimitStable || readLowLimitActive(),
        highLimitStable || readHighLimitActive(), bleConnected, millis())) {
      notifyTagged("ERR_RATCH", command.id);
      return;
    }
    requestIds.accept(command.id);
    controllerHomed = false;
    atRatchetLow = false;
    ratchetResultReported = false;
    notifyTagged("RATCH_START", command.id);
    runRatchetMotion();
    return;
  }
  if (!controllerHomed) { notifyTagged("ERR_HOME", command.id); return; }
  if (atRatchetLow && (command.direction != piano::Direction::High || command.preloadMs != 0)) {
    notifyTagged("ERR_RATCH", command.id);
    return;
  }
  piano::StartResult accepted = timedMove.start(command.id, command.direction,
      command.runMs, command.preloadMs, millis());
  if (accepted != piano::StartResult::Accepted) { notifyTagged("ERR_MOVE", command.id); return; }
  requestIds.accept(command.id);
  timedOriginReleaseFault = false;
  if (atRatchetLow) ratchetLowRelease.begin(millis());
  timedResultReported = false;
  // Validate limits before enabling the first output. START is not completion.
  timedMove.tick(millis(), lowLimitStable || readLowLimitActive(),
      highLimitStable || readHighLimitActive(), bleConnected);
  if (timedMove.active()) notifyTagged("MOVE_START", command.id);
  runTimedMotion();
  reportTimedResult();
}

void serviceLinkAndCommands() {
  bool connected, stop, failed;
  uint32_t session, epoch;
  portENTER_CRITICAL(&linkMux);
  connected = callbackConnected;
  stop = stopRequested;
  failed = queueFailed;
  session = callbackSession;
  epoch = callbackEpoch;
  stopRequested = false;
  queueFailed = false;
  portEXIT_CRITICAL(&linkMux);

  bool newSession = session != controlSession;
  if (newSession || stop || failed) {
    if (newSession || failed || motionBusy()) abortAllMotion("link/STOP barrier", !newSession && connected);
    else { stopMotor("idle STOP barrier"); manualHold.stop(); ratchetReturn.cancel(); }
  }
  bleConnected = connected;
  controlSession = session;
  controlEpoch = epoch;
  if (newSession) {
    requestIds.newSession();
    timedMove.newSession();
    ratchetReturn.newTuningSession();
    atRatchetLow = false;
    if (!connected) BLEDevice::startAdvertising();
  }
  if (failed && connected) notifyStatus("ERR_QUEUE");

  QueuedCommand packet;
  for (int count = 0; count < 4 && xQueueReceive(commandQueue, &packet, 0) == pdTRUE; ++count) {
    bool current;
    portENTER_CRITICAL(&linkMux);
    current = callbackConnected && !stopRequested && !queueFailed
        && packet.session == callbackSession && packet.epoch == callbackEpoch;
    portEXIT_CRITICAL(&linkMux);
    if (current && packet.session == controlSession && packet.epoch == controlEpoch) dispatchCommand(packet);
  }
}

class MotorWriteCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    auto value = characteristic->getValue(); // Arduino-ESP32 2.x std::string / 3.x String
    size_t length = value.length();
    const uint8_t *data = (const uint8_t *)value.c_str();
    bool stop = piano::decodeCommand(data, length <= 20 ? (uint8_t)length : 0).type == piano::CommandType::Stop
        || (length == 4 && memcmp(data, "STOP", 4) == 0);
    QueuedCommand packet = {};
    portENTER_CRITICAL(&linkMux);
    if (!callbackConnected) { portEXIT_CRITICAL(&linkMux); return; }
    if (stop || length == 0 || length > sizeof(packet.data)) {
      ++callbackEpoch;
      stopRequested = true;
      if (!stop) queueFailed = true;
      portEXIT_CRITICAL(&linkMux);
      return;
    }
    packet.session = callbackSession;
    packet.epoch = callbackEpoch;
    portEXIT_CRITICAL(&linkMux);
    packet.size = (uint8_t)length;
    packet.queuedAtMs = millis();
    memcpy(packet.data, data, length);
    if (xQueueSend(commandQueue, &packet, 0) != pdTRUE) {
      portENTER_CRITICAL(&linkMux);
      ++callbackEpoch;
      queueFailed = true;
      stopRequested = true;
      portEXIT_CRITICAL(&linkMux);
    }
  }
};

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    portENTER_CRITICAL(&linkMux);
    callbackConnected = true;
    ++callbackSession;
    ++callbackEpoch;
    stopRequested = true;
    portEXIT_CRITICAL(&linkMux);
  }

  void onDisconnect(BLEServer *server) override {
    portENTER_CRITICAL(&linkMux);
    callbackConnected = false;
    ++callbackSession;
    ++callbackEpoch;
    stopRequested = true;
    portEXIT_CRITICAL(&linkMux);
  }
};

void setupPwm() {
#if ESP_ARDUINO_VERSION_MAJOR >= 3
  bool in1Ready = ledcAttach(PIN_MOTOR_IN1, PWM_FREQ_HZ, PWM_RESOLUTION_BITS);
  bool in2Ready = ledcAttach(PIN_MOTOR_IN2, PWM_FREQ_HZ, PWM_RESOLUTION_BITS);
  pwmReady = in1Ready && in2Ready;
#else
  double in1Frequency = ledcSetup(PWM_CHANNEL_IN1, PWM_FREQ_HZ, PWM_RESOLUTION_BITS);
  double in2Frequency = ledcSetup(PWM_CHANNEL_IN2, PWM_FREQ_HZ, PWM_RESOLUTION_BITS);
  pwmReady = in1Frequency > 0 && in2Frequency > 0;
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
  commandQueue = xQueueCreate(8, sizeof(QueuedCommand));
  if (commandQueue == nullptr || !pwmReady) {
    stopMotor("controller setup failed");
    Serial.println("Controller unavailable: queue/PWM setup failed");
    while (true) delay(1000); // never advertise an unusable motor controller
  }
  setupBle();

  Serial.print("Initial low limit: ");
  Serial.println(lowLimitStable ? "ACTIVE" : "released");
  Serial.print("Initial high limit: ");
  Serial.println(highLimitStable ? "ACTIVE" : "released");
}

void loop() {
  serviceLinkAndCommands();
  pollLimits();
  runHomeStateMachine();
  runTimedMotion();
  runRatchetMotion();
  runManualMotion();
  delay(5);
}
