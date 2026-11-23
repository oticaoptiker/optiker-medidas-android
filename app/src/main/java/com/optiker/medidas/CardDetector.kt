package com.optiker.medidas

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object CardDetector {
    private const val CARD_RATIO = 85.60 / 53.98

    fun detect(bitmap: Bitmap): CardDetection {
        val w = bitmap.width
        val h = bitmap.height

        val guideW = (w * 0.52f).coerceAtLeast(120f)
        val guideH = (guideW / CARD_RATIO).toFloat()
        val guideX = (w - guideW) / 2f
        val guideY = h * 0.055f

        val padX = guideW * 0.16f
        val padY = guideH * 0.22f

        val x0 = max(3, (guideX - padX).roundToInt())
        val x1 = min(w - 4, (guideX + guideW + padX).roundToInt())
        val y0 = max(3, (guideY - padY).roundToInt())
        val y1 = min(h - 4, (guideY + guideH + padY).roundToInt())

        if (x1 - x0 < 80 || y1 - y0 < 40) {
            return invalid()
        }

        fun luminance(x: Int, y: Int): Float {
            val c = bitmap.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
            return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c))
        }

        fun verticalScore(x: Int): Float {
            var score = 0f
            var n = 0
            var y = y0 + 2
            while (y < y1 - 2) {
                score += abs(luminance(x + 2, y) - luminance(x - 2, y))
                n++
                y += 4
            }
            return score / max(1, n)
        }

        fun horizontalScore(y: Int): Float {
            var score = 0f
            var n = 0
            var x = x0 + 2
            while (x < x1 - 2) {
                score += abs(luminance(x, y + 2) - luminance(x, y - 2))
                n++
                x += 4
            }
            return score / max(1, n)
        }

        fun bestAround(target: Int, radius: Int, low: Int, high: Int, scorer: (Int) -> Float): Pair<Int, Float> {
            var bestPos = target
            var bestScore = -1f
            val start = max(low, target - radius)
            val end = min(high, target + radius)
            for (i in start..end) {
                val s = scorer(i)
                if (s > bestScore) {
                    bestScore = s
                    bestPos = i
                }
            }
            return bestPos to bestScore
        }

        val targetL = guideX.roundToInt()
        val targetR = (guideX + guideW).roundToInt()
        val targetT = guideY.roundToInt()
        val targetB = (guideY + guideH).roundToInt()

        val (l, ls) = bestAround(targetL, (padX * 0.95f).roundToInt(), x0 + 2, x1 - 3, ::verticalScore)
        val (r, rs) = bestAround(targetR, (padX * 0.95f).roundToInt(), x0 + 2, x1 - 3, ::verticalScore)
        val (t, ts) = bestAround(targetT, (padY * 0.95f).roundToInt(), y0 + 2, y1 - 3, ::horizontalScore)
        val (b, bs) = bestAround(targetB, (padY * 0.95f).roundToInt(), y0 + 2, y1 - 3, ::horizontalScore)

        val width = (r - l).toFloat()
        val height = (b - t).toFloat()
        if (width <= 0 || height <= 0) return invalid()

        val ratio = width / height
        val ratioErr = abs(ratio - CARD_RATIO) / CARD_RATIO
        val sizeErrW = abs(width - guideW) / guideW
        val sizeErrH = abs(height - guideH) / guideH
        val edge = (ls + rs + ts + bs) / 4f

        val quality = (
            100f -
                min(45f, ratioErr.toFloat() * 260f) -
                min(25f, sizeErrW * 80f) -
                min(20f, sizeErrH * 70f) -
                max(0f, 12f - edge) * 2.5f
            ).roundToInt().coerceIn(0, 100)

        val valid = quality >= 78 &&
            ratioErr < 0.12 &&
            width in (guideW * 0.76f)..(guideW * 1.24f) &&
            height in (guideH * 0.72f)..(guideH * 1.28f) &&
            edge >= 8.5f

        return CardDetection(
            corners = mutableListOf(
                PointF(l.toFloat(), t.toFloat()),
                PointF(r.toFloat(), t.toFloat()),
                PointF(r.toFloat(), b.toFloat()),
                PointF(l.toFloat(), b.toFloat())
            ),
            quality = quality,
            widthPx = width,
            heightPx = height,
            valid = valid
        )
    }

    private fun invalid() = CardDetection(
        corners = mutableListOf(),
        quality = 0,
        widthPx = 0f,
        heightPx = 0f,
        valid = false
    )
}
