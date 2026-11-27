package com.focusstudy.poc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.filament.Engine
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.model.model
import io.github.sceneview.loaders.ModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * الرفيق ثلاثي الأبعاد «منظم» (النسخة النهائية 0.34.0، المواصفة §10–§13 و§17).
 *
 * المحرك: SceneView 4.18.0 فوق Filament (انظر app/build.gradle.kts لسبب اختيار
 * الإصدار). النموذج GLB محلي بالكامل، بلا إنترنت وبلا خدمات مدفوعة وبلا أذونات
 * جديدة، ويعمل دون أي خادم.
 *
 * قواعد السلامة (§11 — الأولوية فوق جمال الرفيق):
 * - الرفيق يعيش داخل مساحة محددة بالـ layout فقط (انظر [CompanionHomeSlot])؛
 *   لا overlay ولا موضع مطلق ولا تغطية لأي عنصر واجهة أبدًا.
 * - عند التعطيل لا يُركَّب العارض أصلًا، والتطبيق يعمل كاملًا بدونه.
 * - عند إيقاف الحركات (أو تقليل الحركة) تُعرض الصورة الثابتة بدل العارض.
 * - عند فشل تحميل النموذج تُعرض الصورة الثابتة، ولا يتعطل أي شيء آخر.
 */
internal object CompanionAssets {
    /** مسار الـ GLB داخل assets/ — كما يقرأه SceneView (بلا scheme = من assets). */
    const val MODEL_PATH = "companion/model/MONAZZEM_Companion.glb"
    const val FALLBACK_IMAGE_PATH = "companion/fallback/companion_static.png"
    const val TAG = "Companion3D"
}

/** مهلة اعتبار تحميل نموذج الرفيق فاشلًا (الملف محلي 1.4MB فيُحمَّل عادةً في أقل من ثانية). */
internal const val MODEL_LOAD_TIMEOUT_MS = 8_000L

/**
 * حالات الرفيق (المواصفة §17). كل حالة تحمل اسم حركتها في الـ GLB كما هو
 * مكتوب في جدول animations (مُتحقَّق منه من الملف نفسه)، ومدتها بالمللي ثانية،
 * وما إذا كانت loop.
 *
 * - IDLE ‏(5.0ث) وTHINKING ‏(4.0ث): loop حتى يطلب شيء آخر.
 * - GREETING ‏(2.5ث) وENCOURAGEMENT ‏(2.5ث) وACHIEVEMENT ‏(3.0ث): مرة واحدة
 *   ثم عودة تلقائية إلى IDLE (تتولاها [MONAZZEMCompanionView]).
 * - DISABLED: لا رندر ولا حركات؛ يفرضه [CompanionController.setEnabled].
 */
internal enum class CompanionState(
    val gltfName: String?,
    val durationMillis: Long,
    val loop: Boolean
) {
    IDLE("IDLE", 5_000L, true),
    GREETING("GREETING", 2_500L, false),
    ENCOURAGEMENT("ENCOURAGEMENT", 2_500L, false),
    ACHIEVEMENT("ACHIEVEMENT", 3_000L, false),
    THINKING("THINKING", 4_000L, true),
    DISABLED(null, 0L, false)
}

/**
 * المتحكم في الرفيق (المواصفة §17): آلة حالات خالصة بلا أي اعتماد على
 * أندرويد، فيُختبر بـ JUnit عادي ([CompanionControllerTest]).
 *
 * قواعدها:
 * - IDLE هي الحالة الافتراضية.
 * - الحدث الجديد يستبدل الحالي ولا يُضاف إلى أي قائمة انتظار؛ فلا قوائم
 *   حركات غير منضبطة مهما تكرر الحدث.
 * - طلب الحالة نفسها وهي loop لا يعيد تشغيلها (لا إعادة تشغيل متكررة).
 * - الحركات ذات المرة الواحدة لا تعود وحدها: العارض يستدعي
 *   [returnToIdle] بعد انتهاء مدتها، ولا تعود إلا إن كانت ما زالت هي
 *   الحالة الحالية (فحدث أحدث منها لا يُلغى بعودة متأخرة).
 * - DISABLED يمنع كل الطلبات، و[setEnabled] وحدها تخرج منه.
 * - لا يغيّر المتحكم أي عنصر واجهة غير الرفيق إطلاقًا.
 */
internal class CompanionController(
    /** ساعة قابلة للحقن — في الإنتاج uptime الجهاز، وفي الاختبارات ساعة وهمية. */
    private val nowMillis: () -> Long = { android.os.SystemClock.uptimeMillis() }
) {
    private val _state = MutableStateFlow(CompanionState.IDLE)
    val state: StateFlow<CompanionState> = _state.asStateFlow()

    val current: CompanionState get() = _state.value
    val isEnabled: Boolean get() = _state.value != CompanionState.DISABLED

    /**
     * وقت آخر طلب (uptime بالمللي ثانية). يسمح للعارض بإسقاط حركة مرة
     * واحدة «قديمة»: حدث وقع والمستخدم لم يكن على الرئيسية (مثلًا إكمال
     * مهمة من تبويب «مهامي»، أو فتح تذكير) يبقى معلّقًا حتى يصل المستخدم
     * إلى الرئيسية، لكن إن تأخر أكثر من [STALE_ONE_SHOT_MILLIS] يُسقط
     * بصمت بدل أن يحتفل متأخرًا بلا سياق.
     */
    var lastRequestUptimeMillis: Long = 0L
        private set

    companion object {
        const val STALE_ONE_SHOT_MILLIS = 60_000L
    }

    fun playIdle() = request(CompanionState.IDLE)

    fun greet() = request(CompanionState.GREETING)

    fun encourage() = request(CompanionState.ENCOURAGEMENT)

    fun celebrateAchievement() = request(CompanionState.ACHIEVEMENT)

    fun think() = request(CompanionState.THINKING)

    /** يُستدعى من العارض بعد انتهاء حركة المرة الواحدة. */
    fun returnToIdle(expected: CompanionState) {
        if (_state.value == expected && !expected.loop && expected != CompanionState.DISABLED) {
            _state.value = CompanionState.IDLE
        }
    }

    fun setEnabled(enabled: Boolean) {
        _state.value = if (enabled) {
            if (_state.value == CompanionState.DISABLED) CompanionState.IDLE else _state.value
        } else {
            CompanionState.DISABLED
        }
    }

    fun release() {
        _state.value = CompanionState.DISABLED
    }

    private fun request(next: CompanionState) {
        if (_state.value == CompanionState.DISABLED) return
        if (_state.value == next) return
        lastRequestUptimeMillis = nowMillis()
        _state.value = next
    }
}

/**
 * مالك محرك الرفيق ونموذجه على مستوى الجذر (0.35.0).
 *
 * المشكلة: كان المحرك (`rememberEngine`) والمحمِّل والنموذج محصورين في
 * تركيب `MONAZZEMCompanionView` نفسه — أي إعادة تركيب هيكلية للرئيسية
 * (تبديل تبويب، تمرير يعيد التركيب) تدمر المحرك والنموذج، فيختفي الرفيق
 * ويعيد التحميل من جديد (~ثانية حجب على الخيط الرئيسي) أو يبقى مختفيًا.
 * الحل (وهو النمط الموثق في SceneView 4.18.0: «create one
 * `rememberEngine()` at app level, share across all scenes» — المحرك كائن
 * واحد لكل عملية): يُنشأ المحرك والمحمِّل في `AppRoot` بـ `remember`،
 * فيعيشان مع النشاط كله، ويُحمَّل النموذج مرة واحدة أثناء شاشة الترحيب
 * (قبل ظهور «اليوم») فلا يحجب واجهة الرئيسية. يُدمَّر كل شيء مع تدمير
 * الجذر فقط — لا مع مغادرة الرئيسية.
 *
 * تحميل النموذج على الخيط الرئيسي صراحةً (إصلاح تعطل الإقلاع 0.35.0):
 * توثيق SceneView 4.18.0 يقول «Filament JNI calls must run on the main
 * thread» — فلا «خيط متبنَّى» خارج الرئيسي في هذه النسخة من المكتبة؛
 * التحميل يبقى على الرئيسي ويُخفى خلف شاشة الترحيب بدل نقله.
 */
internal class Companion3DHost(
    val engine: Engine,
    val modelLoader: ModelLoader
) {
    /**
     * بوابة القدرات (0.35.0): false على جهاز لا يستطيع ربط شيدر التسكين
     * (SwiftShader في المحاكي، أو حد uniforms أقل من 256) — يُعرض البديل
     * الثابت ولا يُحمَّل النموذج أصلًا، لأن محاولة عرضه تفجر العملية
     * بـ SIGABRT من خيط FEngine::loop (لا يلتقطه أي try/catch). تُفحص
     * مرة واحدة وتُحفظ. إنشاء المحرك نفسه آمن (مثبت بسجلات المحاكي) —
     * التعطّل عند أول رسم للنموذج فقط، وهو ما يمنعه هذا الحارس.
     */
    val supported: Boolean = CompanionCapability.is3DSupported()

    /** النموذج المحمَّل، أو null أثناء التحميل/بعد الفشل. */
    var modelInstance by mutableStateOf<ModelInstance?>(null)
        private set

    /** صحيح بعد فشل صريح أو انتهاء المهلة دون نموذج. */
    var loadFailed by mutableStateOf(false)
        private set

    /** مفتاح إعادة التحميل — زيادته تعيد [loadModel] من جديد. */
    var loadRetry by mutableIntStateOf(0)
        private set

    fun retryLoad() {
        loadRetry++
    }

    /**
     * تحميل النموذج مرة واحدة (يُستدعى من `LaunchedEffect(loadRetry)` في
     * الجذر). الإلغاء المنظم يُمرَّر كما هو (لا يُبتلع داخل `runCatching`).
     */
    suspend fun loadModel() {
        if (!supported) {
            modelInstance = null
            loadFailed = true
            Log.w(
                CompanionAssets.TAG,
                "الرفيق ثلاثي الأبعاد غير مدعوم على هذا الجهاز — يُعرض البديل الثابت دون محاولة تحميل"
            )
            return
        }
        modelInstance = null
        loadFailed = false
        val started = SystemClock.uptimeMillis()
        Log.i(CompanionAssets.TAG, "تحميل نموذج الرفيق بدأ (المحاولة ${loadRetry + 1})")
        val loaded = try {
            withContext(Dispatchers.Main) {
                modelLoader.loadModelInstance(CompanionAssets.MODEL_PATH)
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(CompanionAssets.TAG, "فشل تحميل نموذج الرفيق", t)
            null
        }
        if (loaded != null) {
            modelInstance = loaded
            loadFailed = false
            val box = runCatching { loaded.model.boundingBox }.getOrNull()
            Log.i(
                CompanionAssets.TAG,
                "نموذج الرفيق حُمّل في ${SystemClock.uptimeMillis() - started}ms؛ " +
                    "حدوده (مركز/نصف امتداد)=$box"
            )
        } else {
            loadFailed = true
            Log.w(
                CompanionAssets.TAG,
                "تعذر تحميل نموذج الرفيق بعد ${SystemClock.uptimeMillis() - started}ms — يُعرض البديل الثابت"
            )
        }
    }

    /** يُستدعى من حارس المهلة في الجذر عند انتهائها دون نموذج. */
    fun markTimedOut() {
        if (modelInstance == null && !loadFailed) {
            loadFailed = true
            Log.w(
                CompanionAssets.TAG,
                "انتهت مهلة تحميل نموذج الرفيق (${MODEL_LOAD_TIMEOUT_MS}ms) دون اكتمال — يُعرض البديل الثابت"
            )
        }
    }

    /**
     * يُستدعى عند تدمير الجذر فقط. التنظيف الأصلي (المحرك/المحمِّل/النموذج)
     * يتولاه `rememberEngine`/`rememberModelLoader` عند تدميرهما؛ هنا نُسقط
     * المرجع فقط ونُسجّل — فلا تدمير مبكر يخفي الرفيق أثناء عمل التطبيق.
     */
    fun release() {
        if (modelInstance != null) {
            Log.i(CompanionAssets.TAG, "مالك الرفيق أُصدر (تدمير الجذر)")
            modelInstance = null
        }
    }
}

/**
 * الصورة الثابتة للرفيق، تُستخدم في موضعين:
 * 1) داخل التطبيق كـ fallback عند فشل تحميل الـ GLB أو إيقاف الحركات؛
 * 2) كأيقونة كبيرة في الإشعارات ([CompanionNotificationIcon]) — لأن إشعار
 *    نظام أندرويد لا يعرض نموذجًا ثلاثي الأبعاد متحركًا (§13)، فلا ندّعي ذلك.
 */
internal object CompanionNotificationIcon {
    fun decode(context: Context, maxPx: Int = NotificationAvatar.MAX_ICON_PX): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(CompanionAssets.FALLBACK_IMAGE_PATH).use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = NotificationAvatar.sampleSize(bounds.outWidth, bounds.outHeight, maxPx)
            }
            context.assets.open(CompanionAssets.FALLBACK_IMAGE_PATH).use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull()
    }
}

@Composable
private fun CompanionFallbackImage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember {
        CompanionNotificationIcon.decode(context, maxPx = 512)
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "منظم — رفيقك",
            modifier = modifier
        )
    } else {
        // حتى الصورة الثابتة نفسها غير موجودة: فراغ صامت، بلا أي تعطل.
        Box(modifier = modifier)
    }
}

/**
 * العارض القابل لإعادة الاستخدام (المواصفة §10).
 *
 * - خلفية شفافة: `surfaceType = TextureSurface` مع `isOpaque = false`
 *   (الصيغة المصححة في دليل ترحيل SceneView §6 — `isOpaque` وحده لا يكفي
 *   مع `Surface` الافتراضي، فقد تظهر خلفية سوداء معتمة).
 * - تأطير الكاميرا (إصلاح 0.35.0): `autoFitContent = true` يحرّك الكاميرا
 *   حتى يملأ المحتوى مساحة العرض، و`autoCenterContent` (الافتراضي true في
 *   4.18.0) يضع مركز ثقل المحتوى على محور المدار فيُركَّز دون `centerOrigin`
 *   لكل عقدة — هذا نص توثيق SceneView 4.18.0 نفسه. أُزيل
 *   `centerOrigin = Position(y = -1f)` (محاذاة سفلية): مع التوسيط الآلي
 *   كانت الكاميرا تؤطّر أسفل النموذج (1.76م) فقط — ساقان + حذاءان أبيضان
 *   في لقطتي أحمد — لا مشكلة خامات (الخامات تُرسم: لمعان + حذاء أبيض ظاهر).
 * - الحجم والموضع من الـ [modifier] الذي يمرره المتصل؛ الحركة والتأطير
 *   محصورتان داخل حدوده، والبطاقة الحاوية تقصّ أي تجاوز.
 * - لا إيماءات مدار/تقريب (`cameraManipulator = null`) حتى لا يسرق العارض
 *   التمرير من العمود الذي يعيش فيه.
 * - المحرك والمحمِّل والنموذج مرفوعون إلى [Companion3DHost] على مستوى
 *   الجذر (انظر توثيقه): لا يُعاد إنشاؤهم عند إعادة تركيب الرئيسية، ولا
 *   تُعاد عقدة النموذج عند تبديل الحركات — `animationName` تفاعلية في
 *   4.18.0 (تغييرها يوقف السابقة ويبدأ الجديدة، دون إيقاف الرندر).
 * - لا فراغ أبدًا (0.35.0): عند التعطيل أو أثناء التحميل أو عند الفشل
 *   تُعرض الصورة الثابتة `companion_static.png`، لا مساحة فارغة.
 * - تشخيص (0.35.0): التحميل وتبديل الحركات وانتقال العارض بين النموذج
 *   والبديل تُسجَّل في logcat (وسم `Companion3D`) — من النسخة التجريبية
 *   التالية على موبايل أحمد يُعرف محفز الاختفاء: هل النموذج فُقد، أم تبديل
 *   حركة، أم مغادرة التركيب.
 * - عند إيقاف الحركات تُعرض الصورة الثابتة بدل العارض (احترامًا لتقليل
 *   الحركة ولإعداد الحركات).
 *
 * تحميل النموذج (إصلاح تعطل الإقلاع 0.35.0 — مثبَت من مصادر 4.18.0
 * نفسها ومن الـ tombstone الخام للتشغيل 45769975636): `rememberModelInstance`
 * كان يعتمد على سياق produceState، فيعود استئنافه في بيئة اختبار Compose
 * على عامل Default غير متبنَّى من Filament، فيستدعي computeTangents على
 * ذلك الخيط فيرمي PreconditionPanic ويموت التطبيق بـ SIGABRT بعد ~ثانيتين
 * من الإقلاع. البديل هو واجهة SceneView الـ suspend (`loadModelInstance`)
 * التي تثبّت استدعاءات Filament على الخيط الرئيسي (createAsset +
 * asyncBeginLoad)، والتغليف بـ withContext(Main) هنا يضمن ذلك حتى
 * releaseSourceData. المحرك نفسه يُنشأ على الخيط الرئيسي أثناء التركيب
 * (FEngine created على الخيط الرئيسي في السجل)، فهو الخيط المتبنَّى.
 * `rememberModelLoadState`/`ModelLoadState` غير موجودين في 4.18.0 أصلًا؛
 * فالفشل (null أو استثناء) يُظهر البديل الثابت فورًا، والمهلة
 * ([MODEL_LOAD_TIMEOUT_MS]) تبقى حارسًا للتحميل المعلّق فقط. زر «إعادة
 * المحاولة» يعيد التحميل بمفتاح `retry` جديد، وإعادة التركيب وحدها لا
 * تعيد المحاولة.
 *
 * قيد موثّق: تأطير الكاميرا الآلي (`autoFitContent`) والخلفية الشفافة
 * يحتاجان أول فحص بصري على محاكي/جهاز للتأكيد النهائي.
 */
@Composable
internal fun MONAZZEMCompanionView(
    controller: CompanionController,
    host: Companion3DHost,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    animationsEnabled: Boolean = true,
    onLoadFailed: (() -> Unit)? = null
) {
    // DISABLED من المتحكم (عبر setEnabled/release) تُخفي العارض أيضًا،
    // حتى لو جاءت `enabled` صحيحة من مسار آخر — مصدرا الإيقاف متسقان.
    // لا فراغ أبدًا (0.35.0): تُعرض الصورة الثابتة بدل اختفاء البطاقة.
    val state by controller.state.collectAsState()
    if (!enabled || state == CompanionState.DISABLED) {
        Log.d(CompanionAssets.TAG, "الرفيق معطّل (enabled=$enabled, state=$state) — صورة ثابتة")
        CompanionFallbackImage(modifier = modifier.semantics { contentDescription = "منظم — رفيقك" })
        return
    }
    if (!animationsEnabled) {
        CompanionFallbackImage(modifier = modifier.semantics { contentDescription = "منظم — رفيقك" })
        return
    }

    // حركة مرة واحدة معلّقة من حدث سابق (إكمال مهمة أو فتح تذكير بينما
    // الرفيق غير مرئي) تُعرض إن وصل المستخدم إلى الرئيسية خلال دقيقة،
    // وتُسقط بصمت بعدها بدل احتفال متأخر بلا سياق. تُحسب عند التركيب
    // وكل تغيير حالة — وهذا هو الوقت الذي يهم فيه القرار.
    val displayState = remember(state) {
        val requested = state
        if (!requested.loop && requested.gltfName != null &&
            android.os.SystemClock.uptimeMillis() - controller.lastRequestUptimeMillis >
                CompanionController.STALE_ONE_SHOT_MILLIS
        ) CompanionState.IDLE else requested
    }
    // تشخيص (0.35.0): كل تبديل حركة يُسجَّل — يحدد من logcat إن كان اختفاء
    // البطاقة على موبايل أحمد متزامنًا مع عودة GREETING إلى IDLE.
    LaunchedEffect(displayState) {
        Log.i(CompanionAssets.TAG, "حالة الرفيق المعروضة: $displayState (المتحكم: $state)")
    }
    // النموذج مرفوع إلى الجذر (host): لا تحميل هنا، ولا إعادة تحميل عند
    // إعادة تركيب الرئيسية — يُقرأ فقط.
    val modelInstance = host.modelInstance
    val loadFailed = host.loadFailed
    LaunchedEffect(loadFailed) {
        if (loadFailed) {
            Log.w(CompanionAssets.TAG, "تعذر تحميل نموذج الرفيق، يُعرض البديل الثابت")
            onLoadFailed?.invoke()
        }
    }
    // تشخيص (0.35.0): انتقال العارض بين النموذج والبديل يُسجَّل — إن ظهر
    // هذا السطر دون سطر تحميل/فشل، فالاختفاء من مغادرة التركيب لا من النموذج.
    LaunchedEffect(modelInstance != null) {
        Log.i(
            CompanionAssets.TAG,
            if (modelInstance != null) "العارض يعرض النموذج ثلاثي الأبعاد"
            else "العارض يعرض الصورة الثابتة (النموذج null، فشل=$loadFailed)"
        )
    }

    Box(
        modifier = modifier
            .semantics(mergeDescendants = true) { contentDescription = "منظم — رفيقك ثلاثي الأبعاد" }
    ) {
        val instance = modelInstance
        if (instance != null) {
            // تشغيل الحركات تصريحيًا (SceneScope.ModelNode في 4.18.0، مثبَت
            // من مصادر الإصدار): animationName تفاعلية — تغييرها يوقف
            // السابقة ويبدأ الجديدة، وanimationLoop=false لذات المرة
            // الواحدة. لا نمسك عقدة ModelNode ولا نستدعي playAnimation
            // يدويًا، فلا كتابة حالة أثناء التركيب ولا استدعاءات أمرية
            // خارج دورة Compose.
            SceneView(
                modifier = Modifier.matchParentSize(),
                engine = host.engine,
                modelLoader = host.modelLoader,
                surfaceType = SurfaceType.TextureSurface,
                isOpaque = false,
                autoFitContent = true,
                cameraManipulator = null
            ) {
                ModelNode(
                    modelInstance = instance,
                    // الشخصية 1.76م؛ تُقاس لتملأ مكعب 1م داخل المساحة.
                    // بلا centerOrigin (إصلاح تأطير 0.35.0 — انظر توثيق العارض):
                    // autoCenterContent يركّز النموذج كاملًا، والمحاذاة السفلية
                    // السابقة كانت تُظهر الساقين فقط.
                    scaleToUnits = 1f,
                    autoAnimate = false,
                    animationName = displayState.gltfName,
                    animationLoop = displayState.loop
                )
            }
            // ذات المرة الواحدة تعود إلى IDLE بعد مدتها؛ والـ loop تستمر
            // حتى طلب آخر. العودة مشروطة بأن الحالة ما زالت هي نفسها،
            // فلا تُلغى حركة أحدث بعودة متأخرة. وحركة معلّقة أُسقطت
            // لقدمها تُمسح من المتحكم هنا أيضًا.
            LaunchedEffect(displayState) {
                if (displayState == CompanionState.IDLE && state != CompanionState.IDLE) {
                    controller.returnToIdle(state)
                } else if (!displayState.loop && displayState.gltfName != null) {
                    delay(displayState.durationMillis)
                    controller.returnToIdle(displayState)
                }
            }
        } else if (loadFailed) {
            Column(
                modifier = Modifier.matchParentSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CompanionFallbackImage(Modifier.weight(1f))
                TextButton(onClick = { host.retryLoad() }) {
                    Text("إعادة المحاولة")
                }
            }
        } else {
            CompanionFallbackImage(Modifier.matchParentSize())
        }
    }
}

/**
 * مساحة الرئيسية المخصصة للرفيق (§11.1 و§12): بطاقة في عمود التمرير نفسه،
 * بارتفاع أدنى 168dp (L4: يكبر مع تكبير الخط بدل قصّ النص)، يشارك فيها
 * التخطيط كأي بطاقة أخرى — لا overlay ولا تغطية.
 * يمينها/يسارها النموذج، وبجانبه نص قصير وزر يفتح المحادثة؛ البطاقة كلها
 * قابلة للضغط. عند تعطيل الرفيق لا تُركَّب البطاقة أصلًا فيستعيد التخطيط
 * مساحتها دون أن يتغير أي عنصر آخر.
 */
@Composable
internal fun CompanionHomeSlot(
    controller: CompanionController,
    host: Companion3DHost,
    assistantName: String,
    onOpenAssistant: () -> Unit,
    enabled: Boolean,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    if (!enabled) return
    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(FocusSpacing.radiusCard),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 168.dp)
            .clickable(onClick = onOpenAssistant)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MONAZZEMCompanionView(
                controller = controller,
                host = host,
                modifier = Modifier.size(148.dp),
                enabled = true,
                animationsEnabled = animationsEnabled
            )
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "مساعدك $assistantName",
                    style = MaterialTheme.typography.titleSmall,
                    color = FocusColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "رفيقك ثلاثي الأبعاد — يرحب بك، ويحتفل معك بكل مهمة تُنجزها.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onOpenAssistant, shape = RoundedCornerShape(16.dp)) {
                    Text("افتح المحادثة")
                }
            }
        }
    }
}
