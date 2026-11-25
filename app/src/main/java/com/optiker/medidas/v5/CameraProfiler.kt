package com.optiker.medidas.v5

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build

object CameraProfiler {

    fun inspectBestFrontCamera(context: Context): CameraCalibrationProfile? {
        val manager = context.getSystemService(CameraManager::class.java)

        val candidates = manager.cameraIdList.mapNotNull { cameraId ->
            val c = manager.getCameraCharacteristics(cameraId)

            if (c.get(CameraCharacteristics.LENS_FACING) !=
                CameraCharacteristics.LENS_FACING_FRONT
            ) return@mapNotNull null

            val sensorSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val pixelSize = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

            val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                c.physicalCameraIds.toList()
            } else {
                emptyList()
            }

            val distortion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                c.get(CameraCharacteristics.LENS_DISTORTION)?.toList()
            } else {
                null
            }

            CameraCalibrationProfile(
                manufacturer = Build.MANUFACTURER ?: "—",
                model = Build.MODEL ?: "—",
                deviceCode = Build.DEVICE ?: "—",
                cameraId = cameraId,
                physicalCameraIds = physicalIds,
                focalLengthsMm =
                    c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        ?.toList() ?: emptyList(),
                sensorWidthMm = sensorSize?.width,
                sensorHeightMm = sensorSize?.height,
                pixelWidth = pixelSize?.width,
                pixelHeight = pixelSize?.height,
                activeArrayWidth = activeArray?.width(),
                activeArrayHeight = activeArray?.height(),
                sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION),
                hardwareLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
                intrinsics =
                    c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.toList(),
                distortion = distortion
            )
        }

        return candidates.sortedWith(
            compareByDescending<CameraCalibrationProfile> { it.hasIntrinsics }
                .thenByDescending { it.hasDistortionModel }
                .thenByDescending { it.pixelWidth ?: 0 }
        ).firstOrNull()
    }

    fun describe(profile: CameraCalibrationProfile?): String {
        if (profile == null) return "Câmera frontal: não identificada"

        val intrinsicsText = profile.intrinsics?.let {
            if (it.size >= 5) {
                "fx=${fmt(it[0])}  fy=${fmt(it[1])}\n" +
                    "cx=${fmt(it[2])}  cy=${fmt(it[3])}  s=${fmt(it[4])}"
            } else "não fornecidos"
        } ?: "não fornecidos"

        val distortionText = profile.distortion?.joinToString(
            prefix = "[",
            postfix = "]"
        ) { fmt(it) } ?: "não fornecida"

        val focalText = if (profile.focalLengthsMm.isEmpty()) {
            "—"
        } else {
            profile.focalLengthsMm.joinToString { "${fmt(it)} mm" }
        }

        return buildString {
            append("${profile.manufacturer} ${profile.model}\n")
            append("Código: ${profile.deviceCode} • câmera frontal ID ${profile.cameraId}\n")
            if (profile.physicalCameraIds.isNotEmpty()) {
                append("Câmeras físicas: ${profile.physicalCameraIds.joinToString()}\n")
            }
            append("Focal: $focalText\n")
            append(
                "Sensor: ${profile.sensorWidthMm?.let(::fmt) ?: "—"} × " +
                    "${profile.sensorHeightMm?.let(::fmt) ?: "—"} mm\n"
            )
            append("Pixels: ${profile.pixelWidth ?: 0} × ${profile.pixelHeight ?: 0}\n")
            append("Intrínsecos: $intrinsicsText\n")
            append("Distorção: $distortionText")
        }
    }

    private fun fmt(value: Float): String =
        String.format(java.util.Locale.US, "%.3f", value)
}
