package com.optiker.medidas

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

object MeasurementMath {
    private const val CARD_W_MM = 85.60
    private const val CARD_H_MM = 53.98
    private const val CARD_RATIO = CARD_W_MM / CARD_H_MM

    private data class CardScale(
        val scale: Double,
        val quality: Int
    )

    fun dnp(frame: FrameMeasurement): DnpResult? {
        val card = cardScale(frame.cardCorners) ?: return null

        val dx = frame.oe.x - frame.od.x
        val dy = frame.oe.y - frame.od.y
        val pdPx = hypot(dx.toDouble(), dy.toDouble())
        if (pdPx < 10.0) return null

        val ux = dx / pdPx
        val uy = dy / pdPx

        val pd = pdPx * card.scale

        var dOD = abs(
            (frame.od.x - frame.center.x) * ux +
                (frame.od.y - frame.center.y) * uy
        ) * card.scale

        var dOE = abs(
            (frame.oe.x - frame.center.x) * ux +
                (frame.oe.y - frame.center.y) * uy
        ) * card.scale

        val sum = dOD + dOE
        if (sum > 0) {
            val k = pd / sum
            dOD *= k
            dOE *= k
        }

        return DnpResult(
            od = half(dOD),
            oe = half(dOE),
            pd = half(pd),
            cardQuality = card.quality
        )
    }

    fun height(frame: FrameMeasurement): HeightResult? {
        val card = cardScale(frame.cardCorners) ?: return null
        val bOD = frame.bottomOD ?: return null
        val bOE = frame.bottomOE ?: return null

        val dx = frame.oe.x - frame.od.x
        val dy = frame.oe.y - frame.od.y
        val len = hypot(dx.toDouble(), dy.toDouble())
        if (len < 10.0) return null

        var downX = -dy / len
        var downY = dx / len
        if (downY < 0) {
            downX = -downX
            downY = -downY
        }

        val hOD = abs(
            (bOD.x - frame.od.x) * downX +
                (bOD.y - frame.od.y) * downY
        ) * card.scale

        val hOE = abs(
            (bOE.x - frame.oe.x) * downX +
                (bOE.y - frame.oe.y) * downY
        ) * card.scale

        return HeightResult(
            od = half(hOD),
            oe = half(hOE)
        )
    }

    fun cardQuality(frame: FrameMeasurement): Int =
        cardScale(frame.cardCorners)?.quality ?: 0

    /**
     * O cartão pode estar horizontal ou vertical.
     * Identificamos automaticamente o par de lados longos e curtos.
     */
    private fun cardScale(c: List<PointF>): CardScale? {
        if (c.size != 4) return null

        val top = distance(c[0], c[1])
        val right = distance(c[1], c[2])
        val bottom = distance(c[3], c[2])
        val left = distance(c[0], c[3])

        val pairA = (top + bottom) / 2.0
        val pairB = (left + right) / 2.0

        val longPx = max(pairA, pairB)
        val shortPx = min(pairA, pairB)
        if (longPx < 20.0 || shortPx < 12.0) return null

        val ratioErr = abs(longPx / shortPx - CARD_RATIO) / CARD_RATIO

        val parallelA = abs(top - bottom) / max(pairA, 1.0)
        val parallelB = abs(left - right) / max(pairB, 1.0)
        val parallelErr = max(parallelA, parallelB)

        val quality = (
            100.0 -
                minOf(55.0, ratioErr * 280.0) -
                minOf(35.0, parallelErr * 180.0)
            ).roundToIntSafe().coerceIn(0, 100)

        val scaleLong = CARD_W_MM / longPx
        val scaleShort = CARD_H_MM / shortPx

        // Média das duas escalas reduz pequenas diferenças de detecção/perspectiva.
        val scale = (scaleLong + scaleShort) / 2.0

        return CardScale(scale = scale, quality = quality)
    }

    private fun distance(a: PointF, b: PointF): Double =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    private fun half(v: Double): Double = round(v * 2.0) / 2.0

    private fun Double.roundToIntSafe(): Int = round(this).toInt()
}
