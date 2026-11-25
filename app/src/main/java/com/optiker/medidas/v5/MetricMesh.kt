package com.optiker.medidas.v5

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import com.google.ar.core.AugmentedFace
import com.google.ar.core.Pose
import kotlin.math.*

internal data class Reading(val od:Double,val oe:Double)

/** All positions in the ARCore face coordinate system are in metres.
 * Ray/plane intersection provides an experimental iris position; the eye surface
 * is approximated from neighbouring mesh vertices, not measured by a depth sensor.
 */
internal class MetricMesh(face:AugmentedFace, private val cameraFromFace:Pose,
    private val focal:FloatArray, private val principal:FloatArray,
    private val upright:Matrix, private val rawFromUpright:Matrix) {
    private data class V(val x:Float,val y:Float,val z:Float) {
        fun arr()=floatArrayOf(x,y,z)
    }
    private val faceFromCamera=cameraFromFace.inverse()
    private val vertices:List<V>
    private val projected:List<PointF?>
    private val dense:ArrayList<V> = ArrayList(8000)
    val count:Int get()=vertices.size
    val denseCount:Int get()=dense.size
    var registrationError:Float?=null
        private set
    private var noseLine:List<PointF> = emptyList()
    init {
        val vb=face.meshVertices.duplicate().apply { rewind() }
        vertices=List(vb.remaining()/3){V(vb.get(),vb.get(),vb.get())}
        projected=vertices.map { project(it) }
        val ib=face.meshTriangleIndices.duplicate().apply { rewind() }
        val seen=HashSet<Triple<Int,Int,Int>>(8000)
        fun add(p:V){
            // Deduplicate at a micrometre for counting only; not a precision claim.
            val key=Triple((p.x*1e6f).roundToInt(),(p.y*1e6f).roundToInt(),(p.z*1e6f).roundToInt())
            if(seen.add(key)) dense.add(p)
        }
        vertices.forEach(::add)
        while(ib.remaining()>=3){
            val i=ib.get().toInt() and 0xffff; val j=ib.get().toInt() and 0xffff; val k=ib.get().toInt() and 0xffff
            if(i !in vertices.indices || j !in vertices.indices || k !in vertices.indices) continue
            val a=vertices[i]; val b=vertices[j]; val c=vertices[k]
            for(m in 0..4) for(n in 0..4-m){
                val u=m/4f; val v=n/4f; val w=1f-u-v
                add(V(a.x*u+b.x*v+c.x*w,a.y*u+b.y*v+c.y*w,a.z*u+b.z*v+c.z*w))
            }
        }
    }
    private fun project(p:V):PointF? {
        val q=cameraFromFace.transformPoint(p.arr()); val depth=-q[2]
        if(depth<=0.01f || focal[0]<=0f || focal[1]<=0f) return null
        val pixel=floatArrayOf(principal[0]+focal[0]*q[0]/depth,principal[1]-focal[1]*q[1]/depth)
        upright.mapPoints(pixel)
        return if(pixel.all { it.isFinite() }) PointF(pixel[0],pixel[1]) else null
    }
    private fun dist(a:PointF,b:PointF)=hypot(a.x-b.x,a.y-b.y)
    private fun nearest(p:PointF):Pair<V,Float>? {
        var best=-1; var err=Float.POSITIVE_INFINITY
        for(i in projected.indices){ val q=projected[i] ?: continue; val d=dist(p,q); if(d<err){ err=d; best=i } }
        return if(best>=0) vertices[best] to err else null
    }
    private fun median(values:List<Float>):Float { val a=values.sorted(); return if(a.size%2==0) (a[a.size/2-1]+a[a.size/2])/2 else a[a.size/2] }
    private fun onPlane(p:PointF,z:Float):V? {
        val raw=floatArrayOf(p.x,p.y); rawFromUpright.mapPoints(raw)
        if(focal.any { !it.isFinite() || it<=0 })return null
        val ray=floatArrayOf((raw[0]-principal[0])/focal[0],-(raw[1]-principal[1])/focal[1],-1f)
        val origin=faceFromCamera.transformPoint(floatArrayOf(0f,0f,0f))
        val tip=faceFromCamera.transformPoint(ray)
        val d=FloatArray(3){tip[it]-origin[it]}
        if(abs(d[2])<0.001f)return null
        val t=(z-origin[2])/d[2]
        if(!t.isFinite() || t<=0f)return null
        return V(origin[0]+t*d[0],origin[1]+t*d[1],z)
    }
    fun read(p:List<PointF>,w:Int,h:Int):Reading? {
        if(count<400 || p.size<478 || denseCount<5000)return null
        val right=p[468]; val left=p[473]
        val pdPx=dist(right,left)
        if(pdPx<w*0.12f || pdPx>w*0.65f)return null
        if(p.any { !it.x.isFinite() || !it.y.isFinite() })return null
        val mid=PointF((right.x+left.x)/2,(right.y+left.y)/2)
        if(abs(mid.x-w/2f)>w*0.15f || mid.y !in h*0.25f..h*0.65f)return null
        val roll=Math.toDegrees(atan2(abs((right.y-left.y).toDouble()),abs((right.x-left.x).toDouble())))
        if(roll>4.0)return null
        val origin=cameraFromFace.transformPoint(floatArrayOf(0f,0f,0f))
        val normal=cameraFromFace.transformPoint(floatArrayOf(0f,0f,1f))
        val nz=normal[2]-origin[2]
        if(nz<cos(Math.toRadians(8.0)).toFloat() || -origin[2] !in 0.20f..0.85f)return null
        fun eyeOpen(a:Int,b:Int,c:Int,d:Int):Boolean { val width=dist(p[a],p[b]); return width>12 && dist(p[c],p[d])/width>0.16f }
        if(!eyeOpen(33,133,159,145) || !eyeOpen(362,263,386,374))return null
        val rightRing=(469..472).map{p[it]}; val leftRing=(474..477).map{p[it]}
        fun ringOk(center:PointF,ring:List<PointF>,a:Int,b:Int,c:Int,d:Int):Boolean {
            val radii=ring.map{dist(center,it)}; val mean=radii.average()
            if(mean<2.0 || radii.minOrNull()!! < mean*0.55 || radii.maxOrNull()!! > mean*1.55)return false
            val x0=min(p[a].x,p[b].x); val x1=max(p[a].x,p[b].x)
            val y0=min(p[c].y,p[d].y); val y1=max(p[c].y,p[d].y)
            return center.x in x0..x1 && center.y in y0-mean.toFloat()..y1+mean.toFloat()
        }
        if(!ringOk(right,rightRing,33,133,159,145) || !ringOk(left,leftRing,362,263,386,374))return null
        // No assumption that the two SDKs use the same vertex ordering.
        val rIds=listOf(33,133,159,145,160,144)
        val lIds=listOf(362,263,386,374,385,380)
        val noseIds=listOf(168,6,197,195,5)
        val all=(rIds+lIds+noseIds).associateWith { nearest(p[it]) ?: return null }
        val errors=all.values.map{it.second}
        registrationError=median(errors)
        if(errors.maxOrNull()!!>max(3f,pdPx*0.065f) || registrationError!!>max(2f,pdPx*0.035f))return null
        val rz=median(rIds.map{all.getValue(it).first.z}); val lz=median(lIds.map{all.getValue(it).first.z})
        val r=onPlane(right,rz) ?: return null; val l=onPlane(left,lz) ?: return null
        val axis=median(noseIds.map{all.getValue(it).first.x})
        noseLine=noseIds.map{p[it]}
        // ARCore local +X points to the subject's left. Reject swapped labels.
        if(r.x>=axis || l.x<=axis)return null
        val od=(axis-r.x)*1000.0; val oe=(l.x-axis)*1000.0
        if(od !in 15.0..50.0 || oe !in 15.0..50.0 || od+oe !in 40.0..85.0)return null
        return Reading(od,oe)
    }
    fun draw(canvas:Canvas,p:List<PointF>,valid:Boolean,green:Int){
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{ color=if(valid)green else Color.rgb(205,183,233); alpha=105; style=Paint.Style.FILL }
        val step=max(1,dense.size/1200)
        for(i in dense.indices step step){project(dense[i])?.let{canvas.drawCircle(it.x,it.y,1.0f,paint)}}
        paint.alpha=230; paint.color=if(valid)green else Color.WHITE; paint.strokeWidth=2f
        val ids=listOf(168,6,197,195,5,4,1)
        for(i in 0 until ids.lastIndex){ val a=p[ids[i]]; val b=p[ids[i+1]]; canvas.drawLine(a.x,a.y,b.x,b.y,paint) }
        val r=p[468]; val l=p[473]
        canvas.drawLine(r.x,r.y,l.x,l.y,paint)
        for(q in listOf(r,l)){ canvas.drawCircle(q.x,q.y,3.5f,paint); paint.style=Paint.Style.STROKE; canvas.drawCircle(q.x,q.y,9f,paint); paint.style=Paint.Style.FILL }
    }
}
