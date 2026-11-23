package com.optiker.medidas

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF

enum class WorkflowMode { DNP, HEIGHT }

enum class HandleType {
    OD, OE, CENTER,
    CARD_1, CARD_2, CARD_3, CARD_4,
    BOTTOM_OD, BOTTOM_OE
}

data class CardDetection(
    val corners: MutableList<PointF>,
    val quality: Int,
    val widthPx: Float,
    val heightPx: Float,
    val valid: Boolean
)

data class FaceGeometry(
    val od: PointF,
    val oe: PointF,
    val center: PointF,
    val bounds: RectF,
    val roll: Float,
    val yaw: Float,
    val pitch: Float
)

data class LiveFrameResult(
    val bitmap: Bitmap,
    val face: FaceGeometry,
    val card: CardDetection,
    val faceCentered: Boolean,
    val poseValid: Boolean,
    val distanceValid: Boolean,
    val allValid: Boolean,
    val distanceHint: String
)

data class FrameMeasurement(
    val bitmap: Bitmap,
    var od: PointF,
    var oe: PointF,
    var center: PointF,
    val cardCorners: MutableList<PointF>,
    var bottomOD: PointF? = null,
    var bottomOE: PointF? = null
)

data class DnpResult(
    val od: Double,
    val oe: Double,
    val pd: Double,
    val cardQuality: Int
)

data class HeightResult(
    val od: Double,
    val oe: Double
)

data class DnpCapture(val od: Double, val oe: Double, val pd: Double)
data class HeightCapture(val od: Double, val oe: Double)
