package com.optiker.medidas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.min

class MeasurementOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var cardValid: Boolean = false
    var faceValid: Boolean = false
    var poseValid: Boolean = false
    var distanceValid: Boolean = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 34f
        style = Paint.Style.FILL
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        val cardW = min(w * 0.58f, 420f)
        val cardH = cardW * (53.98f / 85.60f)
        val cardRect = RectF(
            (w - cardW) / 2f,
            h * 0.055f,
            (w + cardW) / 2f,
            h * 0.055f + cardH
        )

        paint.color = ContextCompat.getColor(
            context,
            if (cardValid) R.color.optiker_green else R.color.warn
        )
        paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 12f), 0f)
        canvas.drawRoundRect(cardRect, 18f, 18f, paint)

        val faceRect = RectF(
            w * 0.18f,
            h * 0.28f,
            w * 0.82f,
            h * 0.92f
        )
        paint.color = ContextCompat.getColor(
            context,
            if (faceValid && poseValid && distanceValid) R.color.optiker_green else R.color.optiker_lavender
        )
        canvas.drawOval(faceRect, paint)
        paint.pathEffect = null

        textPaint.color = ContextCompat.getColor(context, R.color.white)
        canvas.drawText("CARTÃO", cardRect.left + 10f, cardRect.top - 12f, textPaint)
    }
}
