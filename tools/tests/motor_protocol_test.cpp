#include "../../firmware/esp32_c3_piano_motor/SafeMotorProtocol.h"

using namespace piano;
static int checks = 0;
#define CHECK(expr) do { ++checks; if (!(expr)) return __LINE__; } while (0)

extern "C" int protocol_checks() { return checks; }
extern "C" int run_protocol_tests() {
  checks = 0;
  uint8_t move[] = {0xA1,0xF1,2,0x12,0x34,0x17,0x70,0x46,0x50};
  Command c = decodeCommand(move, 9);
  CHECK(c.type == CommandType::Move && c.id == 0x1234);
  CHECK(c.direction == Direction::High && c.preloadMs == 6000 && c.runMs == 18000);
  CHECK(decodeCommand(move, 8).type == CommandType::Invalid);
  uint8_t extra[10] = {0xA1,0xF1,2,0x12,0x34,0x17,0x70,0x46,0x50,0};
  CHECK(decodeCommand(extra, 10).type == CommandType::Invalid);
  move[2] = 3;
  CHECK(decodeCommand(move, 9).type == CommandType::Invalid);
  move[2] = 1;
  CHECK(decodeCommand(move, 9).direction == Direction::Low);
  move[3] = 0; move[4] = 0;
  CHECK(decodeCommand(move, 9).type == CommandType::Invalid);
  move[4] = 1; move[7] = 0; move[8] = 0;
  CHECK(decodeCommand(move, 9).type == CommandType::Invalid);
  move[7] = 0x4E; move[8] = 0x20;
  CHECK(decodeCommand(move, 9).type == CommandType::Move);
  move[8] = 0x21;
  CHECK(decodeCommand(move, 9).type == CommandType::Invalid);
  move[8] = 0x20; move[5] = 0x27; move[6] = 0x10;
  CHECK(decodeCommand(move, 9).type == CommandType::Move);
  move[6] = 0x11;
  CHECK(decodeCommand(move, 9).type == CommandType::Invalid);
  CHECK(decodeCommand(nullptr, 9).type == CommandType::Invalid);
  CHECK(decodeCommand(move, 0).type == CommandType::Invalid);
  uint8_t cap[] = {0xA1,0xF1,0};
  CHECK(decodeCommand(cap, 3).type == CommandType::Capabilities);
  uint8_t home[] = {0xA1,0xF2,0xAB,0xCD};
  CHECK(decodeCommand(home, 4).type == CommandType::Home && decodeCommand(home, 4).id == 0xABCD);
  home[2] = 0; home[3] = 0;
  CHECK(decodeCommand(home, 4).type == CommandType::Invalid);
  uint8_t ratchet[] = {0xA1,0xF3,0xAB,0xCD};
  CHECK(decodeCommand(ratchet, 4).type == CommandType::Ratchet && decodeCommand(ratchet, 4).id == 0xABCD);
  CHECK(decodeCommand(ratchet, 3).type == CommandType::Invalid);
  ratchet[2] = 0; ratchet[3] = 0;
  CHECK(decodeCommand(ratchet, 4).type == CommandType::Invalid);
  uint8_t stop[] = {0xA1,1,2,0,0,0,1,0x1F};
  CHECK(decodeCommand(stop, 8).type == CommandType::Stop);
  stop[4] = 1;
  CHECK(decodeCommand(stop, 8).type == CommandType::Invalid);
  uint8_t speed[] = {0xA1,8,1,100};
  CHECK(decodeCommand(speed, 4).type == CommandType::Speed && decodeCommand(speed, 4).speed == 100);
  speed[3] = 101;
  CHECK(decodeCommand(speed, 4).type == CommandType::Invalid);
  uint8_t hold[] = {0xA1,1,1,0,0,0,2,50,0x1F};
  CHECK(decodeCommand(hold, 9).type == CommandType::Hold && decodeCommand(hold, 9).direction == Direction::Low);
  hold[8] = 0;
  CHECK(decodeCommand(hold, 9).type == CommandType::Invalid);
  uint8_t ready[] = {0xAF,1,2,3,4,5,6,0xFF};
  CHECK(decodeCommand(ready, 8).type == CommandType::Ready);
  ready[2] = 3;
  CHECK(decodeCommand(ready, 8).type == CommandType::Invalid);

  RequestIds ids;
  CHECK(!ids.accept(0));
  CHECK(ids.accept(1));
  CHECK(!ids.accept(1));
  CHECK(ids.accept(65535));
  CHECK(!ids.accept(1));
  ids.newSession();
  CHECK(ids.accept(1));

  ManualHoldLease h;
  CHECK(h.refresh(Direction::Low, 100));
  h.tick(849);
  CHECK(h.output() == Direction::Low);
  h.tick(850);
  CHECK(h.output() == Direction::Stop && h.expired());
  CHECK(!h.refresh(Direction::Low, 851));
  h.stop();
  CHECK(h.refresh(Direction::High, 900));
  CHECK(!h.refresh(Direction::Low, 901) && h.output() == Direction::Stop);
  h.stop();
  CHECK(!h.refresh(Direction::Stop, 0));
  CHECK(h.refresh(Direction::High, 0));
  for (uint32_t now = 500; now < 30000; now += 500) CHECK(h.refresh(Direction::High, now));
  CHECK(!h.refresh(Direction::High, 30000) && h.output() == Direction::Stop);
  h.stop();
  CHECK(h.refresh(Direction::High, 0xfffffff0U));
  h.tick(0x2DD);
  CHECK(h.output() == Direction::High);
  h.tick(0x2DE);
  CHECK(h.expired() && h.output() == Direction::Stop);
  return 0;
}
