package com.example.lsccollectorsync.data.repository

import android.content.Context
import org.json.JSONArray

/** Historial de frases y palabras frecuentes (base fija + las que añade el usuario). */
class FrasesRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("frases", Context.MODE_PRIVATE)

    // ── Historial (la más reciente primero) ──
    @Synchronized
    fun historial(direccion: String): List<String> = leer(clave(direccion))

    @Synchronized
    fun agregarHistorial(direccion: String, frase: String) {
        val limpia = frase.trim()
        if (limpia.isEmpty()) return
        val k = clave(direccion)
        val lista = leer(k).toMutableList()
        lista.removeAll { it.equals(limpia, ignoreCase = true) }
        lista.add(0, limpia)
        guardar(k, lista.take(MAX_HISTORIAL))
    }

    @Synchronized
    fun eliminarDeHistorial(direccion: String, frase: String) {
        val k = clave(direccion)
        guardar(k, leer(k).filter { it != frase })
    }

    @Synchronized
    fun borrarHistorial(direccion: String) = guardar(clave(direccion), emptyList())

    // ── Palabras frecuentes ──
    @Synchronized
    fun frecuentes(): List<String> = BASE + leer(CLAVE_EXTRAS)

    @Synchronized
    fun esPropia(palabra: String): Boolean = leer(CLAVE_EXTRAS).contains(palabra)

    /** Devuelve false si está vacía o ya existe (sin distinguir mayúsculas). */
    @Synchronized
    fun agregarFrecuente(palabra: String): Boolean {
        val limpia = palabra.trim()
        if (limpia.isEmpty() || limpia.length > MAX_PALABRA) return false
        if (frecuentes().any { it.equals(limpia, ignoreCase = true) }) return false
        guardar(CLAVE_EXTRAS, leer(CLAVE_EXTRAS) + limpia)
        return true
    }

    /** Solo se pueden quitar las palabras añadidas por el usuario, no las de la base. */
    @Synchronized
    fun eliminarFrecuente(palabra: String) {
        guardar(CLAVE_EXTRAS, leer(CLAVE_EXTRAS).filter { it != palabra })
    }

    private fun leer(clave: String): List<String> = try {
        val json = JSONArray(prefs.getString(clave, "[]"))
        List(json.length()) { json.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun guardar(clave: String, lista: List<String>) {
        prefs.edit().putString(clave, JSONArray(lista).toString()).apply()
    }

    companion object {
        private const val CLAVE_HISTORIAL = "historial"          // Texto a señas (la clave original)
        private const val CLAVE_HISTORIAL_SENAS = "historial_senas_a_texto"
        const val SENAS_A_TEXTO = "senas_a_texto"
        const val TEXTO_A_SENAS = "texto_a_senas"

        /** Última pantalla de traducción visitada; el Historial muestra la lista de esa dirección. */
        @Volatile
        var direccionActual = TEXTO_A_SENAS

        private fun clave(direccion: String) =
            if (direccion == SENAS_A_TEXTO) CLAVE_HISTORIAL_SENAS else CLAVE_HISTORIAL
        private const val CLAVE_EXTRAS = "frecuentes_extra"
        private const val MAX_HISTORIAL = 100
        private const val MAX_PALABRA = 30

        // Orden del Figma (de izquierda a derecha, fila a fila)
        val BASE = listOf(
            "Hola", "Tardes", "Buenas", "Días", "Noches", "Sí", "No", "Dónde", "Ahí",
            "Prestar", "Derecha", "Lápiz", "Izquierda", "Mesa", "Adelante", "Silla",
            "Atrás", "Cuaderno"
        )
    }
}
