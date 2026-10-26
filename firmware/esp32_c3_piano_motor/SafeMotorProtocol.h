#pragma once

#include "MotorSafetyCore.h"

namespace piano {

enum class CommandType : uint8_t { Invalid, Capabilities, Move, Home, Ratchet, Stop, Speed, Hold, Ready };

struct Command {
  CommandType type = CommandType::Invalid;
  uint16_t id = 0;
  Direction direction = Direction::Stop;
  uint16_t runMs = 0;
  uint16_t preloadMs = 0;
  uint8_t speed = 0;
};

inline uint16_t readU16(const uint8_t *bytes) {
  return (uint16_t)(((uint16_t)bytes[0] << 8) | bytes[1]);
}

// Exact-length binary frames only. No prefix-only acceptance or truncation.
inline Command decodeCommand(const uint8_t *p, uint8_t size) {
  Command c;
  if (!p) return c;
  if (size == 8 && p[0] == 0xAF && p[1] == 1 && p[2] == 2 && p[3] == 3
      && p[4] == 4 && p[5] == 5 && p[6] == 6 && p[7] == 0xFF) {
    c.type = CommandType::Ready;
    return c;
  }
  if (size < 3 || p[0] != 0xA1) return c;
  if (size == 3 && p[1] == 0xF1 && p[2] == 0) {
    c.type = CommandType::Capabilities;
  } else if (size == 4 && p[1] == 0xF2) {
    c.id = readU16(p + 2);
    if (c.id) c.type = CommandType::Home;
  } else if (size == 4 && p[1] == 0xF3) {
    c.id = readU16(p + 2);
    if (c.id) c.type = CommandType::Ratchet;
  } else if (size == 9 && p[1] == 0xF1 && (p[2] == 1 || p[2] == 2)) {
    c.id = readU16(p + 3);
    c.preloadMs = readU16(p + 5);
    c.runMs = readU16(p + 7);
    if (c.id && c.runMs && c.runMs <= TimedMove::MaxRunMs
        && c.preloadMs <= TimedMove::MaxPreloadMs) {
      c.direction = (Direction)p[2];
      c.type = CommandType::Move;
    }
  } else if (size == 8 && (p[1] == 1 || p[1] == 2) && p[2] == 2
             && p[3] == 0 && p[4] == 0 && p[5] == 0 && p[6] == 1 && p[7] == 0x1F) {
    c.type = CommandType::Stop;
  } else if (size == 4 && p[1] == 8 && p[2] == 1 && p[3] <= 100) {
    c.type = CommandType::Speed;
    c.speed = p[3];
  } else if (size == 9 && (p[1] == 1 || p[1] == 2) && p[2] == 1
             && p[3] == 0 && p[4] == 0 && p[5] == 0 && p[6] == 2
             && p[7] <= 100 && p[8] == 0x1F) {
    c.type = CommandType::Hold;
    c.direction = (Direction)p[1];
  }
  return c;
}

// Shared across homing and timed moves, not reset by STOP or completion.
class RequestIds {
 public:
  bool isFresh(uint16_t id) const { return id && id > last_; }
  bool accept(uint16_t id) {
    if (!isFresh(id)) return false;
    last_ = id;
    return true;
  }
  void newSession() { last_ = 0; }
 private:
  uint16_t last_ = 0;
};

// Manual HOLD refreshes expire locally; heartbeats never refresh this lease.
// Direction changes and a 30-second ceiling require an explicit STOP first.
class ManualHoldLease {
 public:
  static constexpr uint32_t RefreshTimeoutMs = 750;
  static constexpr uint32_t MaxContinuousMs = 30000;
  bool refresh(Direction direction, uint32_t now) {
    tick(now);
    if (locked_ || (direction != Direction::Low && direction != Direction::High)) return false;
    if (direction_ != Direction::Stop && direction_ != direction) {
      expire();
      return false;
    }
    if (direction_ == Direction::Stop) started_ = now;
    direction_ = direction;
    refreshed_ = now;
    return true;
  }
  void tick(uint32_t now) {
    if (direction_ != Direction::Stop
        && (now - refreshed_ >= RefreshTimeoutMs || now - started_ >= MaxContinuousMs)) expire();
  }
  void stop() { direction_ = Direction::Stop; locked_ = false; }
  Direction output() const { return direction_; }
  bool expired() const { return locked_; }
 private:
  void expire() { direction_ = Direction::Stop; locked_ = true; }
  Direction direction_ = Direction::Stop;
  uint32_t started_ = 0;
  uint32_t refreshed_ = 0;
  bool locked_ = false;
};

}  // namespace piano
