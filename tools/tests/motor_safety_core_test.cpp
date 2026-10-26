#include "../../firmware/esp32_c3_piano_motor/MotorSafetyCore.h"

using namespace piano;
static int checks = 0;
#define CHECK(expr) do { ++checks; if (!(expr)) return __LINE__; } while (0)

extern "C" int safety_checks() { return checks; }
extern "C" int run_safety_tests() {
  checks = 0;
  TimedMove m;
  CHECK(m.start(1, Direction::High, 100, 0, 1000) == StartResult::Accepted);
  CHECK(m.output() == Direction::High);
  m.tick(1099, false, false, true);
  CHECK(m.active());
  m.tick(1100, false, false, true);
  CHECK(!m.active() && m.output() == Direction::Stop && m.result() == MoveResult::Done);
  CHECK(m.id() == 1);
  CHECK(m.start(1, Direction::High, 100, 0, 1100) == StartResult::Stale);
  CHECK(!m.active());
  CHECK(m.start(0, Direction::High, 100, 0, 0) == StartResult::Invalid);
  CHECK(m.start(2, Direction::Stop, 100, 0, 0) == StartResult::Invalid);
  CHECK(m.start(2, Direction::High, 0, 0, 0) == StartResult::Invalid);
  CHECK(m.start(2, Direction::High, 20001, 0, 0) == StartResult::Invalid);
  CHECK(m.start(2, Direction::High, 100, 10001, 0) == StartResult::Invalid);

  CHECK(m.start(2, Direction::High, 200, 100, 0) == StartResult::Accepted);
  CHECK(m.output() == Direction::Low);
  CHECK(m.start(3, Direction::Low, 100, 0, 1) == StartResult::Busy);
  m.tick(100, false, false, true);
  CHECK(m.output() == Direction::Stop && m.phase() == MovePhase::ReversalDelay);
  m.tick(229, false, false, true);
  CHECK(m.output() == Direction::Stop);
  m.tick(230, false, false, true);
  CHECK(m.output() == Direction::High);
  m.tick(429, false, false, true);
  CHECK(m.active());
  m.tick(430, false, false, true);
  CHECK(m.result() == MoveResult::Done && !m.active());

  CHECK(m.start(3, Direction::High, 200, 100, 0) == StartResult::Accepted);
  m.tick(1, true, false, true);
  CHECK(m.result() == MoveResult::Limit && m.output() == Direction::Stop);
  CHECK(m.start(4, Direction::High, 100, 0, 0) == StartResult::Accepted);
  m.tick(1, true, false, true); // leaving low limit is permitted
  CHECK(m.active());
  m.tick(2, false, true, true);
  CHECK(m.result() == MoveResult::HighLimit);
  CHECK(m.start(5, Direction::Low, 100, 0, 0) == StartResult::Accepted);
  m.tick(1, true, true, true);
  CHECK(m.result() == MoveResult::Limit);
  CHECK(m.start(6, Direction::High, 100, 50, 0) == StartResult::Accepted);
  m.tick(50, false, false, true);
  m.tick(180, false, true, true);
  CHECK(m.result() == MoveResult::Limit && m.output() == Direction::Stop);
  CHECK(m.start(7, Direction::High, 100, 0, 0) == StartResult::Accepted);
  m.tick(1, false, false, false);
  CHECK(m.result() == MoveResult::Disconnected && !m.active());
  m.tick(500, false, false, true);
  CHECK(!m.active()); // reconnect cannot resume
  CHECK(m.start(8, Direction::High, 100, 0, 0) == StartResult::Accepted);
  m.cancel();
  CHECK(m.result() == MoveResult::Aborted && !m.active());
  CHECK(m.start(7, Direction::High, 100, 0, 0) == StartResult::Stale);
  CHECK(m.start(9, Direction::Low, 100, 0, 0xfffffff0U) == StartResult::Accepted);
  m.tick(0x53U, false, false, true);
  CHECK(m.active());
  m.tick(0x54U, false, false, true);
  CHECK(m.result() == MoveResult::Done);
  m.newSession();
  CHECK(m.start(1, Direction::High, 20000, 10000, 0) == StartResult::Accepted);
  m.cancel();

  HomeReleaseGuard h;
  h.begin(100);
  CHECK(h.update(2099, true) && !h.released());
  CHECK(!h.update(2100, true) && !h.released());
  CHECK(!h.update(2101, false)); // fault stays latched
  h.begin(100);
  CHECK(h.update(101, false) && h.released());
  CHECK(h.update(5000, false) && h.released());
  CHECK(!h.update(5001, true) && !h.released());
  h.begin(0xfffffff0U);
  CHECK(h.update(0x7bfU, true));
  CHECK(!h.update(0x7c0U, true)); // rollover at exactly 2000ms
  h.begin(0);
  CHECK(h.update(1999, false) && h.released());

  RatchetReturn ratchet;
  CHECK(!ratchet.start(1, false, true, true, 0)); // no raising-end authorization
  CHECK(ratchet.armAtHigh());
  CHECK(!ratchet.start(0, false, true, true, 0));
  CHECK(!ratchet.start(1, true, true, true, 0));
  CHECK(!ratchet.start(1, false, false, true, 0));
  CHECK(!ratchet.start(1, false, true, false, 0));
  CHECK(ratchet.start(1, false, true, true, 0));
  CHECK(!ratchet.start(1, false, true, true, 0));
  CHECK(ratchet.output() == Direction::Stop);
  ratchet.tick(129, false, true, true);
  CHECK(ratchet.output() == Direction::Stop);
  ratchet.tick(130, false, true, true);
  CHECK(ratchet.output() == Direction::Low);
  ratchet.tick(200, false, false, true);
  CHECK(ratchet.active());
  ratchet.tick(40130, true, false, true);
  CHECK(ratchet.result() == RatchetResult::Done && ratchet.output() == Direction::Stop);
  CHECK(!ratchet.armed() && ratchet.cycles() == 1);
  ratchet.tick(90000, false, false, true);
  CHECK(ratchet.output() == Direction::Stop); // never autonomously tightens
  CHECK(ratchet.armAtHigh() && ratchet.start(2, false, true, true, 0));
  ratchet.tick(130, false, true, true);
  ratchet.tick(2130, false, true, true);
  CHECK(ratchet.result() == RatchetResult::ReleaseFault && ratchet.output() == Direction::Stop);
  CHECK(ratchet.armAtHigh() && ratchet.start(3, false, true, true, 0));
  ratchet.cancel();
  CHECK(ratchet.result() == RatchetResult::Aborted);
  CHECK(!ratchet.armAtHigh() && ratchet.cycles() == RatchetReturn::MaxCycles);
  ratchet.newTuningSession();
  CHECK(ratchet.cycles() == 0 && !ratchet.armed());
  CHECK(ratchet.armAtHigh() && ratchet.start(4, false, true, true, 0));
  ratchet.tick(130, false, true, true);
  ratchet.tick(200, false, false, true);
  ratchet.tick(300, false, true, true);
  CHECK(ratchet.result() == RatchetResult::ReleaseFault);
  ratchet.newTuningSession();
  CHECK(ratchet.armAtHigh() && ratchet.start(5, false, true, true, 0));
  ratchet.tick(130, false, true, true);
  ratchet.tick(200, false, false, true);
  ratchet.tick(120130, false, false, true);
  CHECK(ratchet.result() == RatchetResult::Timeout);
  ratchet.newTuningSession();
  CHECK(ratchet.armAtHigh() && ratchet.start(6, false, true, true, 0));
  ratchet.tick(130, false, true, true);
  ratchet.tick(200, true, true, true);
  CHECK(ratchet.result() == RatchetResult::LimitFault);
  ratchet.newTuningSession();
  CHECK(ratchet.armAtHigh() && ratchet.start(7, false, true, true, 0));
  ratchet.tick(130, false, true, true);
  ratchet.tick(200, false, false, false);
  CHECK(ratchet.result() == RatchetResult::Disconnected);
  ratchet.newTuningSession();
  CHECK(ratchet.armAtHigh() && ratchet.start(8, false, true, true, 0));
  ratchet.tick(130, false, true, true);
  ratchet.tick(131, true, false, true);
  CHECK(ratchet.result() == RatchetResult::LimitFault); // implausibly short full stroke
  ratchet.newTuningSession();
  CHECK(ratchet.armAtHigh() && ratchet.start(9, false, true, true, 0xfffffff0U));
  ratchet.tick(0x72, false, true, true);
  ratchet.tick(0x73, false, false, true);
  ratchet.tick(0x45a, true, false, true);
  CHECK(ratchet.result() == RatchetResult::Done);
  return 0;
}
