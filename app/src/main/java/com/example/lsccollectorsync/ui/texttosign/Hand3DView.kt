package com.example.lsccollectorsync.ui.texttosign

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Centra la mano en el origen y la escala a radio 1. Entrada y salida: 63 floats (21 puntos x,y,z). */
fun normalizarMano(src: FloatArray): FloatArray {
    val out = FloatArray(63)
    if (src.size < 63) return out
    var mx = 0f
    var my = 0f
    var mz = 0f
    for (i in 0 until 21) {
        mx += src[3 * i]; my += src[3 * i + 1]; mz += src[3 * i + 2]
    }
    mx /= 21f; my /= 21f; mz /= 21f
    var maxD = 0f
    for (i in 0 until 21) {
        val dx = src[3 * i] - mx
        val dy = src[3 * i + 1] - my
        val dz = src[3 * i + 2] - mz
        maxD = max(maxD, sqrt(dx * dx + dy * dy + dz * dz))
    }
    if (maxD < 1e-6f) maxD = 1f
    for (i in 0 until 21) {
        out[3 * i] = (src[3 * i] - mx) / maxD
        out[3 * i + 1] = (src[3 * i + 1] - my) / maxD
        out[3 * i + 2] = (src[3 * i + 2] - mz) / maxD
    }
    return out
}

/** Como normalizarMano, pero centro y escala salen del primer fotograma y valen para todos. */
fun normalizarSecuencia(frames: List<FloatArray>): List<FloatArray> {
    val ref = frames.first()
    val c = FloatArray(3)
    for (i in 0 until 21) for (k in 0 until 3) c[k] += ref[3 * i + k] / 21f
    var maxD = 0f
    for (i in 0 until 21) {
        val dx = ref[3 * i] - c[0]
        val dy = ref[3 * i + 1] - c[1]
        val dz = ref[3 * i + 2] - c[2]
        maxD = max(maxD, sqrt(dx * dx + dy * dy + dz * dz))
    }
    if (maxD < 1e-6f) maxD = 1f
    return frames.map { f -> FloatArray(63) { j -> (f[j] - c[j % 3]) / maxD } }
}

/** Esqueleto 3D de la mano (21 landmarks de MediaPipe). Se rota arrastrando con el dedo. */
class Hand3DView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private companion object {
        val HUESOS = arrayOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,          // pulgar
            0 to 5, 5 to 6, 6 to 7, 7 to 8,          // indice
            5 to 9, 9 to 10, 10 to 11, 11 to 12,     // medio
            9 to 13, 13 to 14, 14 to 15, 15 to 16,   // anular
            13 to 17, 17 to 18, 18 to 19, 19 to 20,  // menique
            0 to 17                                  // borde de la palma
        )
        val COLORES = intArrayOf(
            Color.parseColor("#FF6B6B"), Color.parseColor("#FFD93D"),
            Color.parseColor("#6BCB77"), Color.parseColor("#4D96FF"),
            Color.parseColor("#C77DFF"), Color.parseColor("#AAAAAA")
        )
        val PUNTAS = intArrayOf(4, 8, 12, 16, 20)
    }

    /** Pon true si la mano se ve en espejo respecto a la seña real. */
    var mirrorX: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private var pose = FloatArray(63)
    private var hasPose = false
    private var yaw = 0.5f
    private var pitch = 0.2f
    private var animator: ValueAnimator? = null
    private var lastX = 0f
    private var lastY = 0f
    private val density = resources.displayMetrics.density

    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BBBBBB")
        textAlign = Paint.Align.CENTER
        textSize = 14f * density
    }

    /** Muestra una pose (63 floats) interpolando desde la actual. null = sin pose. */
    fun mostrarPose(landmarks: FloatArray?, duracionMs: Long = 350L) {
        animator?.cancel()
        if (landmarks == null || landmarks.size < 63) {
            hasPose = false
            invalidate()
            return
        }
        val destino = normalizarMano(landmarks)
        if (!hasPose) {
            pose = destino
            hasPose = true
            invalidate()
            return
        }
        val origen = pose.copyOf()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = duracionMs
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                for (i in 0 until 63) pose[i] = origen[i] + (destino[i] - origen[i]) * t
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        if (!hasPose) {
            canvas.drawText("Sin pose para esta letra", cx, cy, textPaint)
            return
        }
        val radio = min(width, height) * 0.42f
        val ca = cos(yaw)
        val sa = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)
        val signo = if (mirrorX) -1f else 1f

        val px = FloatArray(21)
        val py = FloatArray(21)
        val pz = FloatArray(21)
        val ps = FloatArray(21)
        for (i in 0 until 21) {
            val x = signo * pose[3 * i]
            val y = pose[3 * i + 1]
            val z = pose[3 * i + 2]
            val x1 = x * ca + z * sa
            val z1 = -x * sa + z * ca
            val y2 = y * cp - z1 * sp
            val z2 = y * sp + z1 * cp
            val s = 1f / (1f + 0.4f * z2) // z negativo = mas cerca = mas grande
            px[i] = cx + x1 * s * radio
            py[i] = cy + y2 * s * radio
            pz[i] = z2
            ps[i] = s
        }

        // Lo mas lejano primero
        val orden = HUESOS.indices.sortedByDescending { pz[HUESOS[it].first] + pz[HUESOS[it].second] }
        for (i in orden) {
            val (a, b) = HUESOS[i]
            bonePaint.color = COLORES[i / 4]
            bonePaint.strokeWidth = 12f * density * (ps[a] + ps[b]) / 2f
            canvas.drawLine(px[a], py[a], px[b], py[b], bonePaint)
        }
        for (j in (0 until 21).sortedByDescending { pz[it] }) {
            val r = (if (j in PUNTAS) 5f else 3.5f) * density * ps[j]
            canvas.drawCircle(px[j], py[j], r, jointPaint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                yaw += (e.x - lastX) * 0.01f
                pitch = (pitch + (e.y - lastY) * 0.01f).coerceIn(-1.4f, 1.4f)
                lastX = e.x
                lastY = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
