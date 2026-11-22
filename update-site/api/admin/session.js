const { isAdmin, originIsSame, clearSessionCookie, json } = require("../../lib/admin-auth");
const { mustChangePassword } = require("../../lib/admin-credentials");

/**
 * Session status and logout share one function so the Hobby plan stays within its 12-function
 * limit. `vercel.json` rewrites /api/admin/logout here; the two paths are also distinct methods.
 */
module.exports = async function session(req, res) {
  if (req.method === "POST") {
    if (!originIsSame(req)) return json(res, 403, { error: "origin_rejected" });
    clearSessionCookie(res);
    return json(res, 200, { ok: true });
  }
  if (req.method !== "GET") return json(res, 405, { error: "method_not_allowed" });
  if (!process.env.ADMIN_USERNAME || !process.env.ADMIN_SESSION_SECRET) return json(res, 503, { error: "admin_auth_unconfigured" });
  const authenticated = isAdmin(req);
  let mustChange = false;
  if (authenticated) {
    try { mustChange = await mustChangePassword(); } catch { mustChange = false; }
  }
  return json(res, 200, {
    authenticated,
    username: authenticated ? process.env.ADMIN_USERNAME : null,
    mustChangePassword: mustChange,
  });
};
