package com.optiker.medidas.v5

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.ar.core.*
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

/** Research prototype. Not a validated optical measurement device. */
class V5MeasurementActivity : AppCompatActivity(), GLSurfaceView.Renderer {
    private lateinit var gl: GLSurfaceView
    private lateinit var imageView: ImageView
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var start: Button
    private lateinit var diagnostics: Button
    private var session: Session? = null
    private var landmarker: FaceLandmarker? = null
    private var askedInstall = false
    private var glRunning = false
    @Volatile private var active = false
    @Volatile private var collecting = false
    @Volatile private var generation = 0
    @Volatile private var ready = false
    @Volatile private var cameraInfo = "Aguardando câmera ARCore."
    @Volatile private var frameInfo = "Aguardando frame."
    private var rotation = 0
    private var tex = 0
    private var lastTime = 0L
    private var lastStamp = -1L
    private var sampleGeneration = -1
    private val samples = ArrayList<Reading>()
    private var displayed: Bitmap? = null
    private val purple = Color.rgb(100,40,158)
    private val deep = Color.rgb(26,11,46)
    private val green = Color.rgb(57,255,20)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) openSession() else status.text = "Permita a câmera nas configurações."
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); setPadding(dp(12),dp(10),dp(12),dp(12)) }
        fun label(s:String, size:Float) = TextView(this).apply { text=s; textSize=size; setTextColor(deep); setPadding(dp(4),dp(5),dp(4),dp(5)) }
        root.addView(label("Óptiker • V5.2",23f).apply { setTextColor(purple); setTypeface(null,1) })
        root.addView(label("Pupilometria experimental • sem cartão",13f))
        val preview = FrameLayout(this).apply { setBackgroundColor(deep) }
        gl=GLSurfaceView(this).apply { setEGLContextClientVersion(2); preserveEGLContextOnPause=true; setRenderer(this@V5MeasurementActivity); renderMode=GLSurfaceView.RENDERMODE_CONTINUOUSLY }
        preview.addView(gl,FrameLayout.LayoutParams(-1,-1))
        imageView=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER; setBackgroundColor(deep); scaleX=-1f }
        preview.addView(imageView,FrameLayout.LayoutParams(-1,-1))
        root.addView(preview,LinearLayout.LayoutParams(-1,0,1f))
        status=label("Iniciando câmera 3D…",15f).apply { gravity=Gravity.CENTER }
        root.addView(status)
        result=label("Resultado experimental — não usar para fabricar lentes.",14f)
        root.addView(result)
        start=Button(this).apply { text="INICIAR MEDIÇÃO"; isEnabled=false; setTextColor(Color.WHITE); backgroundTintList=android.content.res.ColorStateList.valueOf(purple); setOnClickListener {
            if (!ready) return@setOnClickListener
            generation++; collecting=true; result.text="Aguarde 20 leituras consecutivas. Mantenha o rosto reto e olhe para a câmera."; isEnabled=false
        } }
        root.addView(start,LinearLayout.LayoutParams(-1,dp(52)))
        diagnostics=Button(this).apply { text="Diagnóstico da câmera"; isAllCaps=false; setOnClickListener { androidx.appcompat.app.AlertDialog.Builder(this@V5MeasurementActivity).setTitle("Diagnóstico V5.2").setMessage(cameraInfo+"\n\n"+frameInfo+"\n\nEscala estimada pelo modelo ARCore; sem calibração clínica. Distância de fixação não compensada.").setPositiveButton("Fechar",null).show() } }
        root.addView(diagnostics)
        setContentView(root)
    }
    private fun dp(x:Int)= (x*resources.displayMetrics.density).roundToInt()
    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) openSession()
        else permission.launch(Manifest.permission.CAMERA)
    }
    private fun openSession() {
        if (active || isFinishing) return
        try {
            if (session==null) {
                when(ArCoreApk.getInstance().requestInstall(this,!askedInstall)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { askedInstall=true; status.text="Instale ou atualize os Serviços do Google Play para RA."; return }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                }
                val s=Session(this)
                try {
                    val filter=CameraConfigFilter(s).setFacingDirection(CameraConfig.FacingDirection.FRONT)
                    val configs=s.getSupportedCameraConfigs(filter)
                    require(configs.isNotEmpty()) { "ARCore sem câmera frontal compatível" }
                    s.cameraConfig=configs.filter { it.imageSize.width.toLong()*it.imageSize.height<=1920L*1080 }.maxByOrNull { it.imageSize.width.toLong()*it.imageSize.height } ?: configs.first()
                    val config=Config(s).apply { augmentedFaceMode=Config.AugmentedFaceMode.MESH3D; updateMode=Config.UpdateMode.LATEST_CAMERA_IMAGE }
                    s.configure(config)
                    val id=s.cameraConfig.cameraId
                    val c=getSystemService(CameraManager::class.java).getCameraCharacteristics(id)
                    rotation=c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    require(rotation in listOf(0,90,180,270)) { "Orientação de câmera desconhecida" }
                    val sensor=c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val focal=c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val raw=c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
                    val distortion=if(Build.VERSION.SDK_INT>=28)c.get(CameraCharacteristics.LENS_DISTORTION) else null
                    cameraInfo="${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\nCâmera selecionada pelo ARCore: $id\nSensor: $sensor\nFocal: ${focal?.joinToString() ?: "não informada"} mm\nIntrínsecos Camera2: ${raw?.joinToString() ?: "não informados"}\nDistorção Camera2: ${distortion?.joinToString() ?: "não informada"}\nOrientação: $rotation°\nARCore: sessão frontal MESH3D"
                    session=s
                } catch(e:Exception) { s.close(); throw e }
            }
            session!!.resume()
            active=true; glRunning=true; gl.onResume()
        } catch(e:Exception) { active=false; status.text="Não foi possível iniciar ARCore: ${e.message ?: e.javaClass.simpleName}. Nenhuma medida será produzida." }
    }
    override fun onPause() {
        active=false; collecting=false; ready=false; generation++; start.isEnabled=false
        if(glRunning){ gl.onPause(); glRunning=false; session?.pause() }
        super.onPause()
    }
    override fun onDestroy() {
        active=false
        session?.close(); session=null
        landmarker?.close(); landmarker=null
        imageView.setImageDrawable(null); displayed?.recycle(); displayed=null
        super.onDestroy()
    }
    override fun onSurfaceCreated(unused:GL10?, config:EGLConfig?) {
        val ids=IntArray(1); GLES20.glGenTextures(1,ids,0); tex=ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glClearColor(0f,0f,0f,1f)
    }
    override fun onSurfaceChanged(unused:GL10?, width:Int,height:Int){ GLES20.glViewport(0,0,width,height) }
    override fun onDrawFrame(unused:GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val s=session ?: return
        if(!active || tex==0) return
        try {
            s.setCameraTextureName(tex)
            val frame=s.update()
            val now=SystemClock.elapsedRealtime()
            if(frame.timestamp==0L || frame.timestamp==lastStamp || now-lastTime<180) return
            lastStamp=frame.timestamp; lastTime=now
            if(sampleGeneration!=generation){ samples.clear(); sampleGeneration=generation }
            val face=s.getAllTrackables(AugmentedFace::class.java).filter { it.trackingState==TrackingState.TRACKING }.singleOrNull()
            val ci=frame.camera.imageIntrinsics
            val f=ci.focalLength; val c=ci.principalPoint; val dims=ci.imageDimensions
            frameInfo="Imagem ARCore: ${dims[0]} × ${dims[1]}\nfx=${f[0]}, fy=${f[1]}, cx=${c[0]}, cy=${c[1]}"
            val raw=frame.acquireCameraImage().use { img ->
                if(img.width!=dims[0] || img.height!=dims[1]) return reject("Intrínsecos não correspondem à imagem.")
                if(frame.androidCameraTimestamp != 0L && img.timestamp!=frame.androidCameraTimestamp) return reject("Sincronizando a imagem e a malha 3D…")
                yuvToBitmap(img)
            }
            val matrix=Matrix().apply { postRotate(rotation.toFloat()) }
            val rect=RectF(0f,0f,raw.width.toFloat(),raw.height.toFloat()); matrix.mapRect(rect); matrix.postTranslate(-rect.left,-rect.top)
            val upright=Bitmap.createBitmap(rect.width().roundToInt(),rect.height().roundToInt(),Bitmap.Config.ARGB_8888)
            Canvas(upright).drawBitmap(raw,matrix,null); raw.recycle()
            var handedOff=false
            try {
                if(face==null){ publish(upright,"Centralize apenas um rosto na câmera.",null); handedOff=true; return }
                if(landmarker==null){
                    val opts=FaceLandmarker.FaceLandmarkerOptions.builder().setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build()).setRunningMode(RunningMode.IMAGE).setNumFaces(2).setMinFaceDetectionConfidence(0.7f).setMinFacePresenceConfidence(0.7f).build()
                    landmarker=FaceLandmarker.createFromOptions(this,opts)
                }
                val inferenceImage=upright.copy(Bitmap.Config.ARGB_8888,false)
                val mp=BitmapImageBuilder(inferenceImage).build()
                val marks=try { landmarker!!.detect(mp).faceLandmarks().singleOrNull() } finally { mp.close(); if(!inferenceImage.isRecycled) inferenceImage.recycle() }
                if(marks==null || marks.size<478){ publish(upright,"Aguardando identificação das duas íris.",null); handedOff=true; return }
                val pixels=marks.map { PointF(it.x()*upright.width,it.y()*upright.height) }
                val cameraFromFace=frame.camera.pose.inverse().compose(face.centerPose)
                val inv=Matrix(); if(!matrix.invert(inv)){ publish(upright,"Transformação de câmera inválida.",null); handedOff=true; return }
                val model=MetricMesh(face,cameraFromFace,f,c,matrix,inv)
                val reading=model.read(pixels,upright.width,upright.height)
                model.draw(Canvas(upright),pixels,reading!=null,green)
                frameInfo+="\nMalha ARCore: ${model.count} vértices\nAmostras interpoladas: ${model.denseCount}\nMediaPipe: ${marks.size} pontos\nErro de registro: ${model.registrationError?.let { fmt(it.toDouble())+" px" } ?: "indisponível"}"
                publish(upright,if(reading!=null) "Rosto e íris identificados. Olhe reto para a câmera." else "Ajuste o rosto: olhos abertos, cabeça reta e luz uniforme.",reading)
                handedOff=true
            } finally { if(!handedOff && !upright.isRecycled) upright.recycle() }
        } catch(_:NotYetAvailableException) { reject("Sincronizando a câmera 3D…") }
        catch(e:Exception){ reject("Falha de captura: ${e.message ?: e.javaClass.simpleName}") }
    }
    private fun reject(message:String){ ready=false; samples.clear(); runOnUiThread { if(active){ status.text=message; start.isEnabled=false } } }
    private fun publish(b:Bitmap,message:String,r:Reading?) {
        ready=r!=null
        val g=generation
        if(sampleGeneration!=g){ samples.clear(); sampleGeneration=g }
        var done:List<Reading>?=null
        if(collecting){
            if(r==null) samples.clear() else samples.add(r)
            if(samples.size>=20){ done=samples.toList(); samples.clear(); collecting=false }
        }
        val n=samples.size
        runOnUiThread {
            if(!active || isDestroyed){ b.recycle(); return@runOnUiThread }
            displayed=b; imageView.setImageBitmap(b)
            if(g!=generation) return@runOnUiThread
            status.text= if(collecting) "$message\nLeituras consecutivas: $n/20" else message
            start.isEnabled=ready && !collecting
            if(done!=null){
                val series=done!!
                fun med(a:List<Double>)=a.sorted().let { (it[9]+it[10])/2 }
                fun span(a:List<Double>)=a.maxOrNull()!!-a.minOrNull()!!
                val od=series.map { it.od }; val oe=series.map { it.oe }; val total=series.map { it.od+it.oe }
                val spread=maxOf(span(od),span(oe),span(total))
                result.text=if(spread<=1.0) "ESTIMATIVA DE PERTO (não validada)\nOD ${fmt(med(od))} mm • OE ${fmt(med(oe))} mm\nDP ${fmt(med(od)+med(oe))} mm • variação ${fmt(spread)} mm\nNão utilizar para fabricação de lentes." else "Série instável: variação ${fmt(spread)} mm. Repita a medição."
                start.text="NOVA MEDIÇÃO"
            }
        }
    }
    private fun fmt(x:Double)=String.format(java.util.Locale("pt","BR"),"%.1f",x)
    private fun yuvToBitmap(image:Image):Bitmap {
        require(image.format == android.graphics.ImageFormat.YUV_420_888)
        val w=image.width; val h=image.height; val planes=image.planes
        val y=planes[0].buffer; val u=planes[1].buffer; val v=planes[2].buffer
        val ybase=y.position(); val ubase=u.position(); val vbase=v.position()
        val data=IntArray(w*h)
        for(j in 0 until h) for(i in 0 until w){
            val yy=(y.get(ybase+j*planes[0].rowStride+i*planes[0].pixelStride).toInt() and 255)-16
            val uu=(u.get(ubase+(j/2)*planes[1].rowStride+(i/2)*planes[1].pixelStride).toInt() and 255)-128
            val vv=(v.get(vbase+(j/2)*planes[2].rowStride+(i/2)*planes[2].pixelStride).toInt() and 255)-128
            val a=max(0,yy)*298
            val r=((a+409*vv+128) shr 8).coerceIn(0,255); val g=((a-100*uu-208*vv+128) shr 8).coerceIn(0,255); val b=((a+516*uu+128) shr 8).coerceIn(0,255)
            data[j*w+i]=Color.rgb(r,g,b)
        }
        return Bitmap.createBitmap(data,w,h,Bitmap.Config.ARGB_8888)
    }
}
