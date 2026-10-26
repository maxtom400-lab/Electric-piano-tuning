#pragma once

#include <stdint.h>

// Hardware-independent state machines. All calls must run on the controller's
// single motor-control thread; BLE callbacks should enqueue commands only.
namespace piano {

enum class Direction : uint8_t { Stop = 0, Low = 1, High = 2 };
enum class MovePhase : uint8_t { Idle, Preload, ReversalDelay, Correction };
enum class StartResult : uint8_t { Accepted, Invalid, Busy, Stale };
enum class MoveResult : uint8_t { None, Done, Aborted, Limit, HighLimit, Disconnected };

class TimedMove {
 public:
  static constexpr uint32_t MaxRunMs = 20000;
  static constexpr uint32_t MaxPreloadMs = 10000;
  static constexpr uint32_t ReverseDelayMs = 130;

  // IDs increase within a BLE session. No wrap/retry during that session:
  // reconnecting requires homing again in the application layer.
  StartResult start(uint16_t id, Direction direction, uint32_t runMs,
                    uint32_t preloadMs, uint32_t now) {
    if (id == 0 || (direction != Direction::Low && direction != Direction::High)
        || runMs == 0 || runMs > MaxRunMs || preloadMs > MaxPreloadMs) {
      return StartResult::Invalid;
    }
    if (id <= lastId_) return StartResult::Stale;
    if (active()) return StartResult::Busy;
    lastId_ = id;
    id_ = id;
    direction_ = direction;
    runMs_ = runMs;
    preloadMs_ = preloadMs;
    started_ = now;
    result_ = MoveResult::None;
    phase_ = preloadMs ? MovePhase::Preload : MovePhase::Correction;
    return StartResult::Accepted;
  }

  void tick(uint32_t now, bool lowActive, bool highActive, bool connected) {
    if (!active()) return;
    if (!connected) { cancel(MoveResult::Disconnected); return; }
    Direction motion = output();
    if ((lowActive && highActive)
        || (motion == Direction::Low && lowActive)
        || (motion == Direction::High && highActive)) {
      bool ratchetEnd = !lowActive && highActive && motion == Direction::High
          && phase_ == MovePhase::Correction && direction_ == Direction::High;
      cancel(ratchetEnd ? MoveResult::HighLimit : MoveResult::Limit);
      return;
    }
    uint32_t elapsed = now - started_;  // unsigned millis rollover is safe
    if (phase_ == MovePhase::Preload && elapsed >= preloadMs_) {
      phase_ = MovePhase::ReversalDelay;
      started_ = now;
    } else if (phase_ == MovePhase::ReversalDelay && elapsed >= ReverseDelayMs) {
      // Check the destination limit BEFORE enabling the correction output.
      if ((direction_ == Direction::Low && lowActive)
          || (direction_ == Direction::High && highActive)) {
        cancel(MoveResult::Limit);
      } else {
        phase_ = MovePhase::Correction;
        started_ = now;
      }
    } else if (phase_ == MovePhase::Correction && elapsed >= runMs_) {
      cancel(MoveResult::Done);
    }
  }

  void cancel(MoveResult reason = MoveResult::Aborted) {
    if (!active()) return;
    phase_ = MovePhase::Idle;
    result_ = reason;
  }

  // Only after motor output is stopped on a NEW connection, never per command.
  void newSession() { *this = TimedMove(); }
  bool active() const { return phase_ != MovePhase::Idle; }
  uint16_t id() const { return id_; }
  MovePhase phase() const { return phase_; }
  MoveResult result() const { return result_; }
  Direction output() const {
    if (phase_ == MovePhase::Preload) {
      return direction_ == Direction::High ? Direction::Low : Direction::High;
    }
    return phase_ == MovePhase::Correction ? direction_ : Direction::Stop;
  }

 private:
  uint16_t id_ = 0;
  uint16_t lastId_ = 0;
  uint32_t started_ = 0;
  uint32_t runMs_ = 0;
  uint32_t preloadMs_ = 0;
  Direction direction_ = Direction::Stop;
  MovePhase phase_ = MovePhase::Idle;
  MoveResult result_ = MoveResult::None;
};

class HomeReleaseGuard {
 public:
  static constexpr uint32_t ReleaseTimeoutMs = 2000;
  void begin(uint32_t now) { started_ = now; released_ = false; fault_ = false; }
  bool update(uint32_t now, bool originActive) {
    if (fault_) return false;
    if (originActive && (released_ || now - started_ >= ReleaseTimeoutMs)) {
      fault_ = true;
    } else if (!originActive) {
      released_ = true;
    }
    return !fault_;
  }
  bool released() const { return released_ && !fault_; }

 private:
  uint32_t started_ = 0;
  bool released_ = false;
  bool fault_ = false;
};

enum class RatchetPhase : uint8_t { Idle, ReversalDelay, SeekLow };
enum class RatchetResult : uint8_t { None, Done, Aborted, LimitFault, ReleaseFault, Timeout, Disconnected };

// Ratchet freewheel return ONLY, never a pitch-lowering correction and never
// automatic tightening. After DONE the app must remeasure before another MOVE.
class RatchetReturn {
 public:
  static constexpr uint8_t MaxCycles = 3;
  static constexpr uint32_t SeekTimeoutMs = 120000;
  static constexpr uint32_t MinSeekMs = 1000; // provisional full-stroke plausibility check
  bool armAtHigh() {
    if (active() || cycles_ >= MaxCycles) return false;
    armed_ = true;
    return true;
  }
  bool start(uint16_t id, bool lowActive, bool highActive, bool connected, uint32_t now) {
    if (!armed_ || active() || !id || lowActive || !highActive || !connected || cycles_ >= MaxCycles) return false;
    armed_ = false;
    ++cycles_;
    id_ = id;
    started_ = now;
    phase_ = RatchetPhase::ReversalDelay;
    result_ = RatchetResult::None;
    return true;
  }
  void tick(uint32_t now, bool lowActive, bool highActive, bool connected) {
    if (!active()) return;
    if (!connected) { cancel(RatchetResult::Disconnected); return; }
    if (lowActive && highActive) { cancel(RatchetResult::LimitFault); return; }
    if (phase_ == RatchetPhase::ReversalDelay) {
      if (lowActive) { cancel(RatchetResult::LimitFault); return; }
      if (now - started_ >= TimedMove::ReverseDelayMs) {
        phase_ = RatchetPhase::SeekLow;
        started_ = now;
        highRelease_.begin(now);
      }
      return;
    }
    if (!highRelease_.update(now, highActive)) {
      cancel(RatchetResult::ReleaseFault);
    } else if (lowActive && highRelease_.released()) {
      cancel(now - started_ >= MinSeekMs ? RatchetResult::Done : RatchetResult::LimitFault);
    } else if (now - started_ >= SeekTimeoutMs) {
      cancel(RatchetResult::Timeout);
    }
  }
  void cancel(RatchetResult reason = RatchetResult::Aborted) {
    armed_ = false;
    if (!active()) return;
    phase_ = RatchetPhase::Idle;
    result_ = reason;
  }
  void newTuningSession() { *this = RatchetReturn(); }
  bool active() const { return phase_ != RatchetPhase::Idle; }
  bool armed() const { return armed_; }
  uint8_t cycles() const { return cycles_; }
  uint16_t id() const { return id_; }
  RatchetResult result() const { return result_; }
  Direction output() const { return phase_ == RatchetPhase::SeekLow ? Direction::Low : Direction::Stop; }
 private:
  bool armed_ = false;
  uint8_t cycles_ = 0;
  uint16_t id_ = 0;
  uint32_t started_ = 0;
  RatchetPhase phase_ = RatchetPhase::Idle;
  RatchetResult result_ = RatchetResult::None;
  HomeReleaseGuard highRelease_;
};

}  // namespace piano
