package com.focusstudy.poc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * On-device, deterministic layered cartoon avatar. The photo is used only as a seed: a few sampled
 * colours decide the skin, hair and backdrop of a drawn character. Nothing is uploaded, no model
 * download or network call is involved, and the same photo always produces the same character.
 */
internal data class AvatarPalette(val skin: Int, val hair: Int, val background: Int, val accent: Int)

internal object CartoonAvatar {
    const val SIZE = 512
    val STYLES = listOf("دافئ", "ليلي", "ألوان مائية", "خطوط واضحة")

    private val styleTints = listOf(0xFFF2A65A.toInt(), 0xFF7D9BEB.toInt(), 0xFFE68FB6.toInt(), 0xFF43BFA0.toInt())
    private val fallbackBase = 0xFFC99A74.toInt()

    fun fileName(style: Int): String = "student_companion_cartoon_${style.coerceIn(0, STYLES.lastIndex)}.png"

    /** Stable hash of the sampled photo pixels; used for small, repeatable character variations. */
    fun seedFor(pixels: IntArray): Long {
        var hash = 1469598103934665603L
        var i = 0
        while (i < pixels.size) {
            hash = (hash xor pixels[i].toLong()) * 1099511628211L
            i += 7
        }
        return hash
    }

    /** Derives a palette from sampled ARGB pixels. Pure so it can be tested without Android. */
    fun paletteFrom(pixels: IntArray, style: Int): AvatarPalette {
        val index = style.coerceIn(0, STYLES.lastIndex)
        var count = 0
        var r = 0L
        var g = 0L
        var b = 0L
        var skinCount = 0
        var sr = 0L
        var sg = 0L
        var sb = 0L
        for (pixel in pixels) {
            if ((pixel ushr 24) < 128) continue
            val pr = (pixel shr 16) and 0xFF
            val pg = (pixel shr 8) and 0xFF
            val pb = pixel and 0xFF
            r += pr
            g += pg
            b += pb
            count++
            // Warm, mid-bright pixels are the most likely skin sample in a portrait.
            if (pr > pg + 10 && pg > pb + 4 && pr in 70..249 && pg in 45..220) {
                sr += pr
                sg += pg
                sb += pb
                skinCount++
            }
        }
        val base = if (count == 0) fallbackBase else rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
        val skin = if (skinCount > count / 12) {
            rgb((sr / skinCount).toInt(), (sg / skinCount).toInt(), (sb / skinCount).toInt())
        } else {
            lighten(base, 0.24f)
        }
        val hair = darken(base, 0.62f)
        val tint = styleTints[index]
        val background = when (index) {
            1 -> blend(darken(base, 0.5f), tint, 0.45f)
            3 -> blend(0xFFF4F7FB.toInt(), tint, 0.16f)
            else -> blend(lighten(base, 0.35f), tint, 0.42f)
        }
        return AvatarPalette(skin, hair, background, tint)
    }

    /** Draws the character from a photo seed. Called off the main thread; returns a new bitmap. */
    fun stylize(source: Bitmap, style: Int): Bitmap {
        val samples = 32
        val sample = Bitmap.createScaledBitmap(source, samples, samples, true)
        val pixels = IntArray(samples * samples)
        sample.getPixels(pixels, 0, samples, 0, 0, samples, samples)
        val palette = paletteFrom(pixels, style)
        val variation = (seedFor(pixels) and 0x7).toInt()
        if (sample !== source) sample.recycle()

        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(palette.background)

        val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.accent
            alpha = if (style == 1) 34 else 48
        }
        canvas.drawCircle(96f, 96f, 150f, wash)
        canvas.drawCircle(430f, 420f, 190f, wash)

        val skinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.skin }
        val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.hair }
        val shirtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.accent }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            color = palette.hair
            strokeWidth = if (style == 3) 9f else 4f
        }

        // Shoulders and neck.
        canvas.drawOval(RectF(46f, 338f + variation, 466f, 640f), shirtPaint)
        canvas.drawRect(228f, 300f, 284f, 400f, skinPaint)

        // Face.
        val face = RectF(140f, 112f, 372f, 382f)
        canvas.drawOval(face, skinPaint)
        if (style == 3) canvas.drawOval(face, linePaint)

        // Hair: four simple, distinct silhouettes.
        val hair = Path()
        when (style) {
            0 -> {
                hair.moveTo(134f, 216f)
                hair.quadTo(126f, 92f, 256f, 92f)
                hair.quadTo(390f, 92f, 378f, 216f)
                hair.quadTo(330f, 150f, 256f, 158f)
                hair.quadTo(184f, 150f, 134f, 216f)
            }
            1 -> {
                hair.moveTo(132f, 200f)
                hair.quadTo(140f, 96f, 256f, 96f)
                hair.quadTo(372f, 96f, 380f, 200f)
                hair.lineTo(132f, 200f)
                canvas.drawRect(112f, 182f, 400f, 214f, hairPaint)
            }
            2 -> {
                hair.moveTo(136f, 220f)
                hair.quadTo(120f, 110f, 210f, 104f)
                hair.quadTo(250f, 150f, 288f, 100f)
                hair.quadTo(392f, 112f, 376f, 220f)
                hair.quadTo(320f, 152f, 256f, 162f)
                hair.quadTo(190f, 150f, 136f, 220f)
            }
            else -> {
                hair.moveTo(138f, 206f)
                hair.quadTo(140f, 98f, 256f, 98f)
                hair.quadTo(372f, 98f, 374f, 206f)
                hair.quadTo(300f, 144f, 256f, 152f)
                hair.quadTo(206f, 142f, 138f, 206f)
            }
        }
        hair.close()
        canvas.drawPath(hair, hairPaint)
        if (style == 3) canvas.drawPath(hair, linePaint)

        // Eyes, brows and a calm smile.
        val eyeY = 232f + (variation % 3) * 2f
        val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = darken(palette.hair, 0.25f)
            this.style = if (style == 3) Paint.Style.STROKE else Paint.Style.FILL
            strokeWidth = 6f
        }
        canvas.drawCircle(206f, eyeY, if (style == 3) 12f else 11f, eyePaint)
        canvas.drawCircle(306f, eyeY, if (style == 3) 12f else 11f, eyePaint)
        val browPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.hair
            this.style = Paint.Style.STROKE
            strokeWidth = 7f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(186f, eyeY - 34f, 224f, eyeY - 30f, browPaint)
        canvas.drawLine(288f, eyeY - 30f, 326f, eyeY - 34f, browPaint)
        canvas.drawArc(RectF(216f, 276f, 296f, 332f), 8f, 164f, false, browPaint.apply { strokeWidth = 6f })

        // Cheeks add warmth without trying to mimic the photo pixel by pixel.
        val cheekPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.accent
            alpha = 70
        }
        canvas.drawCircle(178f, 286f, 18f, cheekPaint)
        canvas.drawCircle(334f, 286f, 18f, cheekPaint)
        return out
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        0xFF000000.toInt() or ((r.coerceIn(0, 255) shl 16)) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun lighten(color: Int, amount: Float): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return rgb(
            (r + (255 - r) * amount).toInt(),
            (g + (255 - g) * amount).toInt(),
            (b + (255 - b) * amount).toInt()
        )
    }

    private fun darken(color: Int, amount: Float): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return rgb((r * (1 - amount)).toInt(), (g * (1 - amount)).toInt(), (b * (1 - amount)).toInt())
    }

    private fun blend(first: Int, second: Int, amount: Float): Int {
        val ar = (first shr 16) and 0xFF
        val ag = (first shr 8) and 0xFF
        val ab = first and 0xFF
        val br = (second shr 16) and 0xFF
        val bg = (second shr 8) and 0xFF
        val bb = second and 0xFF
        return rgb(
            (ar + (br - ar) * amount).toInt(),
            (ag + (bg - ag) * amount).toInt(),
            (ab + (bb - ab) * amount).toInt()
        )
    }
}
