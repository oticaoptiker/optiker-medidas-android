package com.optiker.medidas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class MeasurementOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var phase: CapturePhase = CapturePhase.FACE
    var cardValid: Boolean = false
    var faceValid: Boolean = false
    var poseValid: Boolean = false
    var distanceValid: Boolean = false

    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }

    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 28f
            style = Paint.Style.FILL
            typeface =
                android.graphics.Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        val faceRect =
            RectF(
                w * 0.17f,
                h * 0.20f,
                w * 0.83f,
                h * 0.88f
            )

        paint.color =
            ContextCompat.getColor(
                context,
                if (faceValid && poseValid) {
                    R.color.optiker_green
                } else {
                    R.color.optiker_lavender
                }
            )

        paint.pathEffect =
            android.graphics.DashPathEffect(
                floatArrayOf(
                    18f,
                    12f
                ),
                0f
            )

        canvas.drawOval(
            faceRect,
            paint
        )

        paint.pathEffect = null

        textPaint.color =
            ContextCompat.getColor(
                context,
                R.color.white
            )

        if (phase == CapturePhase.FACE) {
            canvas.drawText(
                "ROSTO RETO + CARTÃO HORIZONTAL",
                w / 2f,
                h * 0.14f,
                textPaint
            )
        } else {
            textPaint.color =
                ContextCompat.getColor(
                    context,
                    R.color.optiker_green
                )

            canvas.drawText(
                "ROSTO OK ✓",
                w / 2f,
                h * 0.12f,
                textPaint
            )

            textPaint.color =
                ContextCompat.getColor(
                    context,
                    R.color.white
                )

            textPaint.textSize = 23f

            canvas.drawText(
                "CARTÃO INTEIRO AO LADO • TOQUE EM TIRAR FOTO",
                w / 2f,
                h * 0.16f,
                textPaint
            )

            textPaint.textSize = 28f
        }
    }
}
