package com.focusstudy.poc

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/**
 * Brand palette used across the app and the launcher icon.
 *
 * The tokens are palette-backed instead of fixed constants: switching between the dark and the
 * light scheme only flips [lightMode], and every screen that reads a token recomposes by itself.
 * That keeps one visual identity in two modes without touching hundreds of call sites.
 */
object FocusColors {
    internal var lightMode by mutableStateOf(false)

    private fun token(dark: Color, light: Color): Color = if (lightMode) light else dark

    val Ink get() = token(Color(0xFF08131F), Color(0xFFF6F9FC))
    val Navy get() = token(Color(0xFF142B45), Color(0xFFE7EFF7))
    val Surface get() = token(Color(0xFF16283C), Color(0xFFFFFFFF))
    val SurfaceHigh get() = token(Color(0xFF1E3550), Color(0xFFEDF3F9))
    val Outline get() = token(Color(0xFF33506F), Color(0xFFD8E3EE))

    /** Body text: about 15:1 against [Ink] in both modes, well past WCAG AAA. */
    val TextPrimary get() = token(Color(0xFFF7F8FA), Color(0xFF101F30))

    /** Secondary text, readable at small sizes in both modes. */
    val TextSecondary get() = token(Color(0xFFC6D5E4), Color(0xFF445E78))

    /** Lowest emphasis, still comfortable against the background. */
    val TextMuted get() = token(Color(0xFF9FB2C6), Color(0xFF5D7285))

    /**
     * «أزرق» هو أزرق ملكي مميز (0xFF2563EB)، لا تركوازي: كان مطابقًا حرفيًا لـ [Teal]
     * (0xFF16B8A6) فعرض منتقي التمييز خيارين متطابقين. القيمة المقترحة أصلًا 0xFF2F6F9E
     * لم تُستخدم لأنها مطابقة حرفيًا لـ [Violet] وكانت ستنقل الازدواج إليه.
     */
    val Blue get() = Color(0xFF2563EB)
    val BlueDeep get() = Color(0xFF0E7C74)
    val Violet get() = Color(0xFF2F6F9E)
    val Teal get() = Color(0xFF16B8A6)
    val Amber get() = Color(0xFFF59E0B)
    val Green get() = Color(0xFF22C55E)
    val Rose get() = Color(0xFFF43F5E)

    val accents get() = listOf(Blue, Violet, Teal, Amber, Green)

    val background get() = if (lightMode) {
        Brush.verticalGradient(listOf(Color(0xFFFAFCFE), Color(0xFFEFF5FA), Color(0xFFF7FAFD)))
    } else {
        Brush.verticalGradient(listOf(Ink, Navy, Color(0xFF0D2033)))
    }

    /**
     * Focus card. It stays a deep surface in both modes because the copy on it is white, but the
     * light scheme uses a softer teal-navy pair instead of near-black.
     */
    val hero get() = if (lightMode) {
        Brush.linearGradient(listOf(Color(0xFF12414E), Color(0xFF0E7C74)))
    } else {
        Brush.linearGradient(listOf(Color(0xFF142B45), Color(0xFF0E7C74)))
    }

    // ---- the mark itself, so the logo reads well on both schemes ----------------------------
    /** Tile behind the mark: deep navy at night, a clean ice surface in daylight. */
    val LogoTile get() = if (lightMode) {
        Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFE4EEF7)))
    } else {
        Brush.linearGradient(listOf(Color(0xFF1B3350), Color(0xFF0C1B2B)))
    }

    /** Hairline around the light tile so it separates from a white card without a shadow. */
    val LogoRing get() = token(Color(0x00FFFFFF), Color(0xFFCFDEEB))

    /** The left hook: near-white at night, deep ink by day so it never disappears. */
    val LogoInk get() = token(Color(0xFFF7F8FA), Color(0xFF13293E))

    /** Hooks and cube accent: bright teal at night, a deeper teal for daylight contrast. */
    val LogoAccent get() = token(Color(0xFF16B8A6), Color(0xFF0C6E67))

    /** Background glows: vivid at night, quiet enough not to muddy a light page. */
    val glowStrength get() = if (lightMode) 0.10f else 0.22f

    /**
     * Accent colours are tuned for fills, not for small text: lifted towards white on the dark
     * scheme and deepened towards black on the light one, so labels stay readable.
     */
    fun onTint(accent: Color): Color = if (lightMode) lerp(accent, Color.Black, 0.42f) else lerp(accent, Color.White, 0.45f)

    /**
     * Error text/action colour, adaptive to the mode: the dark scheme uses the same rose as
     * [darkScheme]'s error, the light scheme the same deep rose-red as [lightScheme]'s error.
     * Never use a fixed pale pink for «حذف» — it disappears on a white card.
     */
    val Error get() = token(Color(0xFFF43F5E), Color(0xFFC81E43))

    /**
     * Mark (✓) colour on a filled accent circle: dark ink on light fills such as Amber, Green
     * and Teal, white on dark fills such as Blue and Violet. The threshold is 0.30, between
     * Violet's luminance (0.144) and Teal's (0.372): a white ✓ on Amber/Green/Teal fails
     * contrast (2.15–2.49), while dark ink on them is 6.70–7.76. A 0.5 threshold was wrong —
     * Amber (0.439) and Green (0.411) fell below it and got white.
     */
    fun onAccentFill(accent: Color): Color =
        if (accent.luminance() > 0.30f) Color(0xFF101F30) else Color.White

    /**
     * A stored subject colour (an arbitrary ARGB int from the database, no palette behind it)
     * mapped to something readable at display time only — the stored value never changes.
     * Mid-tone colours pass through untouched; a near-black value is lifted towards white so
     * it does not vanish on the dark surface, and a near-white value is deepened towards black
     * so it does not vanish on the white surface.
     */
    fun subjectColor(stored: Int): Color {
        val raw = Color(stored)
        return when {
            lightMode && raw.luminance() > 0.72f -> lerp(raw, Color.Black, 0.45f)
            !lightMode && raw.luminance() < 0.22f -> lerp(raw, Color.White, 0.55f)
            else -> raw
        }
    }
}

/**
 * One shared spacing scale so screens stop inventing their own paddings: 16dp at the screen
 * edge and inside cards, 12dp between sections, 10dp between list items, 6dp inside chip
 * rows. Corners stay on the [FocusShapes] scale; [radiusCard] is 20.dp and matches
 * `Shapes.medium` exactly — the old 22.dp value contradicted this comment.
 */
object FocusSpacing {
    val screen = 16.dp
    val card = 16.dp
    val section = 12.dp
    val item = 10.dp
    val chip = 6.dp
    val radiusCard = 20.dp
    val radiusChip = 10.dp
    val rowMinHeight = 52.dp
    val progressHeight = 10.dp
}

private fun darkScheme(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0E7C74),
    onPrimaryContainer = Color(0xFFE3FFFA),
    secondary = FocusColors.Violet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF142B45),
    onSecondaryContainer = Color(0xFFDCEAF6),
    tertiary = FocusColors.Teal,
    onTertiary = Color(0xFF04211E),
    background = Color(0xFF08131F),
    onBackground = Color(0xFFF7F8FA),
    surface = Color(0xFF16283C),
    onSurface = Color(0xFFF7F8FA),
    surfaceVariant = Color(0xFF1E3550),
    onSurfaceVariant = Color(0xFFC6D5E4),
    outline = Color(0xFF33506F),
    outlineVariant = Color(0xFF33506F),
    error = FocusColors.Rose,
    onError = Color.White
)

private fun lightScheme(accent: Color) = lightColorScheme(
    primary = lerp(accent, Color.Black, 0.35f),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6F5F1),
    onPrimaryContainer = Color(0xFF06231F),
    secondary = Color(0xFF2F6F9E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDEEAF5),
    onSecondaryContainer = Color(0xFF0E2233),
    tertiary = Color(0xFF0E7C74),
    onTertiary = Color.White,
    background = Color(0xFFF6F9FC),
    onBackground = Color(0xFF101F30),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101F30),
    surfaceVariant = Color(0xFFEDF3F9),
    onSurfaceVariant = Color(0xFF445E78),
    outline = Color(0xFFD8E3EE),
    outlineVariant = Color(0xFFD8E3EE),
    error = Color(0xFFC81E43),
    onError = Color.White
)

/** Zian Black (arfonts.net, free for personal and commercial use) — display headings only. */
internal val ZianHeading = FontFamily(Font(R.font.zian_black))

/**
 * Cairo (SIL Open Font License 1.1, © 2009 The Cairo Project Authors) — the app-wide text
 * face. Static weights downloaded from Google Fonts (fonts.gstatic.com, served from the
 * google/fonts repository, ofl/cairo/OFL.txt): Regular 400, Medium 500, SemiBold 600 and
 * Bold 700, in app/src/main/res/font/cairo_*.ttf. Arabic + Latin coverage was verified
 * (all common Arabic letters and presentation forms present).
 *
 * Zian stays only for the two largest display styles ([Typography.headlineSmall] and
 * [Typography.titleLarge]): its heavy black cut suits a brand heading but is too blunt for
 * card titles and body copy, which now all read in Cairo.
 */
internal val Cairo = FontFamily(
    Font(R.font.cairo_regular, FontWeight.Normal),
    Font(R.font.cairo_medium, FontWeight.Medium),
    Font(R.font.cairo_semibold, FontWeight.SemiBold),
    Font(R.font.cairo_bold, FontWeight.Bold)
)

private val FocusTypography = Typography(
    headlineSmall = TextStyle(fontFamily = ZianHeading, fontSize = 27.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp),
    titleLarge = TextStyle(fontFamily = ZianHeading, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp),
    titleMedium = TextStyle(fontFamily = Cairo, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleSmall = TextStyle(fontFamily = Cairo, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = Cairo, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = Cairo, fontSize = 15.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontFamily = Cairo, fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontFamily = Cairo, fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp),
    labelMedium = TextStyle(fontFamily = Cairo, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    labelSmall = TextStyle(fontFamily = Cairo, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp)
)

/** Shared corner scale: cards use [FocusSpacing.radiusCard] (= medium), dialogs use [Shapes.large]. */
internal val FocusShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun FocusTheme(
    accent: Color = FocusColors.Blue,
    textScale: Float = 1f,
    dark: Boolean = true,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    // Applied before the content composes, so the very first frame already uses the right palette.
    if (FocusColors.lightMode == dark) FocusColors.lightMode = !dark
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * textScale)) {
        MaterialTheme(
            colorScheme = if (dark) darkScheme(accent) else lightScheme(accent),
            typography = FocusTypography,
            shapes = FocusShapes,
        ) {
            CompositionLocalProvider(LocalContentColor provides FocusColors.TextPrimary) {
                content()
            }
        }
    }
}
