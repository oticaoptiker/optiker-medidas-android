package com.optiker.medidas.v5

import android.graphics.RectF
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.common.PointF3D
import com.google.mlkit.vision.facemesh.FaceMesh
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetector
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
import kotlin.math.roundToInt

class DenseFaceMeshEngine {

    private val detector: FaceMeshDetector =
        FaceMeshDetection.getClient(FaceMeshDetectorOptions.DEFAULT_OPTIONS)

    fun process(image: InputImage): Task<List<FaceMesh>> = detector.process(image)

    fun buildDenseResult(
        mesh: FaceMesh,
        sourceWidth: Int,
        sourceHeight: Int
    ): DenseFaceMeshResult {
        val core = mesh.allPoints.map { it.position.toPoint3() }
        val dense = densifyByTriangleSubdivision(mesh)

        return DenseFaceMeshResult(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            bounds = RectF(mesh.boundingBox),
            corePoints = core,
            densePoints = dense,
            noseBridge = mesh.getPoints(FaceMesh.NOSE_BRIDGE)
                .map { it.position.toPoint3() },
            leftEye = mesh.getPoints(FaceMesh.LEFT_EYE)
                .map { it.position.toPoint3() },
            rightEye = mesh.getPoints(FaceMesh.RIGHT_EYE)
                .map { it.position.toPoint3() },
            faceOval = mesh.getPoints(FaceMesh.FACE_OVAL)
                .map { it.position.toPoint3() }
        )
    }

    /**
     * Densificação geométrica da malha original.
     * Os 468 landmarks seguem sendo a fonte de verdade.
     * Os pontos extras NÃO são novos landmarks detectados pela IA.
     */
    private fun densifyByTriangleSubdivision(mesh: FaceMesh): List<Point3> {
        val subdivisions = 4
        val unique = LinkedHashMap<String, Point3>(8192)

        fun add(p: Point3) {
            val key =
                "${(p.x * 1000f).roundToInt()}:" +
                    "${(p.y * 1000f).roundToInt()}:" +
                    "${(p.z * 1000f).roundToInt()}"
            unique.putIfAbsent(key, p)
        }

        for (triangle in mesh.allTriangles) {
            val pts = triangle.allPoints
            if (pts.size != 3) continue

            val a = pts[0].position
            val b = pts[1].position
            val c = pts[2].position

            for (i in 0..subdivisions) {
                for (j in 0..(subdivisions - i)) {
                    val k = subdivisions - i - j
                    val wa = i.toFloat() / subdivisions
                    val wb = j.toFloat() / subdivisions
                    val wc = k.toFloat() / subdivisions

                    add(
                        Point3(
                            x = a.x * wa + b.x * wb + c.x * wc,
                            y = a.y * wa + b.y * wb + c.y * wc,
                            z = a.z * wa + b.z * wb + c.z * wc
                        )
                    )
                }
            }
        }

        return unique.values.toList()
    }

    fun close() = detector.close()

    private fun PointF3D.toPoint3(): Point3 = Point3(x, y, z)
}
