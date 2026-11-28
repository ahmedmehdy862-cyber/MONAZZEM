import { upload } from "@vercel/blob/client";

const loginPanel = document.getElementById("login-panel");
const adminPanel = document.getElementById("admin-panel");
const loginStatus = document.getElementById("login-status");
const passwordPanel = document.getElementById("password-panel");
const passwordStatus = document.getElementById("password-status");
const syncStatus = document.getElementById("sync-status");
const publishStatus = document.getElementById("publish-status");
const storeStatus = document.getElementById("store-status");
const publishButton = document.getElementById("publish");
let mfaToken = "";

function showStatus(element, text, type = "") {
  element.textContent = text;
  element.className = `status ${type}`.trim();
}

async function callApi(path, options = {}) {
  const response = await fetch(path, { credentials: "same-origin", cache: "no-store", ...options });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.error || `http_${response.status}`);
  return body;
}

function errorText(error) {
  const known = {
    invalid_credentials: "اسم المستخدم أو كلمة المرور غير صحيحة.",
    try_later: "محاولات كثيرة. انتظر قليلًا ثم حاول مرة أخرى.",
    admin_auth_unconfigured: "إعداد حساب الإدارة لم يكتمل بعد.",
    storage_not_configured: "تخزين الإصدارات غير مربوط بالموقع بعد.",
    version_code_must_increase: "رقم الإصدار الداخلي يجب أن يكون أكبر من الإصدار المنشور.",
    release_fields_required: "أكمل بيانات الإصدار ورابط المتجر وملاحظات التحديث.",
    invalid_store_url: "أدخل رابط متجر يبدأ بـ HTTPS.",
    invalid_apk_url: "أدخل رابط HTTPS مباشرًا ينتهي بـ .apk.",
    apk_upload_failed: "تعذر رفع ملف APK. تحقق من صلاحية الملف والاتصال.",
    store_link_save_failed: "تعذر حفظ رابط المتجر. تحقق من الرابط وحاول مجددًا.",
    authentication_required: "انتهت الجلسة. سجّل الدخول من جديد.",
    password_too_short: "اختر كلمة مرور من ١٠ أحرف على الأقل.",
    password_unchanged: "كلمة المرور الجديدة يجب أن تختلف عن المؤقتة.",
    password_mismatch: "تأكيد كلمة المرور لا يطابق الجديدة.",
    password_save_failed: "تعذر حفظ كلمة المرور. حاول مرة أخرى.",
    invalid_totp: "رمز التحقق غير صحيح.",
    invalid_mfa_token: "انتهت صلاحية خطوة التحقق. سجّل الدخول من جديد.",
    mfa_not_enabled: "التحقق بخطوتين غير مفعّل.",
    mfa_already_enabled: "التحقق بخطوتين مفعّل أصلًا.",
    mfa_not_pending: "لا يوجد تسجيل تحقق بخطوتين قيد الانتظار.",
    session_revoked: "انتهت الجلسة (سُجّل الخروج من كل الأجهزة أو غُيّرت كلمة المرور). سجّل الدخول من جديد.",
    must_change_password: "غيّر كلمة المرور المؤقتة أولًا قبل استخدام اللوحة.",
    credential_unavailable: "تعذّر قراءة بيانات الاعتماد. حاول لاحقًا ولا تستخدم كلمة المرور المؤقتة.",
    too_many_requests: "محاولات كثيرة. انتظر قليلًا ثم حاول مرة أخرى.",
    confirm_required: "التأكيد مطلوب لحذف المستخدم.",
    user_not_found: "المستخدم غير مسجّل.",
    invalid_sha256: "بصمة SHA-256 غير صالحة.",
    apk_not_a_zip: "ملف APK غير صالح (ليس ملف ZIP).",
    apk_unreachable: "تعذّر الوصول إلى رابط APK للتحقق منه."
  };
  return known[error.message] || "تعذر إكمال العملية. تحقق من البيانات والاتصال ثم حاول مجددًا.";
}

async function loadCurrentRelease() {
  const release = await callApi("/api/latest");
  document.getElementById("current-release").textContent = `الإصدار المنشور: ${release.versionName} · رقم ${release.versionCode}`;
  const attachedWindows = release.windows && release.windows.version
    ? ` · نسخة ويندوز ${release.windows.version}${release.windows.sizeBytes ? ` (${Math.round(Number(release.windows.sizeBytes) / 1048576)} م.ب)` : ""} مرفقة`
    : " · بلا نسخة ويندوز مرفقة";
  document.getElementById("current-release").textContent += attachedWindows;
  document.getElementById("store-url").value = release.storeUrl || "https://qubixstore.vercel.app/focus-study-updates";
  document.getElementById("apk-url").value = release.apkUrl || "";
  document.getElementById("app-description").value = release.description || "نظّم مهامك وموادك وجلسات تركيزك وخطتك الدراسية في مساحة واحدة.";
  document.getElementById("version-code").value = Number(release.versionCode) + 1;
  const parts = String(release.versionName).split(".").map(Number);
  const nextVersion = (parts[2] || 0) === 0
    ? `${parts[0]}.${(parts[1] || 0) + 1}.0`
    : `${parts[0]}.${parts[1]}.${parts[2] + 1}`;
  document.getElementById("version-name").value = nextVersion;
}

async function openAdmin() {
  const session = await callApi("/api/admin/session");
  const mustChange = Boolean(session.mustChangePassword);
  loginPanel.classList.toggle("hidden", session.authenticated);
  document.getElementById("mfa-panel").classList.add("hidden");
  passwordPanel.classList.toggle("hidden", !session.authenticated || !mustChange);
  adminPanel.classList.toggle("hidden", !session.authenticated || mustChange);
  updateMfaState(Boolean(session.mfaEnrolled));
  if (session.authenticated && !mustChange) await loadCurrentRelease();
}

function updateMfaState(enrolled) {
  const state = document.getElementById("mfa-state");
  const enrollButton = document.getElementById("mfa-enroll");
  const disableButton = document.getElementById("mfa-disable");
  if (!state) return;
  state.textContent = enrolled
    ? "التحقق بخطوتين مفعّل — الدخول يحتاج رمز تطبيق المصادقة."
    : "التحقق بخطوتين غير مفعّل — فعّله لحماية حساب الإدارة برمز ثانٍ.";
  enrollButton.classList.toggle("hidden", enrolled);
  disableButton.classList.toggle("hidden", !enrolled);
  document.getElementById("mfa-setup").classList.add("hidden");
}

document.getElementById("login-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const button = event.currentTarget.querySelector("button[type=submit]");
  button.disabled = true;
  showStatus(loginStatus, "جارٍ التحقق من بيانات الدخول…");
  try {
    const result = await callApi("/api/admin/login", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ username: document.getElementById("username").value, password: document.getElementById("password").value }) });
    document.getElementById("password").value = "";
    if (result.mfaRequired) {
      mfaToken = result.mfaToken || "";
      loginPanel.classList.add("hidden");
      document.getElementById("mfa-panel").classList.remove("hidden");
      showStatus(loginStatus, "كلمة المرور صحيحة — أدخل رمز التحقق الثاني.", "ok");
      document.getElementById("mfa-code").focus();
      return;
    }
    await openAdmin();
  } catch (error) {
    showStatus(loginStatus, errorText(error), "error");
  } finally {
    button.disabled = false;
  }
});

document.getElementById("password-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const currentPassword = document.getElementById("current-password").value;
  const newPassword = document.getElementById("new-password").value;
  const confirmPassword = document.getElementById("confirm-password").value;
  if (newPassword !== confirmPassword) return showStatus(passwordStatus, errorText({ message: "password_mismatch" }), "error");
  const button = event.currentTarget.querySelector("button[type=submit]");
  button.disabled = true;
  showStatus(passwordStatus, "جارٍ حفظ كلمة المرور…");
  try {
    await callApi("/api/admin/password", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ currentPassword, newPassword })
    });
    showStatus(passwordStatus, "تم تغيير كلمة المرور. صارت المؤقتة غير صالحة.", "ok");
    document.getElementById("current-password").value = "";
    document.getElementById("new-password").value = "";
    document.getElementById("confirm-password").value = "";
    await openAdmin();
  } catch (error) {
    showStatus(passwordStatus, errorText(error), "error");
  } finally {
    button.disabled = false;
  }
});

document.getElementById("mfa-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const status = document.getElementById("mfa-status");
  const button = event.currentTarget.querySelector("button[type=submit]");
  button.disabled = true;
  showStatus(status, "جارٍ التحقق من الرمز…");
  try {
    await callApi("/api/admin/session", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ action: "mfa-verify", mfaToken, code: document.getElementById("mfa-code").value.trim() }) });
    document.getElementById("mfa-code").value = "";
    mfaToken = "";
    await openAdmin();
  } catch (error) {
    showStatus(status, errorText(error), "error");
  } finally {
    button.disabled = false;
  }
});

document.getElementById("mfa-enroll").addEventListener("click", async () => {
  const status = document.getElementById("mfa-status-admin");
  showStatus(status, "جارٍ توليد سر التحقق…");
  try {
    const result = await callApi("/api/admin/session", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ action: "mfa-enroll" }) });
    document.getElementById("mfa-secret").textContent = result.secret;
    document.getElementById("mfa-otpauth").href = result.otpauthUrl;
    document.getElementById("mfa-backup").textContent = (result.backupCodes || []).join(" · ");
    document.getElementById("mfa-setup").classList.remove("hidden");
    showStatus(status, "امسح الرمز أو أدخل السر، ثم أكّد برمز من التطبيق. احفظ رموز النسخ الاحتياطي الآن — لن تظهر مرة أخرى.", "ok");
  } catch (error) {
    showStatus(status, errorText(error), "error");
  }
});

document.getElementById("mfa-confirm").addEventListener("click", async () => {
  const status = document.getElementById("mfa-status-admin");
  const code = document.getElementById("mfa-enroll-code").value.trim();
  if (code.length !== 6) return showStatus(status, "أدخل رمزًا من 6 أرقام.", "error");
  showStatus(status, "جارٍ تأكيد التفعيل…");
  try {
    await callApi("/api/admin/session", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ action: "mfa-verify-enroll", code }) });
    showStatus(status, "تم تفعيل التحقق بخطوتين.", "ok");
    updateMfaState(true);
  } catch (error) {
    showStatus(status, errorText(error), "error");
  }
});

document.getElementById("mfa-disable").addEventListener("click", async () => {
  const status = document.getElementById("mfa-status-admin");
  const code = prompt("لإيقاف التحقق بخطوتين، أدخل رمز تطبيق المصادقة الحالي:");
  if (!code) return;
  try {
    await callApi("/api/admin/session", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ action: "mfa-disable", code: code.trim() }) });
    showStatus(status, "تم إيقاف التحقق بخطوتين.", "ok");
    updateMfaState(false);
  } catch (error) {
    showStatus(status, errorText(error), "error");
  }
});

document.getElementById("logout-all").addEventListener("click", async () => {
  const status = document.getElementById("mfa-status-admin");
  if (!confirm("تسجيل الخروج من كل الأجهزة والجلسات؟ ستحتاج إلى تسجيل الدخول من جديد.")) return;
  try {
    await callApi("/api/admin/session", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ action: "logout-all" }) });
    adminPanel.classList.add("hidden");
    loginPanel.classList.remove("hidden");
    showStatus(loginStatus, "تم تسجيل الخروج من كل الأجهزة.", "ok");
  } catch (error) {
    showStatus(status, errorText(error), "error");
  }
});

document.getElementById("logout").addEventListener("click", async () => {
  await callApi("/api/admin/logout", { method: "POST" });
  adminPanel.classList.add("hidden");
  loginPanel.classList.remove("hidden");
  showStatus(loginStatus, "تم تسجيل الخروج.", "ok");
});

document.getElementById("store-link-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const button = document.getElementById("save-store-link");
  const storeUrl = document.getElementById("store-url").value.trim();
  const apkUrl = document.getElementById("apk-url").value.trim();
  button.disabled = true;
  showStatus(storeStatus, "جارٍ حفظ رابط المتجر…");
  try {
    await callApi("/api/admin/store-link", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ storeUrl, apkUrl }) });
    showStatus(storeStatus, "تم ربط صفحة المتجر بالإصدار الحالي. ستفتحها إشعارات التحديث.", "ok");
  } catch (error) {
    showStatus(storeStatus, errorText(error), "error");
  } finally {
    button.disabled = false;
  }
});

document.getElementById("release-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const versionName = document.getElementById("version-name").value.trim();
  const versionCode = Number(document.getElementById("version-code").value);
  const releaseNotes = document.getElementById("release-notes").value.trim();
  const storeUrl = document.getElementById("store-url").value.trim();
  const apkInput = document.getElementById("apk-url");
  let apkUrl = apkInput.value.trim();
  const apkFile = document.getElementById("apk-file").files?.[0];
  const description = document.getElementById("app-description").value.trim();
  if (!Number.isInteger(versionCode) || !/^\d+\.\d+\.\d+$/.test(versionName) || !releaseNotes || !storeUrl || (!apkUrl && !apkFile)) return showStatus(publishStatus, "راجع رقم الإصدار ورابط المتجر وملاحظات التحديث، واختر APK أو أدخل رابطه.", "error");
  if (apkFile && (apkFile.size > 200 * 1024 * 1024 || !apkFile.name.toLowerCase().endsWith(".apk"))) return showStatus(publishStatus, "اختر ملف APK صالحًا لا يتجاوز 200 ميجابايت.", "error");

  publishButton.disabled = true;
  showStatus(publishStatus, apkFile ? "جارٍ فحص ملف APK ورفعه بأمان…" : "جارٍ نشر بيانات الإصدار ورابط المتجر…");
  let apkSha256 = "";
  try {
    if (apkFile) {
      const bytes = await apkFile.arrayBuffer();
      // B10: ZIP magic (PK\x03\x04) before upload; B9: SHA-256 for the feed.
      const head = new Uint8Array(bytes.slice(0, 4));
      if (!(head.length === 4 && head[0] === 0x50 && head[1] === 0x4b && head[2] === 0x03 && head[3] === 0x04)) {
        publishButton.disabled = false;
        return showStatus(publishStatus, "ملف APK غير صالح (ليس ملف ZIP).", "error");
      }
      const digest = await crypto.subtle.digest("SHA-256", bytes);
      apkSha256 = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
    }
    if (apkFile) {
      const safeName = `${versionCode}-${crypto.randomUUID()}.apk`;
      const blob = await upload(`focus-study/releases/${safeName}`, apkFile, {
        access: "public",
        handleUploadUrl: "/api/admin/upload-apk",
        contentType: "application/vnd.android.package-archive"
      });
      apkUrl = blob.url;
      apkInput.value = apkUrl;
    }
    await callApi("/api/admin/publish", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ versionCode, versionName, releaseNotes, storeUrl, apkUrl, description, ...(apkSha256 ? { sha256: apkSha256 } : {}) }) });
    showStatus(publishStatus, `تم نشر إعلان مُنظِّم ${versionName}. سيفتح إشعار التحديث صفحة المتجر.`, "ok");
    await loadCurrentRelease();
    document.getElementById("release-notes").value = "";
  } catch (error) {
    showStatus(publishStatus, errorText(error), "error");
  } finally {
    publishButton.disabled = false;
  }
});

document.getElementById("sync-release").addEventListener("click", async (event) => {
  const button = event.currentTarget;
  button.disabled = true;
  showStatus(syncStatus, "جارٍ مزامنة الموجز…");
  try {
    const result = await callApi("/api/admin/publish", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ action: "sync" }) });
    const release = result.release || {};
    showStatus(
      syncStatus,
      result.changed
        ? `تمت المزامنة: الموجز الآن ${release.versionName} · ${release.versionCode} ويعرضه الموقع والتطبيق وصفحة الإدارة.`
        : `الموجز مطابق أصلًا (${release.versionName} · ${release.versionCode}).`,
      "ok"
    );
    await loadCurrentRelease();
  } catch (error) {
    showStatus(syncStatus, errorText(error), "error");
  } finally {
    button.disabled = false;
  }
});

openAdmin().catch((error) => showStatus(loginStatus, errorText(error), "error"));
