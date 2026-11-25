package com.optiker.medidas.v5

import android.graphics.RectF

data class Point3(
    val x: Float,
    val y: Float,
    val z: Float
)

data class DenseFaceMeshResult(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val bounds: RectF,
    val corePoints: List<Point3>,
    val densePoints: List<Point3>,
    val noseBridge: List<Point3>,
    val leftEye: List<Point3>,
    val rightEye: List<Point3>,
    val faceOval: List<Point3>
) {
    val coreCount: Int get() = corePoints.size
    val denseCount: Int get() = densePoints.size
}

data class CameraCalibrationProfile(
    val manufacturer: String,
    val model: String,
    val deviceCode: String,
    val cameraId: String,
    val physicalCameraIds: List<String>,
    val focalLengthsMm: List<Float>,
    val sensorWidthMm: Float?,
    val sensorHeightMm: Float?,
    val pixelWidth: Int?,
    val pixelHeight: Int?,
    val activeArrayWidth: Int?,
    val activeArrayHeight: Int?,
    val sensorOrientation: Int?,
    val hardwareLevel: Int?,
    val intrinsics: List<Float>?,
    val distortion: List<Float>?
) {
    val hasIntrinsics: Boolean
        get() = intrinsics != null && intrinsics.size >= 5

    val hasDistortionModel: Boolean
        get() = !distortion.isNullOrEmpty()
}
