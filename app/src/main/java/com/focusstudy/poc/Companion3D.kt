package com.focusstudy.poc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sceneview.ModelLoadState
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoadState
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * - خلفية شفافة (`isOpaque = false`) فيندمج مع بطاقة التطبيق فاتحًا وداكنًا
 *   دون لون خلفية hard-coded.
 * - الحجم والموضع من الـ [modifier] الذي يمرره المتصل؛ الحركة والتأطير
 *   محصورتان داخل حدوده، والبطاقة الحاوية تقصّ أي تجاوز.
 * - لا إيماءات مدار/تقريب (`cameraManipulator = null`) حتى لا يسرق العارض
 *   التمرير من العمود الذي يعيش فيه.
 * - إدارة دورة حياة: SceneView يوقف الرندر مع دورة حياة الشاشة تلقائيًا،
 *   ويدمر المحرك والنموذج عند مغادرة التركيب؛ وفوق ذلك يُوقف الرندر
 *   (`isRendering`) عندما تخرج المساحة من الشاشة المرئية أثناء التمرير.
 * - عند التعطيل لا يُركَّب شيء أصلًا؛ وعند إيقاف الحركات تُعرض الصورة
 *   الثابتة بدل العارض (احترامًا لتقليل الحركة ولإعداد الحركات).
 *
 * قيد موثّق: تأطير الكاميرا الافتراضي لم يُضبط يدويًا؛ أول فحص بصري على
 * محاكي/جهاز هو الذي يؤكد أن الشخصية كاملة داخل المساحة، فإن احتاجت
 * إزاحة تُضبط `cameraNode` هنا فقط (انظر دليل التكامل).
 */
@Composable
internal fun MONAZZEMCompanionView(
    controller: CompanionController,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    animationsEnabled: Boolean = true,
    onLoadFailed: (() -> Unit)? = null
) {
    // DISABLED من المتحكم (عبر setEnabled/release) تُخفي العارض أيضًا،
    // حتى لو جاءت `enabled` صحيحة من مسار آخر — مصدرا الإيقاف متسقان.
    val state by controller.state.collectAsState()
    if (!enabled || state == CompanionState.DISABLED) return
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
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val loadState = rememberModelLoadState(modelLoader, CompanionAssets.MODEL_PATH)

    // إيقاف الرندر عند عدم الظهور: المساحة خارج الشاشة المرئية أثناء التمرير
    // لا تستحق أي إطار. يُعاد حسابه عند كل تمرير/تغيير حجم.
    val screenHeightPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    var isOnScreen by remember { mutableStateOf(true) }
    val visibilityModifier = Modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        isOnScreen = bounds.top < screenHeightPx && bounds.bottom > 0f
    }

    Box(
        modifier = modifier
            .then(visibilityModifier)
            .semantics(mergeDescendants = true) { contentDescription = "منظم — رفيقك ثلاثي الأبعاد" }
    ) {
        when (val loaded = loadState) {
            is ModelLoadState.Loading -> CompanionFallbackImage(Modifier.matchParentSize())
            is ModelLoadState.Failed -> {
                LaunchedEffect(loaded.error) {
                    Log.w(CompanionAssets.TAG, "تعذر تحميل نموذج الرفيق، يُعرض البديل الثابت", loaded.error)
                    onLoadFailed?.invoke()
                }
                CompanionFallbackImage(Modifier.matchParentSize())
            }
            is ModelLoadState.Ready -> {
                // تشغيل الحركات تصريحيًا (واجهة SceneView 4.x الموثقة):
                // animationName تفاعلية — تغييرها يوقف السابقة ويبدأ الجديدة،
                // وanimationLoop=false لذات المرة الواحدة. لا نمسك عقدة
                // ModelNode ولا نستدعي playAnimation يدويًا، فلا كتابة حالة
                // أثناء التركيب ولا استدعاءات أمرية خارج دورة Compose.
                SceneView(
                    modifier = Modifier.matchParentSize(),
                    engine = engine,
                    modelLoader = modelLoader,
                    isOpaque = false,
                    isRendering = isOnScreen,
                    cameraManipulator = null
                ) {
                    ModelNode(
                        modelInstance = loaded.instance,
                        // الشخصية 1.76م؛ تُقاس لتملأ مكعب 1م داخل المساحة،
                        // وقاعدتها (القدمان) على أصل العقدة.
                        scaleToUnits = 1f,
                        centerOrigin = Position(y = -1f),
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
            }
        }
    }
}

/**
 * مساحة الرئيسية المخصصة للرفيق (§11.1 و§12): بطاقة في عمود التمرير نفسه،
 * بارتفاع ثابت، يشارك فيها التخطيط كأي بطاقة أخرى — لا overlay ولا تغطية.
 * يمينها/يسارها النموذج، وبجانبه نص قصير وزر يفتح المحادثة؛ البطاقة كلها
 * قابلة للضغط. عند تعطيل الرفيق لا تُركَّب البطاقة أصلًا فيستعيد التخطيط
 * مساحتها دون أن يتغير أي عنصر آخر.
 */
@Composable
internal fun CompanionHomeSlot(
    controller: CompanionController,
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
            .height(168.dp)
            .clickable(onClick = onOpenAssistant)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MONAZZEMCompanionView(
                controller = controller,
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
                    color = FocusColors.TextSecondary
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onOpenAssistant, shape = RoundedCornerShape(16.dp)) {
                    Text("افتح المحادثة")
                }
            }
        }
    }
}
