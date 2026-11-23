package com.optiker.medidas

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round

object MeasurementMath {

    private const val CARD_W_MM = 85.60
    private const val CARD_H_MM = 53.98
    private const val CARD_RATIO =
        CARD_W_MM / CARD_H_MM

    private data class CardScale(
        val scale: Double,
        val quality: Int
    )

    fun dnp(
        frame: FrameMeasurement
    ): DnpResult? {
        val card =
            cardScale(
                frame.cardCorners
            ) ?: return null

        val dx =
            frame.oe.x -
                frame.od.x

        val dy =
            frame.oe.y -
                frame.od.y

        val pdPx =
            hypot(
                dx.toDouble(),
                dy.toDouble()
            )

        if (pdPx < 10.0) {
            return null
        }

        val ux = dx / pdPx
        val uy = dy / pdPx

        val pd =
            pdPx *
                card.scale

        var dOD =
            abs(
                (frame.od.x -
                    frame.center.x) * ux +
                    (frame.od.y -
                        frame.center.y) * uy
            ) * card.scale

        var dOE =
            abs(
                (frame.oe.x -
                    frame.center.x) * ux +
                    (frame.oe.y -
                        frame.center.y) * uy
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
            cardQuality =
                card.quality
        )
    }

    fun height(
        frame: FrameMeasurement
    ): HeightResult? {
        val card =
            cardScale(
                frame.cardCorners
            ) ?: return null

        val bOD =
            frame.bottomOD
                ?: return null

        val bOE =
            frame.bottomOE
                ?: return null

        val dx =
            frame.oe.x -
                frame.od.x

        val dy =
            frame.oe.y -
                frame.od.y

        val len =
            hypot(
                dx.toDouble(),
                dy.toDouble()
            )

        if (len < 10.0) {
            return null
        }

        var downX =
            -dy / len

        var downY =
            dx / len

        if (downY < 0) {
            downX = -downX
            downY = -downY
        }

        val hOD =
            abs(
                (bOD.x -
                    frame.od.x) * downX +
                    (bOD.y -
                        frame.od.y) * downY
            ) * card.scale

        val hOE =
            abs(
                (bOE.x -
                    frame.oe.x) * downX +
                    (bOE.y -
                        frame.oe.y) * downY
            ) * card.scale

        return HeightResult(
            od = half(hOD),
            oe = half(hOE)
        )
    }

    fun cardQuality(
        frame: FrameMeasurement
    ): Int =
        cardScale(
            frame.cardCorners
        )?.quality ?: 0

    /**
     * Ordem obrigatória:
     * C1 = superior esquerdo
     * C2 = superior direito
     * C3 = inferior direito
     * C4 = inferior esquerdo
     *
     * O cartão deve estar horizontal.
     */
    private fun cardScale(
        c: List<PointF>
    ): CardScale? {
        if (c.size != 4) {
            return null
        }

        val top =
            distance(
                c[0],
                c[1]
            )

        val right =
            distance(
                c[1],
                c[2]
            )

        val bottom =
            distance(
                c[3],
                c[2]
            )

        val left =
            distance(
                c[0],
                c[3]
            )

        val widthPx =
            (top + bottom) /
                2.0

        val heightPx =
            (left + right) /
                2.0

        if (widthPx < 30.0 ||
            heightPx < 18.0
        ) {
            return null
        }

        // Horizontal obrigatório.
        if (widthPx <= heightPx) {
            return null
        }

        // A ordem dos pontos também precisa representar um retângulo horizontal.
        val topDy =
            abs(
                c[1].y -
                    c[0].y
            ).toDouble()

        val bottomDy =
            abs(
                c[2].y -
                    c[3].y
            ).toDouble()

        if (topDy >
            widthPx * 0.22 ||
            bottomDy >
            widthPx * 0.22
        ) {
            return null
        }

        val ratio =
            widthPx /
                heightPx

        val ratioErr =
            abs(
                ratio -
                    CARD_RATIO
            ) / CARD_RATIO

        val parallelWidthErr =
            abs(
                top -
                    bottom
            ) / max(
                widthPx,
                1.0
            )

        val parallelHeightErr =
            abs(
                left -
                    right
            ) / max(
                heightPx,
                1.0
            )

        val parallelErr =
            max(
                parallelWidthErr,
                parallelHeightErr
            )

        val quality =
            (
                100.0 -
                    minOf(
                        60.0,
                        ratioErr * 320.0
                    ) -
                    minOf(
                        30.0,
                        parallelErr * 180.0
                    )
                )
                .roundToIntSafe()
                .coerceIn(
                    0,
                    100
                )

        // Rejeita marcações muito distorcidas.
        if (ratioErr > 0.22 ||
            parallelErr > 0.20
        ) {
            return null
        }

        val scaleW =
            CARD_W_MM /
                widthPx

        val scaleH =
            CARD_H_MM /
                heightPx

        val scale =
            (scaleW +
                scaleH) /
                2.0

        return CardScale(
            scale = scale,
            quality = quality
        )
    }

    private fun distance(
        a: PointF,
        b: PointF
    ): Double =
        hypot(
            (a.x - b.x)
                .toDouble(),
            (a.y - b.y)
                .toDouble()
        )

    private fun half(
        v: Double
    ): Double =
        round(
            v * 2.0
        ) / 2.0

    private fun Double.roundToIntSafe(): Int =
        round(this)
            .toInt()
}
