package com.optiker.medidas

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object CardDetector {
    private const val CARD_RATIO = 85.60 / 53.98

    /**
     * Procura um cartão ID-1 nas laterais do rosto.
     * Aceita cartão horizontal ou vertical e não depende de uma posição fixa.
     *
     * O detector trabalha em uma cópia reduzida do frame para manter a análise
     * suficientemente rápida para uso em tempo real.
     */
    fun detect(bitmap: Bitmap, faceBounds: RectF): CardDetection {
        val originalW = bitmap.width
        val originalH = bitmap.height
        if (originalW < 200 || originalH < 300) return empty()

        val smallW = min(360, originalW)
        val scale = smallW.toFloat() / originalW.toFloat()
        val smallH = max(1, (originalH * scale).roundToInt())

        val small = if (smallW == originalW) bitmap else
            Bitmap.createScaledBitmap(bitmap, smallW, smallH, true)

        try {
            val pixels = IntArray(smallW * smallH)
            small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH)

            val lum = IntArray(pixels.size)
            for (i in pixels.indices) {
                val c = pixels[i]
                lum[i] = (
                    0.299f * Color.red(c) +
                    0.587f * Color.green(c) +
                    0.114f * Color.blue(c)
                    ).roundToInt()
            }

            val gx = IntArray(pixels.size)
            val gy = IntArray(pixels.size)
            for (y in 1 until smallH - 1) {
                val row = y * smallW
                for (x in 1 until smallW - 1) {
                    val i = row + x
                    gx[i] = abs(lum[i + 1] - lum[i - 1])
                    gy[i] = abs(lum[i + smallW] - lum[i - smallW])
                }
            }

            val intGX = integral(gx, smallW, smallH)
            val intGY = integral(gy, smallW, smallH)

            val f = RectF(
                faceBounds.left * scale,
                faceBounds.top * scale,
                faceBounds.right * scale,
                faceBounds.bottom * scale
            )

            val faceH = f.height().coerceAtLeast(1f)
            val yTop = max(2f, f.top - faceH * 0.10f)
            val yBottom = min((smallH - 3).toFloat(), f.top + faceH * 0.82f)

            val zones = mutableListOf<RectF>()

            // Lado esquerdo da face.
            if (f.left > smallW * 0.10f) {
                zones += RectF(
                    max(2f, f.left - smallW * 0.43f),
                    yTop,
                    min(f.left + smallW * 0.08f, smallW - 3f),
                    yBottom
                )
            }

            // Lado direito da face.
            if (f.right < smallW * 0.90f) {
                zones += RectF(
                    max(2f, f.right - smallW * 0.08f),
                    yTop,
                    min((smallW - 3).toFloat(), f.right + smallW * 0.43f),
                    yBottom
                )
            }

            if (zones.isEmpty()) return empty()

            data class Candidate(
                val x: Int,
                val y: Int,
                val w: Int,
                val h: Int,
                val score: Float
            )

            var best: Candidate? = null

            // Tamanhos plausíveis do cartão em relação ao frame.
            val longSides = listOf(
                (smallW * 0.18f).roundToInt(),
                (smallW * 0.22f).roundToInt(),
                (smallW * 0.26f).roundToInt(),
                (smallW * 0.30f).roundToInt(),
                (smallW * 0.34f).roundToInt()
            ).distinct().filter { it >= 60 }

            val step = max(4, smallW / 90)

            fun rectMean(integral: LongArray, x: Int, y: Int, w: Int, h: Int): Float {
                if (w <= 0 || h <= 0) return 0f
                val x0 = x.coerceIn(0, smallW)
                val y0 = y.coerceIn(0, smallH)
                val x1 = (x + w).coerceIn(0, smallW)
                val y1 = (y + h).coerceIn(0, smallH)
                if (x1 <= x0 || y1 <= y0) return 0f
                val stride = smallW + 1
                val sum =
                    integral[y1 * stride + x1] -
                    integral[y0 * stride + x1] -
                    integral[y1 * stride + x0] +
                    integral[y0 * stride + x0]
                return sum.toFloat() / ((x1 - x0) * (y1 - y0)).toFloat()
            }

            fun evaluate(x: Int, y: Int, cw: Int, ch: Int): Float {
                val t = max(2, min(cw, ch) / 24)

                val left = rectMean(intGX, x - t, y, t * 2 + 1, ch)
                val right = rectMean(intGX, x + cw - t, y, t * 2 + 1, ch)
                val top = rectMean(intGY, x, y - t, cw, t * 2 + 1)
                val bottom = rectMean(intGY, x, y + ch - t, cw, t * 2 + 1)

                val border = (left + right + top + bottom) / 4f
                val inside = rectMean(
                    intGX,
                    x + cw / 5,
                    y + ch / 5,
                    cw * 3 / 5,
                    ch * 3 / 5
                ) + rectMean(
                    intGY,
                    x + cw / 5,
                    y + ch / 5,
                    cw * 3 / 5,
                    ch * 3 / 5
                )

                // Favorece contornos fortes e penaliza um pouco regiões internas muito "ruidosas".
                return border - inside * 0.08f
            }

            for (zone in zones) {
                for (longSide in longSides) {
                    val orientations = listOf(
                        longSide to max(38, (longSide / CARD_RATIO).roundToInt()), // horizontal
                        max(38, (longSide / CARD_RATIO).roundToInt()) to longSide  // vertical
                    )

                    for ((cw, ch) in orientations) {
                        val zx0 = max(2, zone.left.roundToInt())
                        val zy0 = max(2, zone.top.roundToInt())
                        val zx1 = min(smallW - cw - 3, zone.right.roundToInt() - cw)
                        val zy1 = min(smallH - ch - 3, zone.bottom.roundToInt() - ch)

                        if (zx1 <= zx0 || zy1 <= zy0) continue

                        var y = zy0
                        while (y <= zy1) {
                            var x = zx0
                            while (x <= zx1) {
                                val s = evaluate(x, y, cw, ch)
                                if (best == null || s > best!!.score) {
                                    best = Candidate(x, y, cw, ch, s)
                                }
                                x += step
                            }
                            y += step
                        }
                    }
                }
            }

            val c = best ?: return empty()

            val edgeQuality = ((c.score - 7f) * 7.5f).roundToInt().coerceIn(0, 100)

            // O cartão deve ficar próximo ao rosto, mas sem obrigar um ponto exato.
            val cardCenterX = c.x + c.w / 2f
            val cardCenterY = c.y + c.h / 2f
            val faceCenterY = f.centerY()
            val verticalDistance = abs(cardCenterY - faceCenterY) / faceH
            val proximityPenalty = (max(0f, verticalDistance - 0.34f) * 70f).roundToInt()

            val quality = (edgeQuality - proximityPenalty).coerceIn(0, 100)
            val valid = quality >= 70 && c.score >= 10.5f

            val inv = 1f / scale
            return CardDetection(
                corners = mutableListOf(
                    PointF(c.x * inv, c.y * inv),
                    PointF((c.x + c.w) * inv, c.y * inv),
                    PointF((c.x + c.w) * inv, (c.y + c.h) * inv),
                    PointF(c.x * inv, (c.y + c.h) * inv)
                ),
                quality = quality,
                widthPx = c.w * inv,
                heightPx = c.h * inv,
                valid = valid
            )
        } finally {
            if (small !== bitmap && !small.isRecycled) small.recycle()
        }
    }

    fun empty() = CardDetection(
        corners = mutableListOf(),
        quality = 0,
        widthPx = 0f,
        heightPx = 0f,
        valid = false
    )

    private fun integral(values: IntArray, w: Int, h: Int): LongArray {
        val stride = w + 1
        val out = LongArray((w + 1) * (h + 1))
        for (y in 1..h) {
            var rowSum = 0L
            for (x in 1..w) {
                rowSum += values[(y - 1) * w + (x - 1)].toLong()
                out[y * stride + x] = out[(y - 1) * stride + x] + rowSum
            }
        }
        return out
    }
}
