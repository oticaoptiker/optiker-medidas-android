package com.optiker.medidas.v5

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.optiker.medidas.R
import kotlin.math.max
import kotlin.math.min

class V5FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var result: DenseFaceMeshResult? = null
        set(value) {
            field = value
            invalidate()
        }

    var poseValid: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val densePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 60
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 27f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = result ?: return
        val t = transform(r)

        densePaint.color = ContextCompat.getColor(context, R.color.optiker_lavender)
        corePaint.color = ContextCompat.getColor(
            context,
            if (poseValid) R.color.optiker_green else R.color.optiker_purple
        )

        val step = max(1, r.densePoints.size / 1300)
        var i = 0
        while (i < r.densePoints.size) {
            val p = map(r.densePoints[i], t)
            canvas.drawCircle(p.first, p.second, 1.2f, densePaint)
            i += step
        }

        for (p3 in r.corePoints) {
            val p = map(p3, t)
            canvas.drawCircle(p.first, p.second, 2.6f, corePaint)
        }

        drawContour(
            canvas, r.faceOval, t,
            ContextCompat.getColor(context, R.color.optiker_lavender), true
        )
        drawContour(
            canvas, r.noseBridge, t,
            ContextCompat.getColor(context, R.color.optiker_green), false
        )
        drawContour(
            canvas, r.leftEye, t,
            ContextCompat.getColor(context, R.color.white), true
        )
        drawContour(
            canvas, r.rightEye, t,
            ContextCompat.getColor(context, R.color.white), true
        )

        textPaint.color = ContextCompat.getColor(context, R.color.white)
        canvas.drawText(
            "${r.coreCount} landmarks • ${r.denseCount} pontos densos",
            18f, 38f, textPaint
        )
    }

    private data class Transform(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
        val sourceWidth: Int
    )

    private fun transform(r: DenseFaceMeshResult): Transform {
        val scale = min(
            width.toFloat() / r.sourceWidth,
            height.toFloat() / r.sourceHeight
        )
        val drawnW = r.sourceWidth * scale
        val drawnH = r.sourceHeight * scale
        return Transform(
            scale = scale,
            offsetX = (width - drawnW) / 2f,
            offsetY = (height - drawnH) / 2f,
            sourceWidth = r.sourceWidth
        )
    }

    private fun map(p: Point3, t: Transform): Pair<Float, Float> {
        val mirroredX = t.sourceWidth - p.x
        return Pair(
            t.offsetX + mirroredX * t.scale,
            t.offsetY + p.y * t.scale
        )
    }

    private fun drawContour(
        canvas: Canvas,
        points: List<Point3>,
        t: Transform,
        color: Int,
        close: Boolean
    ) {
        if (points.size < 2) return
        linePaint.color = color
        val path = Path()
        val first = map(points.first(), t)
        path.moveTo(first.first, first.second)
        for (index in 1 until points.size) {
            val p = map(points[index], t)
            path.lineTo(p.first, p.second)
        }
        if (close) path.close()
        canvas.drawPath(path, linePaint)
    }
}
