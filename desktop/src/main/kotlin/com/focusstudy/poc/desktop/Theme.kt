package com.focusstudy.poc.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * ألوان العلامة — نفسها في أندرويد: كحلي #142B45 وتركوازي #16B8A6.
 * فاتح/داكن مع تباين مقروء في الحالتين (قاعدة الإصدار في المشروع).
 */
object Brand {
    val Navy = Color(0xFF142B45)
    val NavyDark = Color(0xFF0E1B29)
    val NavySurface = Color(0xFF1B3A58)
    val Teal = Color(0xFF16B8A6)
    val TealDark = Color(0xFF0F8A7D)
    val TealContainerLight = Color(0xFFD9F4F0)
    val TealContainerDark = Color(0xFF134E47)
    val BackgroundLight = Color(0xFFF7F8FA)
    val SurfaceLight = Color(0xFFFFFFFF)
    val OnLight = Color(0xFF142B45)
    val OnLightSecondary = Color(0xFF4A5B6E)
    val BackgroundDark = Color(0xFF0E1B29)
    val SurfaceDark = Color(0xFF16273A)
    val OnDark = Color(0xFFE8EEF4)
    val OnDarkSecondary = Color(0xFFA9BACA)
}

private val LightScheme = lightColorScheme(
    primary = Brand.Teal,
    onPrimary = Color.White,
    primaryContainer = Brand.TealContainerLight,
    onPrimaryContainer = Brand.Navy,
    secondary = Brand.Navy,
    onSecondary = Color.White,
    background = Brand.BackgroundLight,
    onBackground = Brand.OnLight,
    surface = Brand.SurfaceLight,
    onSurface = Brand.OnLight,
    surfaceVariant = Color(0xFFE8EDF2),
    onSurfaceVariant = Brand.OnLightSecondary,
    error = Color(0xFFB3261E),
)

private val DarkScheme = darkColorScheme(
    primary = Brand.Teal,
    onPrimary = Color(0xFF06231F),
    primaryContainer = Brand.TealContainerDark,
    onPrimaryContainer = Color(0xFFC9F2EC),
    secondary = Color(0xFF7FB3D5),
    onSecondary = Brand.NavyDark,
    background = Brand.BackgroundDark,
    onBackground = Brand.OnDark,
    surface = Brand.SurfaceDark,
    onSurface = Brand.OnDark,
    surfaceVariant = Color(0xFF22374E),
    onSurfaceVariant = Brand.OnDarkSecondary,
    error = Color(0xFFFFB4AB),
)

@Composable
fun MonazzemDesktopTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}
