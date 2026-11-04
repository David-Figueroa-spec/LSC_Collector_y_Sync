package com.example.lsccollectorsync.data.repository

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Importa un paquete (.zip) de modelo. Si algo falla, el modelo en uso no se toca. */
class ModelImporter(private val context: Context) {

    sealed class Resultado {
        data class Ok(val version: Long) : Resultado()
        data class Error(val mensaje: String) : Resultado()
    }

    private val raiz get() = File(context.filesDir, "modelos")

    /** Versión del modelo instalado; 0 si solo está el modelo base de assets. */
    fun versionInstalada(): Long = try {
        JSONObject(File(raiz, "actual/manifest.json").readText()).getLong("version")
    } catch (e: Exception) {
        0L
    }

    /** Importa en segundo plano y, si sale bien, hace que se recargue el modelo. */
    suspend fun importarAsync(uri: Uri): Resultado {
        val r = withContext(Dispatchers.IO) { importar(uri) }
        if (r is Resultado.Ok) ModelRepository.invalidarModelos()
        return r
    }

    /** Quita las actualizaciones y hace que se recargue el modelo base. */
    suspend fun volverAlBaseAsync() {
        withContext(Dispatchers.IO) {
            File(raiz, "actual").deleteRecursively()
            File(raiz, "anterior").deleteRecursively()
            File(raiz, "tmp").deleteRecursively()
        }
        ModelRepository.invalidarModelos()
    }

    private fun importar(uri: Uri): Resultado = try {
        val paquete = PaqueteLector.leer(context, uri, versionInstalada())
        instalar(paquete)
        Resultado.Ok(paquete.version)
    } catch (e: ErrorImport) {
        Resultado.Error(e.message ?: "Error al importar")
    } catch (e: Throwable) {   // Throwable: también cubre fallos de la librería nativa
        Resultado.Error("No se pudo importar el modelo: ${e.message}")
    }

    private fun instalar(p: PaqueteModelo) {
        val tmp = File(raiz, "tmp")
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) throw ErrorImport("No se pudo crear la carpeta temporal")
        for ((nombre, datos) in p.archivos) File(tmp, nombre).writeBytes(datos)
        File(tmp, "manifest.json").writeBytes(p.manifiesto)
        File(tmp, "manifest.sig").writeBytes(p.firma)

        val actual = File(raiz, "actual")
        val anterior = File(raiz, "anterior")
        anterior.deleteRecursively()
        if (actual.exists() && !actual.renameTo(anterior)) {
            tmp.deleteRecursively()
            throw ErrorImport("No se pudo guardar el respaldo del modelo actual")
        }
        if (!tmp.renameTo(actual)) {
            anterior.renameTo(actual)   // deshace el movimiento
            throw ErrorImport("No se pudo activar el modelo nuevo")
        }
    }
}
