package com.optiker.medidas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.min

class ReviewOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var frame: FrameMeasurement? = null
        set(value) {
            field = value
            invalidate()
        }

    var selected: HandleType = HandleType.OD
        set(value) {
            field = value
            invalidate()
        }

    var onPointChanged: (() -> Unit)? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val f = frame ?: return
        val transform = transform(f)

        fun map(p: PointF): PointF = PointF(
            transform.offsetX + p.x * transform.scale,
            transform.offsetY + p.y * transform.scale
        )

        if (f.cardCorners.size == 4) {
            val pts = f.cardCorners.map(::map)
            linePaint.color = ContextCompat.getColor(context, R.color.optiker_lavender)
            canvas.drawLine(pts[0].x, pts[0].y, pts[1].x, pts[1].y, linePaint)
            canvas.drawLine(pts[1].x, pts[1].y, pts[2].x, pts[2].y, linePaint)
            canvas.drawLine(pts[2].x, pts[2].y, pts[3].x, pts[3].y, linePaint)
            canvas.drawLine(pts[3].x, pts[3].y, pts[0].x, pts[0].y, linePaint)
            pts.forEachIndexed { i, p ->
                drawPoint(canvas, p, "C${i + 1}", cardHandle(i))
            }
        }

        drawPoint(canvas, map(f.od), "OD", HandleType.OD)
        drawPoint(canvas, map(f.oe), "OE", HandleType.OE)
        drawPoint(canvas, map(f.center), "CENTRO", HandleType.CENTER)
        f.bottomOD?.let { drawPoint(canvas, map(it), "BORDA OD", HandleType.BOTTOM_OD) }
        f.bottomOE?.let { drawPoint(canvas, map(it), "BORDA OE", HandleType.BOTTOM_OE) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN && event.action != MotionEvent.ACTION_MOVE) return true
        val f = frame ?: return true
        val t = transform(f)
        val bx = ((event.x - t.offsetX) / t.scale).coerceIn(0f, f.bitmap.width.toFloat())
        val by = ((event.y - t.offsetY) / t.scale).coerceIn(0f, f.bitmap.height.toFloat())
        setHandlePoint(f, selected, PointF(bx, by))
        invalidate()
        onPointChanged?.invoke()
        return true
    }

    fun nudge(dx: Float, dy: Float) {
        val f = frame ?: return
        val p = getHandlePoint(f, selected) ?: return
        setHandlePoint(
            f,
            selected,
            PointF(
                (p.x + dx).coerceIn(0f, f.bitmap.width.toFloat()),
                (p.y + dy).coerceIn(0f, f.bitmap.height.toFloat())
            )
        )
        invalidate()
        onPointChanged?.invoke()
    }

    private fun drawPoint(canvas: Canvas, p: PointF, label: String, handle: HandleType) {
        val selectedNow = selected == handle
        pointPaint.color = ContextCompat.getColor(
            context,
            if (selectedNow) R.color.optiker_green else R.color.optiker_purple
        )
        canvas.drawCircle(p.x, p.y, if (selectedNow) 13f else 9f, pointPaint)
        textPaint.color = ContextCompat.getColor(context, R.color.white)
        canvas.drawText(label, p.x + 14f, p.y - 10f, textPaint)
    }

    private data class Transform(val scale: Float, val offsetX: Float, val offsetY: Float)

    private fun transform(f: FrameMeasurement): Transform {
        val scale = min(width.toFloat() / f.bitmap.width, height.toFloat() / f.bitmap.height)
        val drawnW = f.bitmap.width * scale
        val drawnH = f.bitmap.height * scale
        return Transform(
            scale = scale,
            offsetX = (width - drawnW) / 2f,
            offsetY = (height - drawnH) / 2f
        )
    }

    private fun cardHandle(i: Int) = when (i) {
        0 -> HandleType.CARD_1
        1 -> HandleType.CARD_2
        2 -> HandleType.CARD_3
        else -> HandleType.CARD_4
    }

    private fun getHandlePoint(f: FrameMeasurement, h: HandleType): PointF? = when (h) {
        HandleType.OD -> f.od
        HandleType.OE -> f.oe
        HandleType.CENTER -> f.center
        HandleType.CARD_1 -> f.cardCorners.getOrNull(0)
        HandleType.CARD_2 -> f.cardCorners.getOrNull(1)
        HandleType.CARD_3 -> f.cardCorners.getOrNull(2)
        HandleType.CARD_4 -> f.cardCorners.getOrNull(3)
        HandleType.BOTTOM_OD -> f.bottomOD
        HandleType.BOTTOM_OE -> f.bottomOE
    }

    private fun setHandlePoint(f: FrameMeasurement, h: HandleType, p: PointF) {
        when (h) {
            HandleType.OD -> f.od = p
            HandleType.OE -> f.oe = p
            HandleType.CENTER -> f.center = p
            HandleType.CARD_1 -> if (f.cardCorners.size > 0) f.cardCorners[0] = p
            HandleType.CARD_2 -> if (f.cardCorners.size > 1) f.cardCorners[1] = p
            HandleType.CARD_3 -> if (f.cardCorners.size > 2) f.cardCorners[2] = p
            HandleType.CARD_4 -> if (f.cardCorners.size > 3) f.cardCorners[3] = p
            HandleType.BOTTOM_OD -> f.bottomOD = p
            HandleType.BOTTOM_OE -> f.bottomOE = p
        }
    }
}
