package com.optiker.medidas

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round

object MeasurementMath {
    private const val CARD_W_MM = 85.60
    private const val CARD_H_MM = 53.98
    private const val CARD_RATIO = CARD_W_MM / CARD_H_MM

    fun dnp(frame: FrameMeasurement): DnpResult? {
        if (frame.cardCorners.size != 4) return null

        val c = frame.cardCorners
        val top = distance(c[0], c[1])
        val right = distance(c[1], c[2])
        val bottom = distance(c[3], c[2])
        val left = distance(c[0], c[3])

        val cardW = (top + bottom) / 2.0
        val cardH = (left + right) / 2.0
        if (cardW < 20.0 || cardH < 12.0) return null

        val ratioErr = abs(cardW / cardH - CARD_RATIO) / CARD_RATIO
        val parallelErr = max(
            abs(top - bottom) / cardW,
            abs(left - right) / cardH
        )
        val quality = (
            100.0 -
                minOf(55.0, ratioErr * 280.0) -
                minOf(35.0, parallelErr * 180.0)
            ).roundToIntSafe().coerceIn(0, 100)

        val scaleX = CARD_W_MM / cardW

        val dx = frame.oe.x - frame.od.x
        val dy = frame.oe.y - frame.od.y
        val pdPx = hypot(dx.toDouble(), dy.toDouble())
        if (pdPx < 10.0) return null

        val ux = dx / pdPx
        val uy = dy / pdPx

        val pd = pdPx * scaleX

        var dOD = abs(
            (frame.od.x - frame.center.x) * ux +
                (frame.od.y - frame.center.y) * uy
        ) * scaleX

        var dOE = abs(
            (frame.oe.x - frame.center.x) * ux +
                (frame.oe.y - frame.center.y) * uy
        ) * scaleX

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
            cardQuality = quality
        )
    }

    fun height(frame: FrameMeasurement): HeightResult? {
        val base = dnp(frame) ?: return null
        val bOD = frame.bottomOD ?: return null
        val bOE = frame.bottomOE ?: return null
        if (frame.cardCorners.size != 4) return null

        val c = frame.cardCorners
        val right = distance(c[1], c[2])
        val left = distance(c[0], c[3])
        val cardH = (left + right) / 2.0
        if (cardH < 12.0) return null
        val scaleY = CARD_H_MM / cardH

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
        ) * scaleY

        val hOE = abs(
            (bOE.x - frame.oe.x) * downX +
                (bOE.y - frame.oe.y) * downY
        ) * scaleY

        // Reference base is intentionally evaluated so card/geometry validity remains shared.
        @Suppress("UNUSED_VARIABLE")
        val unused = base

        return HeightResult(od = half(hOD), oe = half(hOE))
    }

    fun cardQuality(frame: FrameMeasurement): Int = dnp(frame)?.cardQuality ?: 0

    private fun distance(a: PointF, b: PointF): Double =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    private fun half(v: Double): Double = round(v * 2.0) / 2.0

    private fun Double.roundToIntSafe(): Int = round(this).toInt()
}
