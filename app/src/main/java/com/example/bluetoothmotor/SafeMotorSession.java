package com.example.bluetoothmotor;

import java.util.Locale;

/** Single-UI-thread SAFE_V1 gate. Has no Bluetooth or motor side effects. */
final class SafeMotorSession {
    static final String CAPABILITIES_HEX = "A1F100";
    static final long HOME_TIMEOUT_MS = 133_000L;
    static final long MOVE_ACK_MARGIN_MS = 2_500L;
    static final long START_ACK_TIMEOUT_MS = 2_000L;
    static final int MAX_RATCHET_CYCLES = 3;
    enum Kind { HOME, MOVE, RATCHET }
    enum Event { IGNORED, CAPABLE, STARTED, PROGRESS, HOME_DONE, MOVE_DONE, HIGH_LIMIT, RATCHET_DONE, FAULT }

    static final class Request {
        final Kind kind;
        final int id;
        final int direction;
        final int preloadMs;
        final int runMs;
        final long issuedAtMs;
        final long timeoutMs;
        private boolean started;

        private Request(Kind kind, int id, int direction, int preloadMs, int runMs, long now) {
            this.kind = kind;
            this.id = id;
            this.direction = direction;
            this.preloadMs = preloadMs;
            this.runMs = runMs;
            issuedAtMs = now;
            timeoutMs = kind == Kind.HOME ? HOME_TIMEOUT_MS : kind == Kind.RATCHET ? 123_000L
                    : preloadMs + runMs + (preloadMs > 0 ? 130L : 0) + MOVE_ACK_MARGIN_MS;
        }

        String hex() {
            return kind != Kind.MOVE
                    ? String.format(Locale.US, kind == Kind.HOME ? "A1F2%04X" : "A1F3%04X", id)
                    : String.format(Locale.US, "A1F1%02X%04X%04X%04X", direction, id, preloadMs, runMs);
        }
    }

    private boolean connected;
    private boolean capable;
    private boolean ratchetCapable;
    private boolean ratchetEligible;
    private int ratchetCycles;
    private boolean homed;
    private int nextId = 1;
    private Request pending;

    void connected() {
        connected = true;
        capable = false;
        ratchetCapable = false;
        ratchetEligible = false;
        ratchetCycles = 0;
        homed = false;
        pending = null;
        nextId = 1;
    }

    void disconnected() {
        connected = false;
        capable = false;
        ratchetCapable = false;
        invalidate();
    }

    void invalidate() { pending = null; homed = false; ratchetEligible = false; }
    boolean capable() { return connected && capable; }
    boolean homed() { return capable() && homed; }
    Request pending() { return pending; }
    boolean supportsRatchet() { return capable() && ratchetCapable; }
    boolean ratchetEligible() { return supportsRatchet() && ratchetEligible; }
    int ratchetCycles() { return ratchetCycles; }

    Request ratchet(long now) {
        requireReady(false);
        if (!ratchetEligible() || ratchetCycles >= MAX_RATCHET_CYCLES) {
            throw new IllegalStateException("Verified raising-end limit and ratchet capability required");
        }
        int id = allocateId();
        ratchetEligible = false;
        ratchetCycles++;
        homed = false;
        pending = new Request(Kind.RATCHET, id, 1, 0, 0, now);
        return pending;
    }

    Request home(long now) {
        requireReady(false);
        homed = false;
        ratchetEligible = false;
        pending = new Request(Kind.HOME, allocateId(), 0, 0, 0, now);
        return pending;
    }

    Request move(int direction, int runMs, int preloadMs, long now) {
        requireReady(true);
        if ((direction != 1 && direction != 2) || runMs < 1 || runMs > 20000
                || preloadMs < 0 || preloadMs > 10000) {
            throw new IllegalArgumentException("Invalid bounded move");
        }
        pending = new Request(Kind.MOVE, allocateId(), direction, preloadMs, runMs, now);
        return pending;
    }

    private void requireReady(boolean needsHome) {
        if (!capable() || pending != null || (needsHome && !homed)) {
            throw new IllegalStateException("SAFE_V1 ready/home/idle required");
        }
    }

    private int allocateId() {
        if (nextId > 65535) throw new IllegalStateException("Request IDs exhausted; reconnect and rehome");
        return nextId++;
    }

    boolean expire(long now) {
        if (pending == null) return false;
        long elapsed = now - pending.issuedAtMs;
        if (elapsed >= pending.timeoutMs || (!pending.started && elapsed >= START_ACK_TIMEOUT_MS)) {
            invalidate();
            return true;
        }
        return false;
    }

    Event status(String text, long now) {
        if (!connected || text == null) return Event.IGNORED;
        if (expire(now)) return Event.FAULT;
        if ("SAFE_V1".equals(text) || "SAFE_V2".equals(text)) {
            capable = true;
            if ("SAFE_V2".equals(text)) ratchetCapable = true;
            return Event.CAPABLE;
        }
        if (text.startsWith("ERR") || text.startsWith("FAULT")
                || "HOLD_LIMIT".equals(text) || "HOLD_TIMEOUT".equals(text)) {
            invalidate();
            return Event.FAULT;
        }
        boolean homeEvent = text.startsWith("HOME_");
        boolean moveEvent = text.startsWith("MOVE_");
        boolean ratchetEvent = text.startsWith("RATCH_");
        if (!homeEvent && !moveEvent && !ratchetEvent) return Event.IGNORED;
        int colon = text.indexOf(':');
        // Old firmware's untagged events never complete a request.
        if (colon < 0) return Event.IGNORED;
        int id = parseId(text.substring(colon + 1));
        if (id == 0) { invalidate(); return Event.FAULT; }
        if (pending == null || pending.id != id) return Event.IGNORED;
        if ((pending.kind == Kind.HOME && !homeEvent) || (pending.kind == Kind.MOVE && !moveEvent)
                || (pending.kind == Kind.RATCHET && !ratchetEvent)) return Event.IGNORED;
        String event = text.substring(0, colon);
        if ("MOVE_HIGH".equals(event)) {
            if (!supportsRatchet() || pending.direction != 2 || !pending.started
                    || ratchetCycles >= MAX_RATCHET_CYCLES) {
                invalidate();
                return Event.FAULT;
            }
            pending = null;
            homed = false;
            ratchetEligible = true;
            return Event.HIGH_LIMIT;
        }
        if ("HOME_ABORT".equals(event) || "HOME_LOW_STUCK".equals(event)
                || "MOVE_ABORT".equals(event) || "MOVE_LIMIT".equals(event)
                || "RATCH_ABORT".equals(event)) {
            invalidate();
            return Event.FAULT;
        }
        if ("HOME_START".equals(event) || "MOVE_START".equals(event) || "RATCH_START".equals(event)) {
            pending.started = true;
            return Event.STARTED;
        }
        if ("HOME_LOW_LIMIT".equals(event) || "HOME_BACKOFF".equals(event)) return Event.PROGRESS;
        if ("HOME_DONE".equals(event) || "MOVE_DONE".equals(event) || "RATCH_DONE".equals(event)) {
            if (!pending.started) { invalidate(); return Event.FAULT; }
            Event result = pending.kind == Kind.HOME ? Event.HOME_DONE
                    : pending.kind == Kind.RATCHET ? Event.RATCHET_DONE : Event.MOVE_DONE;
            pending = null;
            if (result == Event.HOME_DONE || result == Event.RATCHET_DONE) homed = true;
            if (result == Event.HOME_DONE) { ratchetCycles = 0; ratchetEligible = false; }
            return result;
        }
        return Event.IGNORED;
    }

    private int parseId(String digits) {
        if (digits.isEmpty() || digits.length() > 5) return 0;
        int value = 0;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') return 0;
            value = value * 10 + c - '0';
        }
        return value > 0 && value <= 65535 ? value : 0;
    }
}
