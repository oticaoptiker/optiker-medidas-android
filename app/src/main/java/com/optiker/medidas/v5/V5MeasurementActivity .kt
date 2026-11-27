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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.view.View
import android.view.Surface
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Research prototype. Not a validated optical measurement device. */
class V5MeasurementActivity : AppCompatActivity(), GLSurfaceView.Renderer {
    private lateinit var gl: GLSurfaceView
    private lateinit var overlay: FaceOverlay
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var surfaceW = 0
    private var surfaceH = 0
    private var appliedRotation = -1
    @Volatile private var displayRotation = Surface.ROTATION_0
    @Volatile private var readyAt = 0L
    private var cameraProgram = 0
    private val quad = floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f)
    private val uv = FloatArray(8)
    private val quadBuffer = floats(quad)
    private val uvBuffer = floats(uv)
    private fun floats(a:FloatArray) = ByteBuffer.allocateDirect(a.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); rewind() }
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
    private var lastAcceptedAt = 0L
    private val samples = ArrayList<Reading>()
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
        root.addView(label("Óptiker • V5.2.2",23f).apply { setTextColor(purple); setTypeface(null,1) })
        root.addView(label("Pupilometria experimental • sem cartão",13f))
        val preview = FrameLayout(this).apply { setBackgroundColor(deep) }
        gl=GLSurfaceView(this).apply { setEGLContextClientVersion(2); preserveEGLContextOnPause=true; setRenderer(this@V5MeasurementActivity); renderMode=GLSurfaceView.RENDERMODE_CONTINUOUSLY }
        preview.addView(gl,FrameLayout.LayoutParams(-1,-1))
        overlay=FaceOverlay()
        preview.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        root.addView(preview,LinearLayout.LayoutParams(-1,0,1f))
        status=label("Iniciando câmera 3D…",15f).apply { gravity=Gravity.CENTER }
        root.addView(status)
        result=label("Resultado experimental — não usar para fabricar lentes.",14f)
        root.addView(result)
        start=Button(this).apply { text="INICIAR MEDIÇÃO"; isEnabled=false; setTextColor(Color.WHITE); backgroundTintList=android.content.res.ColorStateList.valueOf(purple); setOnClickListener {
            if (!ready || SystemClock.elapsedRealtime()-readyAt>1500L) { setReady(false); return@setOnClickListener }
            generation++; collecting=true; result.text="Aguarde 20 leituras válidas. Mantenha o rosto reto e olhe para a câmera."; setReady(false)
            val attempt=generation
            postDelayed({
                if(active && collecting && generation==attempt){
                    collecting=false; generation++; samples.clear(); ready=false; setReady(false)
                    result.text="Tempo de medição excedido. Confira o diagnóstico e tente novamente."
                    text="TENTAR NOVAMENTE"
                }
            },30000L)
        } }
        root.addView(start,LinearLayout.LayoutParams(-1,dp(52)))
        diagnostics=Button(this).apply { text="Diagnóstico da câmera"; isAllCaps=false; setOnClickListener { androidx.appcompat.app.AlertDialog.Builder(this@V5MeasurementActivity).setTitle("Diagnóstico V5.2.2").setMessage(cameraInfo+"\n\n"+frameInfo+"\n\nEscala estimada pelo modelo ARCore; sem calibração clínica. Distância de fixação não compensada.").setPositiveButton("Fechar",null).show() } }
        root.addView(diagnostics)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(12)+bars.left,dp(10)+bars.top,dp(12)+bars.right,dp(12)+bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        setReady(false)
        displayRotation=windowManager.defaultDisplay.rotation
    }
    private fun dp(x:Int)= (x*resources.displayMetrics.density).roundToInt()
    override fun onResume() {
        super.onResume()
        displayRotation=windowManager.defaultDisplay.rotation
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
                    s.cameraConfig=configs.first()
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
        active=false; collecting=false; ready=false; generation++; setReady(false)
        overlay.points=emptyList(); overlay.invalidate()
        if(glRunning){ gl.onPause(); glRunning=false; session?.pause() }
        super.onPause()
    }
    override fun onDestroy() {
        active=false
        session?.close(); session=null
        worker.execute { landmarker?.close(); landmarker=null }
        worker.shutdown()
        super.onDestroy()
    }
    private fun setReady(enabled:Boolean) {
        start.isEnabled=enabled
        start.backgroundTintList=android.content.res.ColorStateList.valueOf(if(enabled) purple else Color.DKGRAY)
    }
    override fun onSurfaceCreated(unused:GL10?, config:EGLConfig?) {
        val ids=IntArray(1); GLES20.glGenTextures(1,ids,0); tex=ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glClearColor(0f,0f,0f,1f)
        fun shader(type:Int,source:String):Int {
            val id=GLES20.glCreateShader(type); GLES20.glShaderSource(id,source); GLES20.glCompileShader(id)
            val ok=IntArray(1); GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,ok,0)
            if(ok[0]==0){ android.util.Log.e("OptikerAR",GLES20.glGetShaderInfoLog(id)); GLES20.glDeleteShader(id); return 0 }; return id
        }
        val vs=shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 aPos; attribute vec2 aUv; varying vec2 vUv; void main(){gl_Position=vec4(aPos,0.0,1.0);vUv=aUv;}")
        val fs=shader(GLES20.GL_FRAGMENT_SHADER,"#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES uCamera; varying vec2 vUv; void main(){gl_FragColor=texture2D(uCamera,vUv);}")
        cameraProgram=0
        if(vs!=0 && fs!=0){
            val p=GLES20.glCreateProgram(); GLES20.glAttachShader(p,vs); GLES20.glAttachShader(p,fs); GLES20.glLinkProgram(p)
            val ok=IntArray(1); GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0)
            if(ok[0]!=0) cameraProgram=p else GLES20.glDeleteProgram(p)
        }
        if(vs!=0) GLES20.glDeleteShader(vs); if(fs!=0) GLES20.glDeleteShader(fs)
        appliedRotation=-1
    }
    override fun onSurfaceChanged(unused:GL10?, width:Int,height:Int){
        surfaceW=width; surfaceH=height; appliedRotation=-1; GLES20.glViewport(0,0,width,height)
    }
    private fun renderCamera(frame:Frame){
        if(cameraProgram==0 || frame.timestamp==0L) return
        frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,quad,Coordinates2d.TEXTURE_NORMALIZED,uv)
        uvBuffer.rewind(); uvBuffer.put(uv); uvBuffer.rewind(); quadBuffer.rewind()
        GLES20.glUseProgram(cameraProgram)
        val a=GLES20.glGetAttribLocation(cameraProgram,"aPos"); val b=GLES20.glGetAttribLocation(cameraProgram,"aUv")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(cameraProgram,"uCamera"),0)
        GLES20.glEnableVertexAttribArray(a); GLES20.glEnableVertexAttribArray(b)
        GLES20.glVertexAttribPointer(a,2,GLES20.GL_FLOAT,false,0,quadBuffer)
        GLES20.glVertexAttribPointer(b,2,GLES20.GL_FLOAT,false,0,uvBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        GLES20.glDisableVertexAttribArray(a); GLES20.glDisableVertexAttribArray(b)
    }
    private data class PlaneCopy(val bytes:ByteArray,val row:Int,val pixel:Int)
    private data class Photo(val w:Int,val h:Int,val planes:List<PlaneCopy>)
    private fun copyPhoto(img:Image):Photo {
        require(img.format==android.graphics.ImageFormat.YUV_420_888)
        return Photo(img.width,img.height,img.planes.map { p ->
            val buf=p.buffer.duplicate(); val a=ByteArray(buf.remaining()); buf.get(a); PlaneCopy(a,p.rowStride,p.pixelStride)
        })
    }
    override fun onDrawFrame(unused:GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val s=session ?: return
        if(!active || tex==0 || surfaceW<=0 || surfaceH<=0) return
        try {
            if(appliedRotation!=displayRotation){ s.setDisplayGeometry(displayRotation,surfaceW,surfaceH); appliedRotation=displayRotation }
            s.setCameraTextureName(tex)
            val frame=s.update()
            if(cameraProgram==0){ reject("Falha no renderizador da câmera."); return }
            renderCamera(frame)
            val now=SystemClock.elapsedRealtime()
            if(frame.timestamp==0L || frame.timestamp==lastStamp || now-lastTime<220 || !busy.compareAndSet(false,true)) return
            lastStamp=frame.timestamp; lastTime=now
            val g=generation
            var submitted=false
            try {
                val faces=s.getAllTrackables(AugmentedFace::class.java)
                val tracked=faces.filter { it.trackingState==TrackingState.TRACKING }
                val face=tracked.singleOrNull()
                val trackedCount=tracked.size
                val ci=frame.camera.imageIntrinsics
                val f=ci.focalLength; val c=ci.principalPoint; val dims=ci.imageDimensions
                val info="Imagem ARCore: ${dims[0]} × ${dims[1]}\nfx=${f[0]}, fy=${f[1]}, cx=${c[0]}, cy=${c[1]}\nFaces ARCore: ${faces.size}; TRACKING: ${tracked.size}; PAUSED: ${faces.count { it.trackingState==TrackingState.PAUSED }}\nModo: ${s.config.augmentedFaceMode}"
                frameInfo=info
                val photo=frame.acquireCameraImage().use { img ->
                    if(img.width!=dims[0] || img.height!=dims[1]){ reject("Intrínsecos não correspondem à imagem."); return }
                    copyPhoto(img)
                }
                val matrix=Matrix().apply { postRotate(rotation.toFloat()) }
                val rect=RectF(0f,0f,photo.w.toFloat(),photo.h.toFloat()); matrix.mapRect(rect); matrix.postTranslate(-rect.left,-rect.top)
                val inv=Matrix(); if(!matrix.invert(inv)){ reject("Transformação da câmera inválida."); return }
                val rawCorners=floatArrayOf(0f,0f,photo.w.toFloat(),0f,0f,photo.h.toFloat())
                val viewCorners=FloatArray(6)
                frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS,rawCorners,Coordinates2d.VIEW_NORMALIZED,viewCorners)
                val rawToView=Matrix(); if(!rawToView.setPolyToPoly(rawCorners,0,viewCorners,0,3)){ reject("Transformação de tela inválida."); return }
                // Snapshot mesh while frame is current; no live ARCore objects enter the worker.
                val metric=face?.let { MetricMesh(it,frame.camera.pose.inverse().compose(it.centerPose),f,c,matrix,inv) }
                worker.execute {
                    var upright:Bitmap?=null
                    try {
                        val raw=yuvToBitmap(photo)
                        val bitmap=Bitmap.createBitmap(rect.width().roundToInt(),rect.height().roundToInt(),Bitmap.Config.ARGB_8888)
                        upright=bitmap
                        Canvas(bitmap).drawBitmap(raw,matrix,null); raw.recycle()
                        if(landmarker==null){
                            val opts=FaceLandmarker.FaceLandmarkerOptions.builder().setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build()).setRunningMode(RunningMode.IMAGE).setNumFaces(2).setMinFaceDetectionConfidence(0.7f).setMinFacePresenceConfidence(0.7f).build()
                            landmarker=FaceLandmarker.createFromOptions(this,opts)
                        }
                        val mp=BitmapImageBuilder(bitmap).build()
                        val marks=try { landmarker!!.detect(mp).faceLandmarks() } finally { mp.close() }
                        val mark=marks.singleOrNull()
                        val pixels=mark?.map { PointF(it.x()*bitmap.width,it.y()*bitmap.height) }?.takeIf { list -> list.all { it.x.isFinite() && it.y.isFinite() } } ?: emptyList()
                        val reading=if(pixels.size>=478 && metric!=null) metric.read(pixels,bitmap.width,bitmap.height) else null
                        val mapped=pixels.map { p -> val a=floatArrayOf(p.x,p.y); inv.mapPoints(a); rawToView.mapPoints(a); PointF(a[0],a[1]) }
                        val detail=info+"\nMediaPipe: ${marks.size} face(s), ${pixels.size} pontos\nARCore: ${metric?.count ?: 0} vértices; ${metric?.denseCount ?: 0} amostras derivadas\nRegistro: ${metric?.registrationError?.let { fmt(it.toDouble())+" px" } ?: "indisponível"}"
                        val message=when {
                            marks.size>1 -> "Mantenha apenas um rosto na câmera."
                            pixels.size<478 -> "IA procurando rosto e íris."
                            trackedCount>1 -> "ARCore detectou mais de um rosto."
                            metric==null -> "IA reconheceu rosto e íris. ARCore ainda sem malha métrica."
                            reading==null -> "Malha 3D e íris detectadas. Ajuste a posição para validar a geometria."
                            else -> "Rosto e íris reconhecidos. Pronto para medir."
                        }
                        runOnUiThread { if(active && !isDestroyed && g==generation){ frameInfo=detail; publish(mapped,message,reading) } }
                    } catch(e:Exception){ runOnUiThread { if(active && g==generation) publish(emptyList(),"Falha da IA: ${e.message ?: e.javaClass.simpleName}",null) } }
                    finally { upright?.let { if(!it.isRecycled) it.recycle() }; busy.set(false) }
                }
                submitted=true
            } finally { if(!submitted) busy.set(false) }
        } catch(_:NotYetAvailableException) { if(SystemClock.elapsedRealtime()-readyAt>1500L && ready) reject("Aguardando nova imagem da câmera.") }
        catch(e:Exception){ reject("Falha de captura: ${e.message ?: e.javaClass.simpleName}") }
    }
    private fun reject(message:String){ runOnUiThread { if(active && !isDestroyed) publish(emptyList(),message,null) } }
    private fun publish(points:List<PointF>,message:String,r:Reading?) {
        ready=r!=null
        readyAt=if(ready) SystemClock.elapsedRealtime() else 0L
        overlay.points=points; overlay.valid=ready; overlay.invalidate()
        if(sampleGeneration!=generation){ samples.clear(); sampleGeneration=generation; lastAcceptedAt=0L }
        var done:List<Reading>?=null
        if(collecting){
            if(r==null){ samples.clear(); lastAcceptedAt=0L } else {
                val now=SystemClock.elapsedRealtime()
                if(lastAcceptedAt!=0L && now-lastAcceptedAt>2000L) samples.clear()
                samples.add(r); lastAcceptedAt=now
            }
            if(samples.size>=20){ done=samples.toList(); samples.clear(); collecting=false }
        }
        status.text=if(collecting) "$message\nLeituras analisadas válidas: ${samples.size}/20" else message
        setReady(ready && !collecting)
        done?.let { series ->
            fun med(a:List<Double>)=a.sorted().let { (it[9]+it[10])/2 }
            fun span(a:List<Double>)=a.maxOrNull()!!-a.minOrNull()!!
            val od=series.map { it.od }; val oe=series.map { it.oe }; val total=series.map { it.od+it.oe }
            val spread=maxOf(span(od),span(oe),span(total))
            result.text=if(spread<=1.0) "ESTIMATIVA DE PERTO (não validada)\nOD ${fmt(med(od))} mm • OE ${fmt(med(oe))} mm\nDP ${fmt(med(od)+med(oe))} mm • variação ${fmt(spread)} mm\nNão utilizar para fabricação de lentes." else "Série instável: variação ${fmt(spread)} mm. Repita a medição."
            start.text="NOVA MEDIÇÃO"
        }
    }
    private inner class FaceOverlay:View(this@V5MeasurementActivity){
        var points:List<PointF> = emptyList()
        var valid=false
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas:Canvas){
            super.onDraw(canvas)
            if(points.size<478){
                paint.color=Color.WHITE; paint.alpha=140; paint.style=Paint.Style.STROKE; paint.strokeWidth=dp(1).toFloat()
                canvas.drawOval(width*.22f,height*.18f,width*.78f,height*.82f,paint)
                return
            }
            paint.color=if(valid) green else Color.WHITE; paint.alpha=115; paint.style=Paint.Style.FILL
            points.forEach { p -> canvas.drawCircle(p.x*width,p.y*height,dp(1).toFloat(),paint) }
            paint.alpha=255; paint.strokeWidth=dp(1).toFloat()
            fun line(a:Int,b:Int){ val p=points[a]; val q=points[b]; canvas.drawLine(p.x*width,p.y*height,q.x*width,q.y*height,paint) }
            val nasal=listOf(168,6,197,195,5,4,1)
            nasal.zipWithNext().forEach { (a,b)->line(a,b) }; line(468,473)
            for(i in listOf(468,473)){ val p=points[i]; paint.style=Paint.Style.STROKE; canvas.drawCircle(p.x*width,p.y*height,dp(7).toFloat(),paint); paint.style=Paint.Style.FILL; canvas.drawCircle(p.x*width,p.y*height,dp(2).toFloat(),paint) }
        }
    }
    private fun fmt(x:Double)=String.format(java.util.Locale("pt","BR"),"%.1f",x)
    private fun yuvToBitmap(image:Photo):Bitmap {
        val w=image.w; val h=image.h; val planes=image.planes
        val y=planes[0].bytes; val u=planes[1].bytes; val v=planes[2].bytes
        val ybase=0; val ubase=0; val vbase=0
        val data=IntArray(w*h)
        for(j in 0 until h) for(i in 0 until w){
            val yy=(y.get(ybase+j*planes[0].row+i*planes[0].pixel).toInt() and 255)-16
            val uu=(u.get(ubase+(j/2)*planes[1].row+(i/2)*planes[1].pixel).toInt() and 255)-128
            val vv=(v.get(vbase+(j/2)*planes[2].row+(i/2)*planes[2].pixel).toInt() and 255)-128
            val a=max(0,yy)*298
            val r=((a+409*vv+128) shr 8).coerceIn(0,255); val g=((a-100*uu-208*vv+128) shr 8).coerceIn(0,255); val b=((a+516*uu+128) shr 8).coerceIn(0,255)
            data[j*w+i]=Color.rgb(r,g,b)
        }
        return Bitmap.createBitmap(data,w,h,Bitmap.Config.ARGB_8888)
    }
}
