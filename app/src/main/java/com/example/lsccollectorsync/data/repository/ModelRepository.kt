package com.example.lsccollectorsync.data.repository

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class ModelRepository(private val context: Context) {

    private class ModeloEstatico(val interprete: Interpreter, val etiquetas: List<String>)
    private class ModeloSecuencia(val interprete: Interpreter, val etiquetas: List<String>, val largo: Int)

    private var secuencia: ModeloSecuencia? = null
    private var secuenciaIntentada = false
    private var generacionSecuencia = generacion

    private var estatico: ModeloEstatico? = null
    private var yaIntentado = false
    private var generacionCargada = generacion

    /** Confianza (0..1) de la última predicción hecha por predictSign. */
    @Volatile
    var ultimaConfianza = 0f
        private set

    // Carga perezosa del modelo base (assets). Si algo falla devuelve null y la app sigue sin predicción.
    private fun cargarEstatico(): ModeloEstatico? = synchronized(this) {
        if (generacionCargada != generacion) {   // se instaló o quitó un modelo: recargar
            estatico?.let { m -> synchronized(m) { m.interprete.close() } }
            estatico = null
            yaIntentado = false
            generacionCargada = generacion
        }
        if (!yaIntentado) {
            yaIntentado = true
            estatico = try {
                // Prioridad: modelo actualizado (filesDir/modelos/actual); si no existe, el base de assets.
                val dirActual = File(context.filesDir, "modelos/actual")
                val usarActual = File(dirActual, MODELO_ESTATICO).isFile && File(dirActual, ETIQUETAS_ESTATICO).isFile
                val bytes = if (usarActual) File(dirActual, MODELO_ESTATICO).readBytes()
                            else context.assets.open(MODELO_ESTATICO).use { it.readBytes() }
                val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                buffer.put(bytes)
                buffer.rewind()
                val interprete = Interpreter(buffer)
                val entrada = interprete.getInputTensor(0).shape()
                val salida = interprete.getOutputTensor(0).shape()
                val textoEtiquetas = if (usarActual) File(dirActual, ETIQUETAS_ESTATICO).readText()
                                     else context.assets.open(ETIQUETAS_ESTATICO).bufferedReader().use { it.readText() }
                val json = JSONArray(textoEtiquetas)
                val etiquetas = List(json.length()) { json.getString(it) }
                if (entrada.size != 2 || entrada[1] != NUM_FEATURES || salida.size != 2 || salida[1] != etiquetas.size) {
                    Log.e(TAG, "Modelo incompatible: entrada=${entrada.toList()} salida=${salida.toList()} etiquetas=${etiquetas.size}")
                    interprete.close()
                    null
                } else {
                    Log.i(TAG, if (usarActual) "Modelo cargado: actualizado" else "Modelo cargado: base")
                    ModeloEstatico(interprete, etiquetas)
                }
            } catch (e: Throwable) {   // Throwable: también cubre fallos al cargar la librería nativa
                Log.e(TAG, "No se pudo cargar el modelo: ${e.message}")
                null
            }
        }
        estatico
    }

    // Misma normalización que landmark_utils.py del PC: muñeca al origen y escala por muñeca->nudillo medio.
    private fun normalizar(plano: List<Float>): FloatArray? {
        if (plano.size != NUM_FEATURES) return null
        val p = FloatArray(NUM_FEATURES)
        for (i in 0 until NUM_PUNTOS) {
            for (c in 0..2) p[i * 3 + c] = plano[i * 3 + c] - plano[c]
        }
        var escala = norma(p, NUDILLO_MEDIO)
        if (escala < 1e-6f) escala = (0 until NUM_PUNTOS).maxOf { norma(p, it) }
        if (escala < 1e-6f) escala = 1f
        for (i in p.indices) p[i] /= escala
        return p
    }

    private fun norma(p: FloatArray, i: Int): Float {
        val x = p[i * 3]
        val y = p[i * 3 + 1]
        val z = p[i * 3 + 2]
        return sqrt(x * x + y * y + z * z)
    }

    suspend fun predictSign(landmarks: List<Float>): String? = withContext(Dispatchers.Default) {
        if (landmarks.isEmpty()) return@withContext null
        val modelo = cargarEstatico() ?: return@withContext null
        val entrada = normalizar(landmarks) ?: return@withContext null
        val salida = Array(1) { FloatArray(modelo.etiquetas.size) }
        try {
            synchronized(modelo) { modelo.interprete.run(arrayOf(entrada), salida) }
        } catch (e: Exception) {
            Log.e(TAG, "Error al predecir: ${e.message}")
            return@withContext null
        }
        val probs = salida[0]
        var mejor = 0
        for (i in probs.indices) if (probs[i] > probs[mejor]) mejor = i
        ultimaConfianza = probs[mejor]
        if (probs[mejor] < UMBRAL_CONFIANZA) null else modelo.etiquetas[mejor]
    }

    fun close() = synchronized(this) {
        secuencia?.interprete?.close()
        secuencia = null
        secuenciaIntentada = false
        estatico?.interprete?.close()
        estatico = null
    }

    companion object {
        private const val TAG = "ModelRepository"

        @Volatile
        private var generacion = 0

        /** Hace que todos los ModelRepository vuelvan a cargar el modelo en la próxima predicción. */
        fun invalidarModelos() {
            generacion++
        }
        private const val MODELO_ESTATICO = "estatico.tflite"
        private const val ETIQUETAS_ESTATICO = "etiquetas_estatico.json"
        private const val MODELO_SECUENCIA = "dynamic_model.tflite"
        private const val ETIQUETAS_SECUENCIA = "dynamic_labels.json"
        private const val NUM_PUNTOS = 21
        private const val NUM_FEATURES = NUM_PUNTOS * 3
        private const val NUDILLO_MEDIO = 9
        // Nombres del modelo de movimiento dentro del paquete importado
        private const val ARCHIVO_SECUENCIA = "secuencias.tflite"
        private const val ARCHIVO_ETIQUETAS_SECUENCIA = "etiquetas_secuencias.json"
        private const val UMBRAL_CONFIANZA = 0.5f   // ajustable: por debajo no se muestra ninguna letra
    }

    // Carga perezosa del modelo de secuencias (letras con movimiento). Si no está en assets, devuelve null.
    private fun cargarSecuencia(): ModeloSecuencia? = synchronized(this) {
        if (generacionSecuencia != generacion) {   // se importó o quitó un paquete: recargar
            secuencia?.let { m -> synchronized(m) { m.interprete.close() } }
            secuencia = null
            secuenciaIntentada = false
            generacionSecuencia = generacion
        }
        if (!secuenciaIntentada) {
            secuenciaIntentada = true
            secuencia = try {
                val dirActual = File(context.filesDir, "modelos/actual")
                val usarActual = File(dirActual, ARCHIVO_SECUENCIA).isFile &&
                    File(dirActual, ARCHIVO_ETIQUETAS_SECUENCIA).isFile
                val bytes = if (usarActual) File(dirActual, ARCHIVO_SECUENCIA).readBytes()
                            else context.assets.open(MODELO_SECUENCIA).use { it.readBytes() }
                val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                buffer.put(bytes)
                buffer.rewind()
                val interprete = Interpreter(buffer)
                val entrada = interprete.getInputTensor(0).shape()
                val salida = interprete.getOutputTensor(0).shape()
                val textoEtiquetas = if (usarActual) File(dirActual, ARCHIVO_ETIQUETAS_SECUENCIA).readText()
                                     else context.assets.open(ETIQUETAS_SECUENCIA).bufferedReader().use { it.readText() }
                val json = JSONArray(textoEtiquetas)
                val etiquetas = List(json.length()) { json.getString(it) }
                if (entrada.size != 3 || entrada[2] != NUM_FEATURES || salida.size != 2 || salida[1] != etiquetas.size) {
                    Log.e(TAG, "Modelo de secuencias incompatible: entrada=${entrada.toList()} salida=${salida.toList()} etiquetas=${etiquetas.size}")
                    interprete.close()
                    null
                } else {
                    Log.i(TAG, "Modelo de secuencias cargado (${entrada[1]} fotogramas, ${etiquetas.size} letras)")
                    ModeloSecuencia(interprete, etiquetas, entrada[1])
                }
            } catch (e: Throwable) {
                Log.i(TAG, "Sin modelo de secuencias: ${e.message}")
                null
            }
        }
        secuencia
    }

    /** Fotogramas que espera el modelo de secuencias, o null si no hay modelo. */
    suspend fun largoSecuencia(): Int? = withContext(Dispatchers.Default) { cargarSecuencia()?.largo }

    /** True si la letra es una de las que distingue el modelo de secuencias (las dinámicas). */
    suspend fun esDinamica(letra: String): Boolean = withContext(Dispatchers.Default) {
        cargarSecuencia()?.etiquetas?.contains(letra) == true
    }

    /** Recibe exactamente `largo` fotogramas crudos (63 valores cada uno). Si acierta, actualiza ultimaConfianza. */
    suspend fun predictSequence(frames: List<List<Float>>): String? = withContext(Dispatchers.Default) {
        val modelo = cargarSecuencia() ?: return@withContext null
        if (frames.size != modelo.largo) return@withContext null
        val entrada = Array(1) { Array(modelo.largo) { FloatArray(NUM_FEATURES) } }
        for (i in 0 until modelo.largo) {
            entrada[0][i] = normalizar(frames[i]) ?: return@withContext null
        }
        val salida = Array(1) { FloatArray(modelo.etiquetas.size) }
        try {
            synchronized(modelo) { modelo.interprete.run(entrada, salida) }
        } catch (e: Exception) {
            Log.e(TAG, "Error al predecir secuencia: ${e.message}")
            return@withContext null
        }
        val probs = salida[0]
        var mejor = 0
        for (i in probs.indices) if (probs[i] > probs[mejor]) mejor = i
        if (probs[mejor] < UMBRAL_CONFIANZA) return@withContext null
        ultimaConfianza = probs[mejor]
        modelo.etiquetas[mejor]
    }
}
