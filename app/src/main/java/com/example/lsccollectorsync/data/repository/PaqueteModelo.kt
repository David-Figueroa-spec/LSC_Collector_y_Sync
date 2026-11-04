package com.example.lsccollectorsync.data.repository

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class ErrorImport(mensaje: String) : Exception(mensaje)

/** Paquete ya verificado (firma, esquema, versión, hashes y prueba del modelo). */
class PaqueteModelo(
    val version: Long,
    val archivos: Map<String, ByteArray>,
    val manifiesto: ByteArray,
    val firma: ByteArray
)

object PaqueteLector {
    // Clave pública Ed25519 (la privada está solo en el PC que empaqueta).
    private const val CLAVE_PUBLICA = "fzlsxMlvGeATF+uCV1OIEzdaMuQ9eAnv43EPHY7Dfx8="
    const val MODELO = "estatico.tflite"
    const val ETIQUETAS = "etiquetas_estatico.json"
    const val SECUENCIAS = "secuencias.tflite"
    const val ETIQUETAS_SECUENCIAS = "etiquetas_secuencias.json"
    private const val NUM_FEATURES = 63
    private const val MAX_TOTAL = 30L * 1024 * 1024
    private val PERMITIDOS = setOf(
        "manifest.json", "manifest.sig", "estatico.tflite", "etiquetas_estatico.json",
        "secuencias.tflite", "etiquetas_secuencias.json"
    )

    fun leer(context: Context, uri: Uri, versionInstalada: Long): PaqueteModelo {
        val partes = leerZip(context, uri)
        val manifiesto = partes["manifest.json"] ?: throw ErrorImport("Falta manifest.json")
        val firma = partes["manifest.sig"] ?: throw ErrorImport("Falta manifest.sig")
        verificarFirma(manifiesto, firma)

        val m = JSONObject(String(manifiesto, Charsets.UTF_8))
        if (m.optInt("formato") != 1) throw ErrorImport("Formato de paquete no soportado")
        val esquema = m.getJSONObject("esquema")
        if (esquema.optString("normalizacion") != "v1") {
            throw ErrorImport("Este modelo usa una normalización que la app no conoce. Actualiza la app.")
        }
        val entrada = esquema.optJSONArray("entrada_estatico")
        if (entrada == null || entrada.length() != 2 || entrada.getInt(1) != NUM_FEATURES) {
            throw ErrorImport("La forma de entrada del modelo no es compatible")
        }
        val version = m.getLong("version")
        if (version <= versionInstalada) {
            throw ErrorImport("Ya tienes la versión $versionInstalada o una más nueva (el paquete es la $version)")
        }

        val lista = m.getJSONObject("archivos")
        val verificados = HashMap<String, ByteArray>()
        for (nombre in lista.keys()) {
            val datos = partes[nombre] ?: throw ErrorImport("Falta $nombre en el paquete")
            if (sha256(datos) != lista.getJSONObject(nombre).getString("sha256")) {
                throw ErrorImport("El archivo $nombre está dañado o modificado")
            }
            verificados[nombre] = datos
        }
        val tflite = verificados[MODELO] ?: throw ErrorImport("El paquete no incluye $MODELO")
        val etiquetas = verificados[ETIQUETAS] ?: throw ErrorImport("El paquete no incluye $ETIQUETAS")
        probarModelo(tflite, etiquetas)

        // Modelo de letras con movimiento: opcional, pero sus dos archivos van juntos
        val seq = verificados[SECUENCIAS]
        val etiquetasSeq = verificados[ETIQUETAS_SECUENCIAS]
        if ((seq == null) != (etiquetasSeq == null)) {
            throw ErrorImport("El paquete debe traer juntos $SECUENCIAS y $ETIQUETAS_SECUENCIAS")
        }
        if (seq != null && etiquetasSeq != null) probarSecuencias(seq, etiquetasSeq)

        return PaqueteModelo(version, verificados, manifiesto, firma)
    }

    private fun leerZip(context: Context, uri: Uri): Map<String, ByteArray> {
        val resultado = HashMap<String, ByteArray>()
        var total = 0L
        val flujo = context.contentResolver.openInputStream(uri) ?: throw ErrorImport("No se pudo abrir el archivo")
        flujo.use { entrada ->
            ZipInputStream(entrada).use { zip ->
                val buf = ByteArray(8192)
                while (true) {
                    val e = zip.nextEntry ?: break
                    // Los nombres del zip nunca se usan como ruta: solo se aceptan los esperados.
                    if (e.isDirectory || e.name !in PERMITIDOS) continue
                    val out = ByteArrayOutputStream()
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_TOTAL) throw ErrorImport("El paquete es demasiado grande")
                        out.write(buf, 0, n)
                    }
                    resultado[e.name] = out.toByteArray()
                }
            }
        }
        if (resultado.isEmpty()) throw ErrorImport("El archivo no es un paquete de modelo")
        return resultado
    }

    private fun probarSecuencias(tflite: ByteArray, etiquetasJson: ByteArray) {
        val etiquetas = JSONArray(String(etiquetasJson, Charsets.UTF_8))
        val buffer = ByteBuffer.allocateDirect(tflite.size).order(ByteOrder.nativeOrder())
        buffer.put(tflite)
        buffer.rewind()
        val interprete = Interpreter(buffer)
        try {
            val entrada = interprete.getInputTensor(0).shape()
            val salida = interprete.getOutputTensor(0).shape()
            if (entrada.size != 3 || entrada[2] != NUM_FEATURES || entrada[1] < 2) {
                throw ErrorImport("El modelo de secuencias no acepta fotogramas de $NUM_FEATURES valores")
            }
            if (salida.size != 2 || salida[1] != etiquetas.length()) {
                throw ErrorImport("Las etiquetas del modelo de secuencias no coinciden con su salida")
            }
            interprete.run(
                Array(1) { Array(entrada[1]) { FloatArray(NUM_FEATURES) } },
                Array(1) { FloatArray(etiquetas.length()) }
            )
        } finally {
            interprete.close()
        }
    }

    private fun verificarFirma(manifiesto: ByteArray, firma: ByteArray) {
        val clave = Ed25519PublicKeyParameters(Base64.decode(CLAVE_PUBLICA, Base64.DEFAULT), 0)
        val v = Ed25519Signer()
        v.init(false, clave)
        v.update(manifiesto, 0, manifiesto.size)
        if (!v.verifySignature(firma)) throw ErrorImport("Firma no válida: este paquete no lo generaste tú")
    }

    private fun sha256(datos: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(datos).joinToString("") { "%02x".format(it) }

    private fun probarModelo(tflite: ByteArray, etiquetasJson: ByteArray) {
        val etiquetas = JSONArray(String(etiquetasJson, Charsets.UTF_8))
        val buffer = ByteBuffer.allocateDirect(tflite.size).order(ByteOrder.nativeOrder())
        buffer.put(tflite)
        buffer.rewind()
        val interprete = Interpreter(buffer)
        try {
            val entrada = interprete.getInputTensor(0).shape()
            val salida = interprete.getOutputTensor(0).shape()
            if (entrada.size != 2 || entrada[1] != NUM_FEATURES) {
                throw ErrorImport("El modelo no acepta $NUM_FEATURES valores de entrada")
            }
            if (salida.size != 2 || salida[1] != etiquetas.length()) {
                throw ErrorImport("El número de etiquetas no coincide con la salida del modelo")
            }
            interprete.run(arrayOf(FloatArray(NUM_FEATURES)), Array(1) { FloatArray(etiquetas.length()) })
        } finally {
            interprete.close()
        }
    }
}
