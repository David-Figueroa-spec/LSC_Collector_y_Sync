package com.example.lsccollectorsync.ui.texttosign

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Genera los 21 landmarks (63 floats) desde 20 ángulos en grados:
 * [pulgar: abd, cmc, mcp, ip] [indice: spread, mcp, pip, dip] [medio] [anular] [menique].
 * Ejes: x derecha, y abajo, z negativo hacia quien mira. Palma hacia quien mira, dedos hacia arriba.
 * Flexión positiva = el dedo se curva hacia la palma. abd/spread positivo = hacia +x (lado del pulgar).
 */
object HandKinematics {

    // x de la base, y de la base, largo de los 3 segmentos: indice, medio, anular, menique
    private val DEDOS = arrayOf(
        floatArrayOf(0.30f, -1.00f, 0.45f, 0.28f, 0.20f),
        floatArrayOf(0.10f, -1.05f, 0.50f, 0.31f, 0.22f),
        floatArrayOf(-0.10f, -1.00f, 0.45f, 0.29f, 0.21f),
        floatArrayOf(-0.28f, -0.90f, 0.35f, 0.20f, 0.18f)
    )
    private val PULGAR = floatArrayOf(0.25f, -0.15f, 0.40f, 0.30f, 0.25f)

    private fun rad(g: Float): Float = g * PI.toFloat() / 180f

    private fun cadena(out: FloatArray, idx: Int, base: FloatArray, ang: FloatArray, o: Int) {
        var x = base[0]
        var y = base[1]
        var z = 0f
        out[3 * idx] = x; out[3 * idx + 1] = y; out[3 * idx + 2] = z
        val a = rad(ang[o])
        var fl = 0f
        for (k in 0 until 3) {
            fl += rad(ang[o + 1 + k])
            val l = base[2 + k]
            x += l * sin(a) * cos(fl)
            y += -l * cos(a) * cos(fl)
            z += -l * sin(fl)
            val p = idx + 1 + k
            out[3 * p] = x; out[3 * p + 1] = y; out[3 * p + 2] = z
        }
    }

    fun build(ang: FloatArray): FloatArray {
        val out = FloatArray(63) // el punto 0 (muñeca) queda en el origen
        cadena(out, 1, PULGAR, ang, 0)
        for (d in 0 until 4) cadena(out, 5 + 4 * d, DEDOS[d], ang, 4 + 4 * d)
        return out
    }
}
