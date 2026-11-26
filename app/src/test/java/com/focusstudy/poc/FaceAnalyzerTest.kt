package com.focusstudy.poc

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * FaceAnalyzer must degrade gracefully when ML Kit cannot run (hotfix 0.33.1):
 * analyze() returns null instead of throwing, so a photo pick falls back to
 * the old colour-seed cartoon and the app never dies on detection.
 *
 * This also guards the lazy-init path: with the auto-init provider disabled in
 * the manifest, ML Kit is initialized inside analyze() only when a photo is
 * actually analyzed. Under Robolectric the native model cannot load, so the
 * call must still finish (or time out) with null — never throw, never hang
 * the test.
 *
 * Runs under Robolectric (no device needed) with the runtime pinned to SDK 35.
 * Run with `./gradlew testDebugUnitTest` on a machine with the Android SDK;
 * the release workflow does not run unit tests, so run this before merging.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FaceAnalyzerTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun analyzeReturnsNullInsteadOfThrowingWhenMlKitUnavailable() = runBlocking {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val result = withTimeoutOrNull(15_000) { FaceAnalyzer.analyze(context, bitmap) }
        assertNull(result)
    }
}
