# H1 v1 sunset — DEFERRED (flag + documentation only)

- **Status:** NOT implemented. This is a deliberate deferral, not an oversight.
- **Why:** the published 0.35.0 clients are not out yet. The published 0.34.0
  clients sync in v1 (plaintext) and depend on the server accepting v1
  records. Rejecting v1 now would strand every 0.34.0 install's sync with no
  upgrade path except updating the app — the exact users this release is
  meant to carry forward.
- **When the sunset ships:** only after 0.35.0 is published AND adopted
  (telemetry/registry shows v1-only clients are a negligible remainder).
  Ahmed decides the date; it is not a code decision.
- **What the sunset will do (when approved):** in
  `update-site/lib/sync-store.js` `cleanRecord`, reject records without
  `v: 2` (return null, so they never merge or store), and answer v1 pushes
  with a distinct error (`sync_v1_sunset`) so old clients can explain it
  instead of failing silently. v1 rows already stored stay readable until
  their owners upgrade and re-push as v2 — deletion is a separate decision.
- **What stays true meanwhile:** v2 envelopes are the default for 0.35.0
  clients; v1 rows are visible only to the Blob-token holder; the v1→v2
  upgrade at equal timestamp (see `mergeSync`) keeps migrating stored rows
  as clients upgrade.

Related: SA-P2-12 in `~/workspace/final-audit-phase1/security-admin-audit.md`.
