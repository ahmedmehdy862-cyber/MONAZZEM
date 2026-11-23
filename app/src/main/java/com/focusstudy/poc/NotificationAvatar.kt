package com.focusstudy.poc

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Bounded avatar decoding for notification icons.
 *
 * A notification large icon is never larger than a few dozen density-independent pixels, but the
 * saved avatar can be a full-size photo. Decoding it at full resolution for every alert used to
 * cost memory and time exactly when the phone was waking for a prayer. Sampling first keeps the
 * alarm path cheap; the pure [sampleSize] rule is unit tested.
 */
internal object NotificationAvatar {
    const val MAX_ICON_PX = 128

    fun sampleSize(width: Int, height: Int, maxPx: Int = MAX_ICON_PX): Int {
        val limit = maxPx.coerceAtLeast(1)
        var sample = 1
        while (width / sample > limit || height / sample > limit) sample *= 2
        return sample.coerceAtLeast(1)
    }

    fun decode(path: String?, maxPx: Int = MAX_ICON_PX): Bitmap? {
        if (path.isNullOrBlank()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx)
            }
            BitmapFactory.decodeFile(path, options)
        }.getOrNull()
    }
}
