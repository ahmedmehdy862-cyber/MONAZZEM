/**
 * Generic fixed-window limiter (B7), same shape as the H2 registration limiter:
 * 10 per caller address + 5 per user id per 15 minutes.
 *
 * LIMITATION (documented, SA-P2-13): the counter lives in this serverless
 * instance only and resets on a cold start / redeploy, so it is a deterrent
 * against mass abuse and storage-filling, not a hard distributed guarantee.
 * The ownership proofs and the 500-item inbox cap remain the real controls.
 * Nothing here logs a profile id, a passphrase or a client address.
 */
const WINDOW_MS = 15 * 60 * 1000;
const IP_LIMIT = 10;
const UID_LIMIT = 5;

function addressOf(request) {
  const header = request && request.headers && request.headers["x-forwarded-for"];
  return String(header || "unknown").split(",")[0].trim() || "unknown";
}

function evaluate(current, limit, now) {
  const state = !current || current.until <= now
    ? { count: 0, until: now + WINDOW_MS }
    : current;
  if (state.count >= limit) {
    return { allowed: false, retryAfter: Math.max(1, Math.ceil((state.until - now) / 1000)), state };
  }
  return { allowed: true, retryAfter: 0, state };
}

function after(current, limit, now) {
  const gate = evaluate(current, limit, now);
  if (!gate.allowed) return gate;
  return { allowed: true, retryAfter: 0, state: { count: gate.state.count + 1, until: gate.state.until } };
}

/**
 * @param prefix - key namespace, e.g. "support" or "admin-password".
 * Every attempt spends a slot (success or failure); only window expiry resets.
 */
function createFixedWindowLimiter(prefix, { ipLimit = IP_LIMIT, uidLimit = UID_LIMIT, windowMs = WINDOW_MS } = {}) {
  const attempts = new Map();
  return {
    check(request, uid, now = Date.now()) {
      const ipKey = `${prefix}-ip:${addressOf(request)}`;
      const uidKey = `${prefix}-uid:${String(uid || "").toLowerCase()}`;
      const ipGate = evaluate(attempts.get(ipKey), ipLimit, now);
      const uidGate = evaluate(attempts.get(uidKey), uidLimit, now);
      attempts.set(ipKey, ipGate.state);
      attempts.set(uidKey, uidGate.state);
      const allowed = ipGate.allowed && uidGate.allowed;
      return { allowed, retryAfter: Math.max(ipGate.retryAfter, uidGate.retryAfter), keys: [ipKey, uidKey] };
    },
    record(keys, now = Date.now()) {
      for (const [key, limit] of [[keys[0], ipLimit], [keys[1], uidLimit]]) {
        const gate = after(attempts.get(key), limit, now);
        attempts.set(key, gate.state);
      }
    },
  };
}

module.exports = { WINDOW_MS, IP_LIMIT, UID_LIMIT, addressOf, createFixedWindowLimiter };
