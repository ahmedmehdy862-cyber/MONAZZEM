/**
 * Best-effort in-memory audit log for sync security actions (design §3.4).
 *
 * Only the event type, time and outcome are kept — never a profile id, a passphrase,
 * a recovery code or a caller address. The ring holds the last 500 events and resets
 * on a cold start, like the rate limiter: a deterrent and a diagnostic aid, not a
 * durable record.
 */
const MAX_EVENTS = 500;

const events = [];

function record(event, ok) {
  events.push({ event, at: Date.now(), ok: ok === true });
  if (events.length > MAX_EVENTS) events.splice(0, events.length - MAX_EVENTS);
}

/** A copy of the recorded events, oldest first. */
function list() {
  return events.map((item) => ({ ...item }));
}

/** Test-only: drop all recorded events. */
function clear() {
  events.length = 0;
}

module.exports = { MAX_EVENTS, record, list, clear };
