package com.optiker.medidas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
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
    var onCardCornerProgress: ((Int) -> Unit)? = null

    private var cardMarkingMode = false
    private var magnifierPoint: PointF? = null
    private var magnifierVisible = false

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

    private val magnifierBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val magnifierImagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }

    fun startCardMarking() {
        val f = frame ?: return

        f.cardCorners.clear()
        cardMarkingMode = true
        magnifierVisible = false
        selected = HandleType.CARD_1

        onCardCornerProgress?.invoke(0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val f = frame ?: return
        val t = transform(f)

        fun map(p: PointF): PointF =
            PointF(
                t.offsetX + p.x * t.scale,
                t.offsetY + p.y * t.scale
            )

        if (f.cardCorners.isNotEmpty()) {
            val pts = f.cardCorners.map(::map)

            linePaint.color =
                ContextCompat.getColor(
                    context,
                    R.color.optiker_lavender
                )

            for (i in 0 until pts.size - 1) {
                canvas.drawLine(
                    pts[i].x,
                    pts[i].y,
                    pts[i + 1].x,
                    pts[i + 1].y,
                    linePaint
                )
            }

            if (pts.size == 4) {
                canvas.drawLine(
                    pts[3].x,
                    pts[3].y,
                    pts[0].x,
                    pts[0].y,
                    linePaint
                )
            }

            pts.forEachIndexed { i, p ->
                drawPoint(
                    canvas,
                    p,
                    "C${i + 1}",
                    cardHandle(i)
                )
            }
        }

        drawPoint(
            canvas,
            map(f.od),
            "OD",
            HandleType.OD
        )

        drawPoint(
            canvas,
            map(f.oe),
            "OE",
            HandleType.OE
        )

        drawPoint(
            canvas,
            map(f.center),
            "CENTRO",
            HandleType.CENTER
        )

        f.bottomOD?.let {
            drawPoint(
                canvas,
                map(it),
                "BORDA OD",
                HandleType.BOTTOM_OD
            )
        }

        f.bottomOE?.let {
            drawPoint(
                canvas,
                map(it),
                "BORDA OE",
                HandleType.BOTTOM_OE
            )
        }

        if (magnifierVisible) {
            magnifierPoint?.let {
                drawMagnifier(
                    canvas,
                    f,
                    it
                )
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val f = frame ?: return true
        val t = transform(f)

        fun pointFromEvent(): PointF {
            val bx =
                ((event.x - t.offsetX) / t.scale)
                    .coerceIn(
                        0f,
                        f.bitmap.width.toFloat()
                    )

            val by =
                ((event.y - t.offsetY) / t.scale)
                    .coerceIn(
                        0f,
                        f.bitmap.height.toFloat()
                    )

            return PointF(bx, by)
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                magnifierPoint = pointFromEvent()
                magnifierVisible = true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                magnifierVisible = false
            }
        }

        if (cardMarkingMode) {
            if (event.action == MotionEvent.ACTION_DOWN ||
                event.action == MotionEvent.ACTION_MOVE
            ) {
                invalidate()
                return true
            }

            if (event.action == MotionEvent.ACTION_UP) {
                if (f.cardCorners.size < 4) {
                    f.cardCorners.add(pointFromEvent())
                }

                val count = f.cardCorners.size

                if (count < 4) {
                    selected = cardHandle(count)
                } else {
                    cardMarkingMode = false
                    selected = HandleType.CARD_4
                }

                invalidate()
                onCardCornerProgress?.invoke(count)
                onPointChanged?.invoke()
                return true
            }
        }

        if (event.action != MotionEvent.ACTION_DOWN &&
            event.action != MotionEvent.ACTION_MOVE &&
            event.action != MotionEvent.ACTION_UP
        ) {
            return true
        }

        if (event.action != MotionEvent.ACTION_UP) {
            setHandlePoint(
                f,
                selected,
                pointFromEvent()
            )
            invalidate()
            onPointChanged?.invoke()
        }

        return true
    }

    fun nudge(dx: Float, dy: Float) {
        val f = frame ?: return
        val p = getHandlePoint(
            f,
            selected
        ) ?: return

        setHandlePoint(
            f,
            selected,
            PointF(
                (p.x + dx).coerceIn(
                    0f,
                    f.bitmap.width.toFloat()
                ),
                (p.y + dy).coerceIn(
                    0f,
                    f.bitmap.height.toFloat()
                )
            )
        )

        invalidate()
        onPointChanged?.invoke()
    }

    private fun drawPoint(
        canvas: Canvas,
        p: PointF,
        label: String,
        handle: HandleType
    ) {
        val selectedNow = selected == handle

        pointPaint.color =
            ContextCompat.getColor(
                context,
                if (selectedNow) {
                    R.color.optiker_green
                } else {
                    R.color.optiker_purple
                }
            )

        canvas.drawCircle(
            p.x,
            p.y,
            if (selectedNow) 13f else 9f,
            pointPaint
        )

        textPaint.color =
            ContextCompat.getColor(
                context,
                R.color.white
            )

        canvas.drawText(
            label,
            p.x + 14f,
            p.y - 10f,
            textPaint
        )
    }

    private fun drawMagnifier(
        canvas: Canvas,
        f: FrameMeasurement,
        imagePoint: PointF
    ) {
        val radius = min(width, height) * 0.13f
        if (radius < 45f) return

        val centerX =
            if (imagePoint.x < f.bitmap.width / 2f) {
                width - radius - 24f
            } else {
                radius + 24f
            }

        val centerY = radius + 36f

        val sampleRadius = 36

        val sx =
            imagePoint.x.toInt().coerceIn(
                sampleRadius,
                f.bitmap.width - sampleRadius
            )

        val sy =
            imagePoint.y.toInt().coerceIn(
                sampleRadius,
                f.bitmap.height - sampleRadius
            )

        val src = Rect(
            sx - sampleRadius,
            sy - sampleRadius,
            sx + sampleRadius,
            sy + sampleRadius
        )

        val dst = RectF(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius
        )

        val save = canvas.save()

        val clip = Path().apply {
            addCircle(
                centerX,
                centerY,
                radius,
                Path.Direction.CW
            )
        }

        canvas.clipPath(clip)

        canvas.drawBitmap(
            f.bitmap,
            src,
            dst,
            magnifierImagePaint
        )

        linePaint.color =
            ContextCompat.getColor(
                context,
                R.color.optiker_green
            )
        linePaint.strokeWidth = 3f

        canvas.drawLine(
            centerX - 18f,
            centerY,
            centerX + 18f,
            centerY,
            linePaint
        )

        canvas.drawLine(
            centerX,
            centerY - 18f,
            centerX,
            centerY + 18f,
            linePaint
        )

        canvas.restoreToCount(save)

        magnifierBorder.color =
            ContextCompat.getColor(
                context,
                R.color.white
            )

        canvas.drawCircle(
            centerX,
            centerY,
            radius,
            magnifierBorder
        )
    }

    private data class Transform(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float
    )

    private fun transform(
        f: FrameMeasurement
    ): Transform {
        val scale =
            min(
                width.toFloat() /
                    f.bitmap.width,
                height.toFloat() /
                    f.bitmap.height
            )

        val drawnW =
            f.bitmap.width * scale

        val drawnH =
            f.bitmap.height * scale

        return Transform(
            scale = scale,
            offsetX = (width - drawnW) / 2f,
            offsetY = (height - drawnH) / 2f
        )
    }

    private fun cardHandle(i: Int) =
        when (i) {
            0 -> HandleType.CARD_1
            1 -> HandleType.CARD_2
            2 -> HandleType.CARD_3
            else -> HandleType.CARD_4
        }

    private fun getHandlePoint(
        f: FrameMeasurement,
        h: HandleType
    ): PointF? =
        when (h) {
            HandleType.OD -> f.od
            HandleType.OE -> f.oe
            HandleType.CENTER -> f.center
            HandleType.CARD_1 ->
                f.cardCorners.getOrNull(0)
            HandleType.CARD_2 ->
                f.cardCorners.getOrNull(1)
            HandleType.CARD_3 ->
                f.cardCorners.getOrNull(2)
            HandleType.CARD_4 ->
                f.cardCorners.getOrNull(3)
            HandleType.BOTTOM_OD ->
                f.bottomOD
            HandleType.BOTTOM_OE ->
                f.bottomOE
        }

    private fun setHandlePoint(
        f: FrameMeasurement,
        h: HandleType,
        p: PointF
    ) {
        when (h) {
            HandleType.OD ->
                f.od = p

            HandleType.OE ->
                f.oe = p

            HandleType.CENTER ->
                f.center = p

            HandleType.CARD_1 ->
                if (f.cardCorners.size > 0) {
                    f.cardCorners[0] = p
                }

            HandleType.CARD_2 ->
                if (f.cardCorners.size > 1) {
                    f.cardCorners[1] = p
                }

            HandleType.CARD_3 ->
                if (f.cardCorners.size > 2) {
                    f.cardCorners[2] = p
                }

            HandleType.CARD_4 ->
                if (f.cardCorners.size > 3) {
                    f.cardCorners[3] = p
                }

            HandleType.BOTTOM_OD ->
                f.bottomOD = p

            HandleType.BOTTOM_OE ->
                f.bottomOE = p
        }
    }
}
