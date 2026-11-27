/**
 * Fixed-window limiter for POST /api/messages?action=register (H2).
 *
 * Two windows: per caller address (10 registrations / 15 min) and per install id
 * (5 / 15 min). The counter lives in this serverless instance only and resets on a
 * cold start, so it is a deterrent against mass fake registrations and profile-id
 * enumeration, not a hard distributed guarantee. The ownership proof and the
 * no-eviction rule remain the real controls. Nothing here logs a profile id, a
 * passphrase or a client address.
 */
const WINDOW_MS = 15 * 60 * 1000;
const IP_LIMIT = 10;
const UID_LIMIT = 5;

function addressOf(request) {
  const header = request && request.headers && request.headers["x-forwarded-for"];
  return String(header || "unknown").split(",")[0].trim() || "unknown";
}

/** Pure state transition: a blocked request never spends another slot. */
function evaluate(current, limit, now) {
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

// A true rate limit: every attempt spends a slot (success or failure); only the
// window expiry resets the count. A legitimate client registers at most once per
// signature change, so 10/IP and 5/uid per 15 minutes leave ample room.
function after(current, limit, now) {
  const gate = evaluate(current, limit, now);
  if (!gate.allowed) return gate;
  return {
    allowed: true,
    retryAfter: 0,
    state: { count: gate.state.count + 1, until: gate.state.until },
  };
}

function createMessagesLimiter() {
  const attempts = new Map();
  return {
    check(request, uid, now = Date.now()) {
      const ipKey = `register-ip:${addressOf(request)}`;
      const uidKey = `register-uid:${String(uid || "").toLowerCase()}`;
      const ipGate = evaluate(attempts.get(ipKey), IP_LIMIT, now);
      const uidGate = evaluate(attempts.get(uidKey), UID_LIMIT, now);
      attempts.set(ipKey, ipGate.state);
      attempts.set(uidKey, uidGate.state);
      const allowed = ipGate.allowed && uidGate.allowed;
      return {
        allowed,
        retryAfter: Math.max(ipGate.retryAfter, uidGate.retryAfter),
        keys: [ipKey, uidKey],
      };
    },
    record(keys, now = Date.now()) {
      for (const [key, limit] of [[keys[0], IP_LIMIT], [keys[1], UID_LIMIT]]) {
        const gate = after(attempts.get(key), limit, now);
        attempts.set(key, gate.state);
      }
    },
  };
}

module.exports = { createMessagesLimiter, IP_LIMIT, UID_LIMIT, WINDOW_MS };
