// Source contracts complement runtime unit tests; they are NOT device tests.
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';

const read = path => readFile(new URL('../../' + path, import.meta.url), 'utf8');
const app = await read('app/src/main/java/com/example/bluetoothmotor/MainActivity.java');
const firmware = await read('firmware/esp32_c3_piano_motor/esp32_c3_piano_motor.ino');
const readme = await read('README.md');
let checks = 0;
function check(condition, message) { assert.ok(condition, message); checks++; }

function body(source, signature) {
  const at = source.indexOf(signature);
  assert.ok(at >= 0, `Missing production entry: ${signature}`);
  const open = source.indexOf('{', at);
  let depth = 0, state = '';
  for (let i = open; i < source.length; i++) {
    const c = source[i], next = source[i + 1];
    if (state === 'line') { if (c === '\n') state = ''; continue; }
    if (state === 'comment') { if (c === '*' && next === '/') { state = ''; i++; } continue; }
    if (state) { if (c === '\\') i++; else if (c === state) state = ''; continue; }
    if (c === '/' && next === '/') { state = 'line'; i++; continue; }
    if (c === '/' && next === '*') { state = 'comment'; i++; continue; }
    if (c === '"' || c === "'") { state = c; continue; }
    if (c === '{') depth++;
    if (c === '}' && --depth === 0) return source.slice(open, i + 1);
  }
  throw new Error(`Unclosed production entry: ${signature}`);
}

const status = body(app, 'private void handleEsp32StatusNotification(');
const send = body(app, 'private boolean sendCommand(');
const pulse = body(app, 'private boolean autoPulseWithPreload(');
const transport = body(app, 'private void pumpMotorWrites(');
const audio = body(app, 'private void startPitchListening(');
const pitch = body(app, 'private void updatePitch(');
const ratchet = body(app, 'private void continueRatchetAfterRemeasurement(');
const positionCalls = [...app.matchAll(/updateMotorPositionAfterMove\(/g)].length;
check(!app.includes('WRITE_TYPE_NO_RESPONSE'), 'No fire-and-forget motor transport');
check(transport.includes('WRITE_TYPE_DEFAULT') && transport.includes('activeMotorWrite'), 'Serialized write-response transport');
check(transport.includes('motorTransportFailure') && transport.includes('1500L'), 'Transport failure deadline');
check(send.includes('safeMotor.pending().hex().equals(hex)') && send.includes('hex.startsWith("A1F3")'), 'Untracked timed/home/ratchet frames blocked');
check(send.includes('motorWrites.clear()') && send.includes('addFirst(write)'), 'STOP drops queued writes and takes priority');
check(body(app, 'private void handleNotifyValue(').includes('owner != gatt || activityPaused'), 'Stale/paused notification filtering');
check(body(app, 'public void onCharacteristicWrite(').includes('owner != MainActivity.this.gatt'), 'Stale GATT write callback filtering');
const pause = body(app, 'protected void onPause(');
check(pause.includes('stopPitchListening()') && pause.includes('clearMotorPosition()') && pause.includes('disconnectGatt()'), 'Pause invalidates audio/control/link');
check(pitch.includes('motorStrikeGate.acceptStable') && pitch.includes('if (motorPitchReady)'), 'One acoustic-gated motor pitch sample');
check((app.match(/latestStableFrequency = frequency/g) || []).length === 1, 'No continuous stable-frame refresh of motor pitch');
check(audio.indexOf('motorStrikeGate.observe(capturedAt, level)') < audio.indexOf('updatePitch(frequency'), 'Envelope gate precedes pitch control');
check(audio.includes('generation != audioGeneration') && audio.includes('MAX_FRAME_GAP_MS'), 'Old/stale audio samples rejected');
check(body(app, 'private void startMotorSafetyInitialization(').includes('safeMotor.home(SystemClock.elapsedRealtime())'), 'Tagged monotonic HOME');
check(pulse.includes('safeMotor.move(') && pulse.includes('request.hex()') && !pulse.includes('handler.postDelayed'), 'Controller-owned complete timed sequence');
check(!app.includes('AUTO PRELOAD STOP') && !app.includes('finishAutoProbe') && !app.includes('startMotorSafetyBackoff'), 'No phone-side preload/homing/probe fallback');
check(positionCalls === 3 && (status.match(/updateMotorPositionAfterMove\(/g) || []).length === 2, 'Position update calls restricted to matching completion handler');
check(status.includes('SafeMotorSession.Event.MOVE_DONE') && status.includes('SafeMotorSession.Event.RATCHET_DONE'), 'Tagged terminal event dispatch');
check(ratchet.includes('hasNewStablePitchForTarget') && ratchet.includes('cents > 0') && ratchet.includes('safeMotor.ratchet('), 'Return requires flat remeasurement');
check(!ratchet.includes('safeMotor.home(') && status.includes('motorPositionRevFromLow = 0.0'), 'Ratchet return is not remove-wrench HOME');
check(!app.includes('autoRaiseDirection = autoLowerDirection'), 'No inferred direction swapping');
const writeCallback = body(firmware, 'class MotorWriteCallbacks');
const serverCallback = body(firmware, 'class ServerCallbacks');
check(writeCallback.includes('xQueueSend') && writeCallback.includes('callbackEpoch'), 'Bounded, epoch-tagged callback queue');
check(!/stopMotor\(|moveLeftLow\(|moveRightHigh\(|pwmWrite/.test(writeCallback + serverCallback), 'BLE callbacks never drive motor state');
for (const entry of ['void notifyBytes(', 'void notifyText(', 'bool moveLeftLow(', 'bool moveRightHigh(']) {
  check(body(firmware, entry).includes('isCurrentLink()'), `${entry} checks current link epoch`);
}
const dispatch = body(firmware, 'void dispatchCommand(');
check(dispatch.includes('packet.queuedAtMs > 250') && dispatch.includes('requestIds.isFresh'), 'Expired/replayed requests blocked');
check(dispatch.includes('CommandType::Ratchet') && dispatch.includes('ratchetReturn.start('), 'Ratchet authorization core wired');
check(body(firmware, 'void runTimedMotion(').includes('ratchetLowRelease.update'), 'Post-return low-origin release guard wired');
const loop = body(firmware, 'void loop()');
check(loop.indexOf('serviceLinkAndCommands()') < loop.indexOf('runHomeStateMachine()')
  && loop.includes('runTimedMotion()') && loop.includes('runRatchetMotion()') && loop.includes('runManualMotion()'), 'All control paths serviced in loop');
check(readme.indexOf('## English') < readme.indexOf('## 中文'), 'README remains English first');
check(readme.includes('### Open-Source Acknowledgements') && readme.includes('### 开源作者致谢')
  && readme.includes('Neil Kolban') && readme.includes('Haye Hinrichsen'), 'Bilingual acknowledgements retained');
const view = body(app, 'private static class SteampunkTunerView');
const gauge = body(view, 'private void drawNeedleGauge(');
check(body(view, 'private void drawSkinnedTuner(').includes('drawNeedleGauge(')
  && body(view, 'private void drawDial(').includes('drawNeedleGauge('), 'Same needle gauge in both display paths');
check(view.includes('R.drawable.zhen') && !view.includes('drawSkinnedTopNeedle')
  && !view.includes('drawSkinnedTopKey') && !view.includes('drawSkinnedWaveformDisplay'), 'No old fixed needle/marker/waveform layer');
check((gauge.match(/canvas.rotate\(/g) || []).length === 1
  && gauge.includes('canvas.rotate(DialGeometry.angleDegrees(displayCents), cx, cy)'), 'Only supplied needle rotates at defined cents scale');
check(gauge.indexOf('drawBitmap(rotatingDialBitmap') < gauge.indexOf('canvas.rotate(')
  && gauge.indexOf('restoreToCount(needleSave)') < gauge.indexOf('float hubRadius'), 'Fixed dial below needle below foreground note medallion');
check(gauge.includes('DialGeometry.DIAL_PIVOT_X') && gauge.includes('DialGeometry.DIAL_PIVOT_Y')
  && gauge.includes('DialGeometry.NEEDLE_PIVOT_X') && gauge.includes('DialGeometry.NEEDLE_PIVOT_Y'), 'Both supplied black-dot pivots used');
check(gauge.includes('validMeasurement && now - measuredAtMs <= 2500L')
  && gauge.includes('boolean complete = fresh &&') && gauge.includes('WAITING FOR PIANO'), 'Stale measurements cannot indicate completed calibration');
check(body(view, 'void setSelectedMidi(').includes('validMeasurement = false')
  && body(view, 'void setPitch(').includes('DialGeometry.finite(cents)'), 'Selection resets and invalid pitch is rejected for display');
check(gauge.includes('canvas.clipPath(dialClip)') && !gauge.includes('EVEN_ODD')
  && !gauge.includes('radius * 0.79f'), 'Interior dial texture is not cut out');
console.log(`PASS: ${checks} production source contracts (structural checks, not runtime/device verification)`);
