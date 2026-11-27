package com.focusstudy.poc

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES30
import android.util.Log

/**
 * بوابة قدرات الرفيق ثلاثي الأبعاد — تُفحص قبل إنشاء محرك Filament.
 *
 * الدافع (مثبَت من بوابة المحاكي 0.35.0، التشغيل 45797820646، API 30):
 * على SwiftShader (مُصيّر البرمجيات في المحاكي) يفشل ربط برنامج
 * `base_lit_opaque` لأن شيدر الرأس يتجاوز GL_MAX_VERTEX_UNIFORM_VECTORS
 * (256) فيفجر Filament العملية بـ SIGABRT من خيط FEngine::loop — إيقاف
 * أصلي لا يلتقطه أي try/catch ولا بديل ثابت بعده. الحماية الوحيدة هي
 * عدم إنشاء المحرك أصلًا على جهاز لا يستطيع ربط الشيدر.
 *
 * الفحص: سياق EGL صغير مؤقت (pbuffer 1×1) يقرأ اسم المُصيّر وحد المتجهات.
 * - مُصيّر برمجي (SwiftShader / ANGLE / llvmpipe / softpipe) → ثابت.
 * - حد أقل من 256 (دون الحد الأدنى للمواصفة) → ثابت.
 * - غير ذلك (Adreno / Mali / PowerVR الحقيقية) → 3D.
 * أي فشل في الفحص نفسه → ثابت (آمن افتراضيًا). النتيجة تُحفظ لمرة واحدة.
 */
object CompanionCapability {
    private const val TAG = "CompanionCap"

    @Volatile
    private var cached: Boolean? = null

    /** true = آمن عرض الرفيق 3D؛ false = استخدم الصورة الثابتة ولا تنشئ Filament. */
    fun is3DSupported(): Boolean {
        cached?.let { return it }
        val ok = runCatching { probe() }.getOrDefault(false)
        cached = ok
        return ok
    }

    private fun probe(): Boolean {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            Log.w(TAG, "probe: no EGL display")
            return false
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            Log.w(TAG, "probe: eglInitialize failed")
            return false
        }
        try {
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
                Log.w(TAG, "probe: no EGL config")
                return false
            }
            val surfAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            val surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) {
                Log.w(TAG, "probe: no pbuffer surface")
                return false
            }
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
            val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroySurface(display, surface)
                Log.w(TAG, "probe: no EGL context")
                return false
            }
            try {
                if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                    Log.w(TAG, "probe: eglMakeCurrent failed")
                    return false
                }
                val renderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: ""
                val maxVec = IntArray(1)
                GLES30.glGetIntegerv(GLES30.GL_MAX_VERTEX_UNIFORM_VECTORS, maxVec, 0)
                Log.i(TAG, "probe: renderer=$renderer maxVertexUniformVectors=${maxVec[0]}")
                val software = renderer.contains("SwiftShader", ignoreCase = true) ||
                    renderer.contains("ANGLE", ignoreCase = true) ||
                    renderer.contains("llvmpipe", ignoreCase = true) ||
                    renderer.contains("softpipe", ignoreCase = true)
                return !software && maxVec[0] >= 256
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglDestroySurface(display, surface)
            }
        } finally {
            EGL14.eglTerminate(display)
        }
    }
}
