package com.optiker.medidas.v5

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Bundle
import android.util.Size
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.optiker.medidas.MainActivity
import com.optiker.medidas.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class V5MeasurementActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: V5FaceOverlayView
    private lateinit var stateText: TextView
    private lateinit var cameraText: TextView
    private lateinit var arcoreText: TextView
    private lateinit var meshText: TextView

    private lateinit var cameraExecutor: ExecutorService
    private val analyzerBusy = AtomicBoolean(false)
    private var lastAnalysisAt = 0L

    private var cameraProfile: CameraCalibrationProfile? = null
    private var arcoreSupported: Boolean? = null

    private val meshEngine = DenseFaceMeshEngine()

    private val poseDetector: FaceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.16f)
                .enableTracking()
                .build()
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            readCameraProfile()
            startCamera()
        } else {
            stateText.text = "Permita o uso da câmera para iniciar a V5."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_v5_measurement)
        cameraExecutor = Executors.newSingleThreadExecutor()

        previewView = findViewById(R.id.v5PreviewView)
        overlay = findViewById(R.id.v5FaceOverlay)
        stateText = findViewById(R.id.v5StateText)
        cameraText = findViewById(R.id.v5CameraText)
        arcoreText = findViewById(R.id.v5ArcoreText)
        meshText = findViewById(R.id.v5MeshText)

        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        findViewById<Button>(R.id.v5FallbackButton).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        checkArCore()

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            readCameraProfile()
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun readCameraProfile() {
        cameraProfile = try {
            CameraProfiler.inspectBestFrontCamera(this)
        } catch (_: Exception) {
            null
        }
        cameraText.text = CameraProfiler.describe(cameraProfile)
    }

    private fun checkArCore() {
        arcoreText.text = "ARCore: verificando compatibilidade…"

        val availability = ArCoreApk.getInstance().checkAvailability(this)
        arcoreSupported = availability.isSupported

        arcoreText.text = if (availability.isSupported) {
            "ARCore: compatível ✓ • $availability"
        } else {
            "ARCore: indisponível/indeterminado • $availability"
        }
    }

    @Suppress("DEPRECATION")
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            provider.unbindAll()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(720, 1280))
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setOutputImageRotationEnabled(true)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val now = System.currentTimeMillis()
                if (now - lastAnalysisAt < 220 ||
                    !analyzerBusy.compareAndSet(false, true)
                ) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                lastAnalysisAt = now

                val bitmap = try {
                    imageProxy.toBitmap()
                } catch (_: Exception) {
                    analyzerBusy.set(false)
                    imageProxy.close()
                    return@setAnalyzer
                }

                analyzeBitmap(bitmap) {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    analyzerBusy.set(false)
                    imageProxy.close()
                }
            }

            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeBitmap(bitmap: Bitmap, cleanup: () -> Unit) {
        val input = InputImage.fromBitmap(bitmap, 0)

        poseDetector.process(input)
            .addOnSuccessListener(cameraExecutor) { faces ->
                val face = faces.singleOrNull()
                if (face == null) {
                    runOnUiThread {
                        overlay.result = null
                        overlay.poseValid = false
                        stateText.text = if (faces.isEmpty()) {
                            "Centralize um rosto na câmera."
                        } else {
                            "Mantenha apenas uma pessoa na câmera."
                        }
                        meshText.text = "Malha IA: aguardando rosto"
                    }
                    cleanup()
                    return@addOnSuccessListener
                }

                meshEngine.process(input)
                    .addOnSuccessListener(cameraExecutor) { meshes ->
                        val mesh = meshes.singleOrNull()
                        if (mesh == null) {
                            runOnUiThread {
                                overlay.result = null
                                overlay.poseValid = false
                                stateText.text =
                                    "Rosto detectado, mas a malha 3D ainda não estabilizou."
                                meshText.text = "Malha IA: aguardando 468 landmarks"
                            }
                            return@addOnSuccessListener
                        }

                        val dense = meshEngine.buildDenseResult(
                            mesh = mesh,
                            sourceWidth = bitmap.width,
                            sourceHeight = bitmap.height
                        )
                        val pose = evaluatePose(face, bitmap.width, bitmap.height)

                        runOnUiThread {
                            overlay.result = dense
                            overlay.poseValid = pose.valid

                            meshText.text =
                                "Malha IA: ${dense.coreCount} landmarks reais • " +
                                    "${dense.denseCount} pontos geométricos derivados\n" +
                                    "Nariz: ${dense.noseBridge.size} • " +
                                    "Olho E: ${dense.leftEye.size} • " +
                                    "Olho D: ${dense.rightEye.size}"

                            stateText.text = when {
                                !pose.centered -> "Centralize o rosto."
                                !pose.valid ->
                                    "Olhe reto: não incline, não vire e mantenha o queixo nivelado."
                                cameraProfile?.hasIntrinsics != true ->
                                    "Rosto OK ✓ • câmera sem intrínsecos completos. " +
                                        "Vamos usar calibração alternativa na etapa métrica."
                                arcoreSupported == false ->
                                    "Rosto OK ✓ • ARCore não disponível. Modo alternativo será necessário."
                                dense.denseCount < 5000 ->
                                    "Rosto OK ✓ • malha estável, mas a densificação ficou abaixo de 5.000 pontos."
                                else ->
                                    "BASE V5 VALIDADA ✓\n" +
                                        "Rosto frontal + malha densa + câmera identificada."
                            }
                        }
                    }
                    .addOnFailureListener(cameraExecutor) {
                        runOnUiThread {
                            stateText.text = "Falha temporária ao gerar a malha facial."
                        }
                    }
                    .addOnCompleteListener(cameraExecutor) { cleanup() }
            }
            .addOnFailureListener(cameraExecutor) {
                runOnUiThread {
                    stateText.text = "Falha temporária ao analisar a posição do rosto."
                }
                cleanup()
            }
    }

    private data class PoseState(
        val centered: Boolean,
        val valid: Boolean
    )

    private fun evaluatePose(face: Face, width: Int, height: Int): PoseState {
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position

        if (rightEye == null || leftEye == null) {
            return PoseState(false, false)
        }

        val mid = PointF(
            (rightEye.x + leftEye.x) / 2f,
            (rightEye.y + leftEye.y) / 2f
        )

        val centered =
            abs(mid.x - width / 2f) <= width * 0.14f &&
                mid.y in (height * 0.29f)..(height * 0.61f)

        val straight =
            abs(face.headEulerAngleZ) <= 3.0f &&
                abs(face.headEulerAngleY) <= 6.0f &&
                abs(face.headEulerAngleX) <= 6.0f

        return PoseState(centered, centered && straight)
    }

    override fun onDestroy() {
        super.onDestroy()
        poseDetector.close()
        meshEngine.close()
        cameraExecutor.shutdown()
    }
}
