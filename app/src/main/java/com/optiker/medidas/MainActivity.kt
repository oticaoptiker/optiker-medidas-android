package com.optiker.medidas

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var liveOverlay: MeasurementOverlayView
    private lateinit var cameraScreen: View
    private lateinit var reviewScreen: View
    private lateinit var summaryScreen: View

    private lateinit var stepText: TextView
    private lateinit var instructionText: TextView
    private lateinit var cardStatus: TextView
    private lateinit var faceStatus: TextView
    private lateinit var poseStatus: TextView
    private lateinit var distanceStatus: TextView
    private lateinit var stabilityText: TextView
    private lateinit var captureButton: Button

    private lateinit var reviewImage: ImageView
    private lateinit var reviewOverlay: ReviewOverlayView
    private lateinit var adjustButtons: LinearLayout
    private lateinit var reviewResult: TextView
    private lateinit var confirmButton: Button

    private lateinit var summaryTitle: TextView
    private lateinit var summaryValues: TextView
    private lateinit var repeatabilityText: TextView
    private lateinit var dnpOnlyButton: Button
    private lateinit var multifocalButton: Button
    private lateinit var retrySeriesButton: Button
    private lateinit var multifocalNotice: TextView
    private lateinit var startHeightButton: Button
    private lateinit var shareButton: Button
    private lateinit var newMeasurementButton: Button

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

    private val analyzerBusy = AtomicBoolean(false)
    private var lastAnalysisAt = 0L
    private var stableFrames = 0
    private var faceStableFrames = 0
    private var lostFaceFrames = 0
    private var capturePhase = CapturePhase.FACE
    private var autoCaptureArmed = false
    private var latestValidFrame: LiveFrameResult? = null

    private var workflowMode = WorkflowMode.DNP
    private var currentReview: FrameMeasurement? = null

    private val dnpCaptures = mutableListOf<DnpCapture>()
    private val heightCaptures = mutableListOf<HeightCapture>()

    private var finalDnp: DnpCapture? = null
    private var finalHeight: HeightCapture? = null

    private val faceDetector: FaceDetector by lazy {
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.16f)
            .enableTracking()
            .build()
        FaceDetection.getClient(opts)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            instructionText.text = "Permita o uso da câmera nas configurações do Android para realizar a medição."
            captureButton.isEnabled = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraExecutor = Executors.newSingleThreadExecutor()
        bindViews()
        bindActions()
        configurePreview()
        resetAll()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        liveOverlay = findViewById(R.id.liveOverlay)
        cameraScreen = findViewById(R.id.cameraScreen)
        reviewScreen = findViewById(R.id.reviewScreen)
        summaryScreen = findViewById(R.id.summaryScreen)

        stepText = findViewById(R.id.stepText)
        instructionText = findViewById(R.id.instructionText)
        cardStatus = findViewById(R.id.cardStatus)
        faceStatus = findViewById(R.id.faceStatus)
        poseStatus = findViewById(R.id.poseStatus)
        distanceStatus = findViewById(R.id.distanceStatus)
        stabilityText = findViewById(R.id.stabilityText)
        captureButton = findViewById(R.id.captureButton)

        reviewImage = findViewById(R.id.reviewImage)
        reviewOverlay = findViewById(R.id.reviewOverlay)
        adjustButtons = findViewById(R.id.adjustButtons)
        reviewResult = findViewById(R.id.reviewResult)
        confirmButton = findViewById(R.id.confirmButton)

        summaryTitle = findViewById(R.id.summaryTitle)
        summaryValues = findViewById(R.id.summaryValues)
        repeatabilityText = findViewById(R.id.repeatabilityText)
        dnpOnlyButton = findViewById(R.id.dnpOnlyButton)
        multifocalButton = findViewById(R.id.multifocalButton)
        retrySeriesButton = findViewById(R.id.retrySeriesButton)
        multifocalNotice = findViewById(R.id.multifocalNotice)
        startHeightButton = findViewById(R.id.startHeightButton)
        shareButton = findViewById(R.id.shareButton)
        newMeasurementButton = findViewById(R.id.newMeasurementButton)
    }

    private fun configurePreview() {
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

    private fun bindActions() {
        captureButton.setOnClickListener { captureLatestStableFrame() }
        findViewById<Button>(R.id.retakeButton).setOnClickListener { retakeCurrent() }
        confirmButton.setOnClickListener { confirmCurrentCapture() }

        reviewOverlay.onPointChanged = {
            recomputeReview()
        }

        findViewById<Button>(R.id.nudgeLeft).setOnClickListener { reviewOverlay.nudge(-1f, 0f) }
        findViewById<Button>(R.id.nudgeRight).setOnClickListener { reviewOverlay.nudge(1f, 0f) }
        findViewById<Button>(R.id.nudgeUp).setOnClickListener { reviewOverlay.nudge(0f, -1f) }
        findViewById<Button>(R.id.nudgeDown).setOnClickListener { reviewOverlay.nudge(0f, 1f) }

        dnpOnlyButton.setOnClickListener {
            showFinalResult(false)
        }

        multifocalButton.setOnClickListener {
            multifocalNotice.visibility = View.VISIBLE
            startHeightButton.visibility = View.VISIBLE
            instructionText.text = ""
        }

        startHeightButton.setOnClickListener {
            workflowMode = WorkflowMode.HEIGHT
            heightCaptures.clear()
            showCamera()
        }

        retrySeriesButton.setOnClickListener {
            if (workflowMode == WorkflowMode.HEIGHT) heightCaptures.clear() else dnpCaptures.clear()
            showCamera()
        }

        shareButton.setOnClickListener { shareResult() }
        newMeasurementButton.setOnClickListener {
            resetAll()
            startCamera()
        }
    }

    @Suppress("DEPRECATION")
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            val provider = cameraProvider ?: return@addListener
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

            imageAnalysis = analysis
            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val now = System.currentTimeMillis()
                if (now - lastAnalysisAt < 180 || !analyzerBusy.compareAndSet(false, true)) {
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

                val input = InputImage.fromBitmap(bitmap, 0)

                faceDetector.process(input)
                    .addOnSuccessListener { faces ->
                        analyzeFrame(bitmap, faces)
                    }
                    .addOnFailureListener {
                        bitmap.recycle()
                        runOnUiThread {
                            stableFrames = 0
                            faceStableFrames = 0
                            capturePhase = CapturePhase.FACE
                            updateLiveUi(
                                null,
                                CardDetector.empty(),
                                false,
                                false,
                                false,
                                "Rosto não detectado"
                            )
                        }
                    }
                    .addOnCompleteListener {
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

            autoCaptureArmed = true
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(bitmap: Bitmap, faces: List<Face>) {
        if (faces.size != 1) {
            bitmap.recycle()
            runOnUiThread {
                stableFrames = 0
                faceStableFrames = 0
                capturePhase = CapturePhase.FACE
                updateLiveUi(
                    null,
                    CardDetector.empty(),
                    false,
                    false,
                    false,
                    if (faces.isEmpty()) "Centralize o rosto" else "Apenas uma pessoa na câmera"
                )
            }
            return
        }

        val face = faces.first()
        val od = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val oe = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position

        if (od == null || oe == null) {
            bitmap.recycle()
            runOnUiThread {
                stableFrames = 0
                faceStableFrames = 0
                capturePhase = CapturePhase.FACE
                updateLiveUi(
                    null,
                    CardDetector.empty(),
                    false,
                    false,
                    false,
                    "Aproxime um pouco o rosto"
                )
            }
            return
        }

        val midEye = PointF((od.x + oe.x) / 2f, (od.y + oe.y) / 2f)
        val center = nose ?: midEye

        val bounds = RectF(face.boundingBox)
        val geometry = FaceGeometry(
            od = PointF(od.x, od.y),
            oe = PointF(oe.x, oe.y),
            center = PointF(center.x, center.y),
            bounds = bounds,
            roll = face.headEulerAngleZ,
            yaw = face.headEulerAngleY,
            pitch = face.headEulerAngleX
        )

        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val faceRatio = bounds.width() / w

        // Mais natural que a versão anterior: ainda rejeita rotações relevantes,
        // mas não exige uma posição "matematicamente perfeita".
        val centerOk =
            abs(midEye.x - w / 2f) <= w * 0.14f &&
                midEye.y in (h * 0.31f)..(h * 0.59f)

        // A posição do rosto continua sendo uma trava de precisão.
        // Roll = inclinação lateral; yaw = rosto virado para os lados;
        // pitch = queixo para cima/baixo.
        val poseOk =
            abs(geometry.roll) <= 3.0f &&
                abs(geometry.yaw) <= 6.0f &&
                abs(geometry.pitch) <= 6.0f

        // A distância não é mais uma trava rígida.
        // O cartão é a referência física de escala; portanto o que realmente
        // importa aqui é o rosto estar detectado, centralizado e com pose válida.
        val distanceOk = true

        val distanceHint = when {
            faceRatio < 0.16f -> "Se quiser, aproxime um pouco para facilitar a leitura."
            faceRatio > 0.82f -> "Se quiser, afaste um pouco para caber o cartão ao lado."
            else -> "Distância acompanhada automaticamente"
        }

        val faceReady = centerOk && poseOk

        // FASE 1 — primeiro deixamos o rosto pronto, sem exigir cartão.
        if (capturePhase == CapturePhase.FACE) {
            faceStableFrames = if (faceReady) {
                min(10, faceStableFrames + 1)
            } else {
                max(0, faceStableFrames - 2)
            }

            if (faceStableFrames >= 7) {
                capturePhase = CapturePhase.CARD
                stableFrames = 0
                lostFaceFrames = 0
            }

            bitmap.recycle()

            runOnUiThread {
                updateLiveUi(
                    geometry,
                    CardDetector.empty(),
                    centerOk,
                    poseOk,
                    distanceOk,
                    distanceHint
                )
            }
            return
        }

        // FASE 2 — rosto já aprovado. Agora procuramos o cartão nas laterais.
        if (!faceReady) {
            lostFaceFrames++
            stableFrames = max(0, stableFrames - 2)

            if (lostFaceFrames >= 5) {
                capturePhase = CapturePhase.FACE
                faceStableFrames = 0
                lostFaceFrames = 0
            }

            bitmap.recycle()

            runOnUiThread {
                updateLiveUi(
                    geometry,
                    CardDetector.empty(),
                    centerOk,
                    poseOk,
                    distanceOk,
                    distanceHint
                )
            }
            return
        }

        lostFaceFrames = 0
        val card = CardDetector.detect(bitmap, bounds)
        val allValid = card.valid && faceReady

        if (allValid) {
            stableFrames = min(12, stableFrames + 1)

            if (stableFrames >= 5) {
                latestValidFrame?.bitmap?.let { old ->
                    if (!old.isRecycled) old.recycle()
                }
                latestValidFrame = LiveFrameResult(
                    bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false),
                    face = geometry,
                    card = card.copy(
                        corners = card.corners.map { PointF(it.x, it.y) }.toMutableList()
                    ),
                    faceCentered = centerOk,
                    poseValid = poseOk,
                    distanceValid = distanceOk,
                    allValid = true,
                    distanceHint = distanceHint
                )
            }
        } else {
            stableFrames = max(0, stableFrames - 1)
        }

        bitmap.recycle()

        runOnUiThread {
            updateLiveUi(
                geometry,
                card,
                centerOk,
                poseOk,
                distanceOk,
                distanceHint
            )

            if (stableFrames >= 8 && autoCaptureArmed) {
                autoCaptureArmed = false
                captureLatestStableFrame()
            }
        }
    }

    private fun updateLiveUi(
        face: FaceGeometry?,
        card: CardDetection,
        centerOk: Boolean,
        poseOk: Boolean,
        distanceOk: Boolean,
        distanceHint: String
    ) {
        liveOverlay.phase = capturePhase
        liveOverlay.cardValid = card.valid
        liveOverlay.faceValid = centerOk
        liveOverlay.poseValid = poseOk
        liveOverlay.distanceValid = distanceOk
        liveOverlay.invalidate()

        if (capturePhase == CapturePhase.FACE) {
            setStatus(cardStatus, "Cartão", "Depois", false)
        } else {
            setStatus(
                cardStatus,
                "Cartão",
                if (card.valid) "Detectado" else "Mostre ao lado",
                card.valid
            )
        }

        setStatus(
            faceStatus,
            "Rosto",
            if (centerOk) "Centralizado" else "Ajuste",
            centerOk
        )
        setStatus(
            poseStatus,
            "Cabeça",
            if (poseOk) "Reta ✓" else "Endireite",
            poseOk
        )
        setStatus(
            distanceStatus,
            "Distância",
            "Automática",
            true
        )

        val progress = if (capturePhase == CapturePhase.FACE) {
            (faceStableFrames / 7f * 100).toInt()
        } else {
            (stableFrames / 8f * 100).toInt()
        }.coerceIn(0, 100)

        stabilityText.text =
            if (capturePhase == CapturePhase.FACE) {
                "Validação do rosto: $progress%"
            } else {
                "Validação final: $progress%"
            }

        captureButton.isEnabled =
            capturePhase == CapturePhase.CARD &&
                stableFrames >= 5 &&
                latestValidFrame != null

        instructionText.text = when {
            capturePhase == CapturePhase.FACE && face == null ->
                "Centralize o rosto no oval."

            capturePhase == CapturePhase.FACE && !centerOk ->
                "Centralize o rosto. Não precisa mostrar o cartão ainda."

            capturePhase == CapturePhase.FACE && !poseOk ->
                "Olhe diretamente para a câmera. Não incline, não vire o rosto e mantenha o queixo reto."

            capturePhase == CapturePhase.FACE ->
                "Rosto correto ✓ Fique assim por um instante."

            !centerOk || !poseOk ->
                "Rosto fora da posição ideal. Centralize e olhe diretamente para a câmera antes de continuar."

            !card.valid ->
                "Rosto OK ✓ Agora encoste o cartão ao lado da têmpora/bochecha. Pode ser horizontal ou vertical."

            stableFrames < 5 ->
                "Cartão detectado ✓ Mantenha rosto e cartão imóveis."

            stableFrames < 8 ->
                "Perfeito. Captura automática em instantes…"

            else ->
                "Tudo validado ✓"
        }
    }

    private fun setStatus(view: TextView, label: String, value: String, ok: Boolean) {
        view.text = "$label\n$value"
        view.setTextColor(
            ContextCompat.getColor(this, if (ok) R.color.ok else R.color.warn)
        )
    }

    private fun captureLatestStableFrame() {
        val live = latestValidFrame ?: return
        val bitmap = live.bitmap.copy(Bitmap.Config.ARGB_8888, true)

        currentReview = FrameMeasurement(
            bitmap = bitmap,
            od = PointF(live.face.od.x, live.face.od.y),
            oe = PointF(live.face.oe.x, live.face.oe.y),
            center = PointF(live.face.center.x, live.face.center.y),
            cardCorners = live.card.corners.map { PointF(it.x, it.y) }.toMutableList()
        )

        cameraProvider?.unbindAll()
        showReview()
    }

    private fun showReview() {
        val frame = currentReview ?: return
        cameraScreen.visibility = View.GONE
        summaryScreen.visibility = View.GONE
        reviewScreen.visibility = View.VISIBLE

        reviewImage.setImageBitmap(frame.bitmap)
        reviewOverlay.frame = frame
        reviewOverlay.selected = HandleType.OD

        buildAdjustmentButtons()
        recomputeReview()
    }

    private fun buildAdjustmentButtons() {
        adjustButtons.removeAllViews()

        val handles = mutableListOf(
            "Pupila OD" to HandleType.OD,
            "Pupila OE" to HandleType.OE,
            "Centro" to HandleType.CENTER,
            "Cartão C1" to HandleType.CARD_1,
            "Cartão C2" to HandleType.CARD_2,
            "Cartão C3" to HandleType.CARD_3,
            "Cartão C4" to HandleType.CARD_4
        )

        if (workflowMode == WorkflowMode.HEIGHT) {
            handles.add("Borda OD" to HandleType.BOTTOM_OD)
            handles.add("Borda OE" to HandleType.BOTTOM_OE)
        }

        handles.forEach { (label, handle) ->
            val b = MaterialButton(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener {
                    reviewOverlay.selected = handle
                    if (workflowMode == WorkflowMode.HEIGHT &&
                        (handle == HandleType.BOTTOM_OD || handle == HandleType.BOTTOM_OE)
                    ) {
                        reviewHelpText("Selecione $label e toque exatamente na borda interna inferior da lente.")
                    } else {
                        reviewHelpText("Selecione $label e toque no ponto correto; ou use as setas para ajuste fino.")
                    }
                }
            }
            adjustButtons.addView(b)
        }
    }

    private fun reviewHelpText(text: String) {
        findViewById<TextView>(R.id.reviewHelp).text = text
    }

    private fun recomputeReview() {
        val frame = currentReview ?: return
        val dnp = MeasurementMath.dnp(frame)

        if (workflowMode == WorkflowMode.DNP) {
            if (dnp == null) {
                reviewResult.text = "Não foi possível calcular. Revise os pontos do cartão."
                confirmButton.isEnabled = false
                return
            }

            val plausible = dnp.pd in 35.0..90.0 &&
                dnp.od in 15.0..50.0 &&
                dnp.oe in 15.0..50.0 &&
                dnp.cardQuality >= 70

            reviewResult.text =
                "DNP OD: ${fmt(dnp.od)} mm\n" +
                    "DNP OE: ${fmt(dnp.oe)} mm\n" +
                    "DP total: ${fmt(dnp.pd)} mm\n" +
                    "Qualidade do cartão: ${dnp.cardQuality}%\n" +
                    if (plausible) "Pronto para confirmar." else "Revise cartão e pontos antes de confirmar."

            confirmButton.isEnabled = plausible
        } else {
            val height = MeasurementMath.height(frame)
            if (dnp == null) {
                reviewResult.text = "Revise o cartão e as pupilas."
                confirmButton.isEnabled = false
                return
            }

            if (frame.bottomOD == null || frame.bottomOE == null || height == null) {
                reviewResult.text =
                    "DNP: ${fmt(dnp.od)} / ${fmt(dnp.oe)} mm\n" +
                        "Agora marque BORDA OD e BORDA OE na borda interna inferior das lentes."
                confirmButton.isEnabled = false
                return
            }

            val plausible = height.od in 5.0..45.0 &&
                height.oe in 5.0..45.0 &&
                dnp.cardQuality >= 70

            reviewResult.text =
                "Altura OD: ${fmt(height.od)} mm\n" +
                    "Altura OE: ${fmt(height.oe)} mm\n" +
                    "Qualidade do cartão: ${dnp.cardQuality}%\n" +
                    if (plausible) "Pronto para confirmar." else "Revise os pontos antes de confirmar."

            confirmButton.isEnabled = plausible
        }
    }

    private fun confirmCurrentCapture() {
        val frame = currentReview ?: return

        if (workflowMode == WorkflowMode.DNP) {
            val d = MeasurementMath.dnp(frame) ?: return
            dnpCaptures.add(DnpCapture(d.od, d.oe, d.pd))
            if (dnpCaptures.size < 3) {
                showCamera()
            } else {
                evaluateDnpSeries()
            }
        } else {
            val h = MeasurementMath.height(frame) ?: return
            heightCaptures.add(HeightCapture(h.od, h.oe))
            if (heightCaptures.size < 2) {
                showCamera()
            } else {
                evaluateHeightSeries()
            }
        }
    }

    private fun retakeCurrent() {
        currentReview?.bitmap?.let { if (!it.isRecycled) it.recycle() }
        currentReview = null
        showCamera()
    }

    private fun showCamera() {
        reviewScreen.visibility = View.GONE
        summaryScreen.visibility = View.GONE
        cameraScreen.visibility = View.VISIBLE

        stableFrames = 0
        faceStableFrames = 0
        lostFaceFrames = 0
        capturePhase = CapturePhase.FACE
        autoCaptureArmed = true
        latestValidFrame?.bitmap?.let { if (!it.isRecycled) it.recycle() }
        latestValidFrame = null

        stepText.text = if (workflowMode == WorkflowMode.DNP) {
            "DNP • captura ${dnpCaptures.size + 1} de 3"
        } else {
            "Altura multifocal • captura ${heightCaptures.size + 1} de 2"
        }

        instructionText.text = "Centralize o rosto e olhe reto para a câmera. A distância é ajustada automaticamente."
        captureButton.isEnabled = false
        stabilityText.text = "Estabilidade: 0%"
        liveOverlay.phase = CapturePhase.FACE
        liveOverlay.cardValid = false
        liveOverlay.faceValid = false
        liveOverlay.poseValid = false
        liveOverlay.distanceValid = false
        liveOverlay.invalidate()

        startCamera()
    }

    private fun evaluateDnpSeries() {
        val od = dnpCaptures.map { it.od }
        val oe = dnpCaptures.map { it.oe }
        val pd = dnpCaptures.map { it.pd }

        val final = DnpCapture(median(od), median(oe), median(pd))
        finalDnp = final

        val spread = maxOf(range(od), range(oe), range(pd))
        showDnpSummary(final, spread <= 1.0, spread)
    }

    private fun showDnpSummary(final: DnpCapture, repeatOk: Boolean, spread: Double) {
        cameraScreen.visibility = View.GONE
        reviewScreen.visibility = View.GONE
        summaryScreen.visibility = View.VISIBLE

        summaryTitle.text = if (repeatOk) "DNP validada" else "DNP precisa ser repetida"
        summaryValues.text =
            "DNP OD: ${fmt(final.od)} mm\n" +
                "DNP OE: ${fmt(final.oe)} mm\n" +
                "DP total: ${fmt(final.pd)} mm"

        repeatabilityText.text =
            "Maior variação entre as 3 capturas: ${fmt(spread)} mm. " +
                if (repeatOk) "Repetibilidade aprovada." else "A série não será liberada para uso."

        dnpOnlyButton.visibility = if (repeatOk) View.VISIBLE else View.GONE
        multifocalButton.visibility = if (repeatOk) View.VISIBLE else View.GONE
        retrySeriesButton.visibility = if (repeatOk) View.GONE else View.VISIBLE
        multifocalNotice.visibility = View.GONE
        startHeightButton.visibility = View.GONE
        shareButton.visibility = View.GONE
        newMeasurementButton.visibility = View.GONE
    }

    private fun evaluateHeightSeries() {
        val od = heightCaptures.map { it.od }
        val oe = heightCaptures.map { it.oe }
        val final = HeightCapture((od[0] + od[1]) / 2.0, (oe[0] + oe[1]) / 2.0)
        finalHeight = final
        val spread = maxOf(range(od), range(oe))

        cameraScreen.visibility = View.GONE
        reviewScreen.visibility = View.GONE
        summaryScreen.visibility = View.VISIBLE

        val dnp = finalDnp ?: return
        summaryTitle.text = if (spread <= 1.0) "Medição completa validada" else "Altura precisa ser repetida"
        summaryValues.text =
            "DNP OD: ${fmt(dnp.od)} mm\n" +
                "DNP OE: ${fmt(dnp.oe)} mm\n" +
                "DP total: ${fmt(dnp.pd)} mm\n\n" +
                "Altura OD: ${fmt(final.od)} mm\n" +
                "Altura OE: ${fmt(final.oe)} mm"

        repeatabilityText.text =
            "Maior diferença entre as 2 capturas de altura: ${fmt(spread)} mm. " +
                if (spread <= 1.0) "Repetibilidade aprovada." else "Refaça a série de altura sem reajustar a armação."

        dnpOnlyButton.visibility = View.GONE
        multifocalButton.visibility = View.GONE
        multifocalNotice.visibility = View.GONE
        startHeightButton.visibility = View.GONE

        if (spread <= 1.0) {
            retrySeriesButton.visibility = View.GONE
            shareButton.visibility = View.VISIBLE
            newMeasurementButton.visibility = View.VISIBLE
        } else {
            retrySeriesButton.visibility = View.VISIBLE
            shareButton.visibility = View.GONE
            newMeasurementButton.visibility = View.GONE
        }
    }

    private fun showFinalResult(withHeight: Boolean) {
        val dnp = finalDnp ?: return
        summaryTitle.text = if (withHeight && finalHeight != null) "Medição completa" else "DNP validada"
        summaryValues.text = buildString {
            append("DNP OD: ${fmt(dnp.od)} mm\n")
            append("DNP OE: ${fmt(dnp.oe)} mm\n")
            append("DP total: ${fmt(dnp.pd)} mm")
            if (withHeight) {
                finalHeight?.let {
                    append("\n\nAltura OD: ${fmt(it.od)} mm\n")
                    append("Altura OE: ${fmt(it.oe)} mm")
                }
            }
        }
        dnpOnlyButton.visibility = View.GONE
        multifocalButton.visibility = View.GONE
        multifocalNotice.visibility = View.GONE
        startHeightButton.visibility = View.GONE
        retrySeriesButton.visibility = View.GONE
        shareButton.visibility = View.VISIBLE
        newMeasurementButton.visibility = View.VISIBLE
    }

    private fun shareResult() {
        val dnp = finalDnp ?: return
        val text = buildString {
            append("Óptiker — Medição Online\n\n")
            append("DNP OD: ${fmt(dnp.od)} mm\n")
            append("DNP OE: ${fmt(dnp.oe)} mm\n")
            append("DP total: ${fmt(dnp.pd)} mm\n")
            finalHeight?.let {
                append("Altura OD: ${fmt(it.od)} mm\n")
                append("Altura OE: ${fmt(it.oe)} mm\n")
            }
            append("\nMedição validada por capturas repetidas no app Óptiker.")
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Enviar medidas"))
    }

    private fun resetAll() {
        dnpCaptures.clear()
        heightCaptures.clear()
        finalDnp = null
        finalHeight = null
        workflowMode = WorkflowMode.DNP
        currentReview = null

        cameraScreen.visibility = View.VISIBLE
        reviewScreen.visibility = View.GONE
        summaryScreen.visibility = View.GONE

        stepText.text = "DNP • captura 1 de 3"
        instructionText.text = "Centralize o rosto e olhe reto para a câmera. A distância é ajustada automaticamente."
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2]
        else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    private fun range(values: List<Double>): Double =
        (values.maxOrNull() ?: 0.0) - (values.minOrNull() ?: 0.0)

    private fun fmt(v: Double): String = String.format(java.util.Locale("pt", "BR"), "%.1f", v)

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        faceDetector.close()
        cameraExecutor.shutdown()
        latestValidFrame?.bitmap?.let { if (!it.isRecycled) it.recycle() }
        currentReview?.bitmap?.let { if (!it.isRecycled) it.recycle() }
    }
}
