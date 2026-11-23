package com.focusstudy.poc

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable

/** Shared motion timings: 150-300 ms, gentle, and disabled when the person prefers reduced motion. */
internal object MotionSpec {
    const val SHORT_MS = 150
    const val MEDIUM_MS = 220
    const val LONG_MS = 280

    @Composable
    fun enter(reduceMotion: Boolean): EnterTransition = when {
        reduceMotion -> fadeIn(tween(SHORT_MS))
        else -> fadeIn(tween(MEDIUM_MS)) + slideInVertically(tween(MEDIUM_MS)) { it / 12 }
    }

    @Composable
    fun exit(reduceMotion: Boolean): ExitTransition = when {
        reduceMotion -> fadeOut(tween(SHORT_MS))
        else -> fadeOut(tween(MEDIUM_MS)) + slideOutVertically(tween(MEDIUM_MS)) { it / 12 }
    }
}
