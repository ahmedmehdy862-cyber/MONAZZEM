/**
 * Fixed-window limiter for /api/sync.
 *
 * The counter lives in this serverless instance only and resets on a cold start, so it is a
 * deterrent against password guessing and profile enumeration, not a hard distributed guarantee.
 * The scrypt hash, constant-time comparison and per-account isolation remain the real controls.
 * Nothing here logs a profile id, a passphrase or a client address.
 */
const WINDOW_MS = 15 * 60 * 1000;
const LIMITS = { register: 10, pull: 30, push: 30 };

function addressOf(request) {
  const header = request && request.headers && request.headers["x-forwarded-for"];
  return String(header || "unknown").split(",")[0].trim() || "unknown";
}

/** Register is keyed by caller address so one source cannot enumerate many profile ids. */
function keyFor(request, action, profileId) {
  return action === "register" ? `register:${addressOf(request)}` : `${action}:${profileId}`;
}

/** Pure state transition: a blocked request never spends another slot. */
function evaluate(current, action, now) {
  const limit = LIMITS[action] || LIMITS.push;
  const state = !current || current.until <= now
    ? { count: 0, until: now + WINDOW_MS }
    : current;
  if (state.count >= limit) {
    return {
      allowed: false,
      retryAfter: Math.max(1, Math.ceil((state.until - now) / 1000)),
      state,
    };
  }
  return { allowed: true, retryAfter: 0, state };
}

function after(current, action, now, success) {
  const gate = evaluate(current, action, now);
  if (!gate.allowed) return gate;
  return {
    allowed: true,
    retryAfter: 0,
    state: { count: success ? 0 : gate.state.count + 1, until: gate.state.until },
  };
}

function createLimiter() {
  const attempts = new Map();
  return {
    check(request, action, profileId, now = Date.now()) {
      const key = keyFor(request, action, profileId);
      const gate = evaluate(attempts.get(key), action, now);
      attempts.set(key, gate.state);
      return { allowed: gate.allowed, retryAfter: gate.retryAfter, key };
    },
    record(key, action, success, now = Date.now()) {
      const gate = after(attempts.get(key), action, now, success);
      attempts.set(key, gate.state);
    },
  };
}

module.exports = { WINDOW_MS, LIMITS, addressOf, keyFor, evaluate, after, createLimiter, limiter: createLimiter() };
