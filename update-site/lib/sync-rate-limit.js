/**
 * Fixed-window limiter for /api/sync.
 *
 * The counter lives in this serverless instance only and resets on a cold start, so it is a
 * deterrent against password guessing and profile enumeration, not a hard distributed guarantee.
 * The scrypt hash, constant-time comparison and per-account isolation remain the real controls.
 * Nothing here logs a profile id, a passphrase or a client address.
 */
const WINDOW_MS = 15 * 60 * 1000;
/** Recovery-code reset attempts: 5 per account per day (design §3.3), on top of the 15-minute IP bucket. */
const RESET_DAILY_WINDOW_MS = 24 * 60 * 60 * 1000;
const RESET_DAILY_LIMIT = 5;
const LIMITS = { register: 10, changePassphrase: 10, reset: 10, setRecoveryCode: 10, pull: 30, push: 30 };

function addressOf(request) {
  const header = request && request.headers && request.headers["x-forwarded-for"];
  return String(header || "unknown").split(",")[0].trim() || "unknown";
}

/**
 * Register, changePassphrase and reset are keyed by caller address so one source cannot
 * enumerate many profile ids or guess many accounts' secrets. Authenticated per-account
 * actions (pull/push/setRecoveryCode) are keyed by profile id.
 */
const IP_KEYED_ACTIONS = new Set(["register", "changePassphrase", "reset"]);
function keyFor(request, action, profileId) {
  return IP_KEYED_ACTIONS.has(action) ? `${action}:${addressOf(request)}` : `${action}:${profileId}`;
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

/**
 * A limiter with its own window, limits and key function, so the reset action can carry
 * both the shared 15-minute IP bucket and a 24-hour per-account bucket (design §3.3).
 * Defaults preserve the original fixed 15-minute window.
 */
function createLimiter(windowMs = WINDOW_MS, limits = LIMITS, keyFn = keyFor) {
  const attempts = new Map();
  const limitFor = (action) => limits[action] || limits.push;
  const evaluateIn = (current, action, now) => {
    const limit = limitFor(action);
    const state = !current || current.until <= now
      ? { count: 0, until: now + windowMs }
      : current;
    if (state.count >= limit) {
      return { allowed: false, retryAfter: Math.max(1, Math.ceil((state.until - now) / 1000)), state };
    }
    return { allowed: true, retryAfter: 0, state };
  };
  const afterIn = (current, action, now, success) => {
    const gate = evaluateIn(current, action, now);
    if (!gate.allowed) return gate;
    return {
      allowed: true,
      retryAfter: 0,
      state: { count: success ? 0 : gate.state.count + 1, until: gate.state.until },
    };
  };
  return {
    check(request, action, profileId, now = Date.now()) {
      const key = keyFn(request, action, profileId);
      const gate = evaluateIn(attempts.get(key), action, now);
      attempts.set(key, gate.state);
      return { allowed: gate.allowed, retryAfter: gate.retryAfter, key };
    },
    record(key, action, success, now = Date.now()) {
      const gate = afterIn(attempts.get(key), action, now, success);
      attempts.set(key, gate.state);
    },
  };
}

module.exports = {
  WINDOW_MS, RESET_DAILY_WINDOW_MS, RESET_DAILY_LIMIT, LIMITS,
  addressOf, keyFor, evaluate, after, createLimiter, limiter: createLimiter(),
};
