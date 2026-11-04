package com.example.lsccollectorsync.ui.texttosign

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lsccollectorsync.data.repository.LettersRepository
import com.example.lsccollectorsync.data.repository.SampleRepository
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TextToSignViewModel(application: Application) : AndroidViewModel(application) {

    private val lettersRepository = LettersRepository(application)
    private val sampleRepository = SampleRepository(application)

    private val _poses = MutableStateFlow<Map<String, List<FloatArray>>>(emptyMap())
    val poses: StateFlow<Map<String, List<FloatArray>>> = _poses.asStateFlow()

    init {
        viewModelScope.launch {
            _poses.value = withContext(Dispatchers.Default) { cargarPosesDeAssets() }
        }
    }

    /** Lee assets/poses_letras.json (letra -> 20 ángulos) y genera los landmarks por cinemática. */
    private fun aFrame(a: org.json.JSONArray): FloatArray? = when {
        a.length() >= 63 -> FloatArray(63) { a.getDouble(it).toFloat() }
        a.length() >= 20 -> HandKinematics.build(FloatArray(20) { a.getDouble(it).toFloat() })
        else -> null
    }

    private fun cargarPosesDeAssets(): Map<String, List<FloatArray>> {
        return try {
            val texto = getApplication<Application>().assets.open("poses_letras.json")
                .bufferedReader().use { it.readText() }
            val json = org.json.JSONObject(texto)
            val out = HashMap<String, List<FloatArray>>()
            for (letra in json.keys()) {
                val arr = json.getJSONArray(letra)
                if (arr.length() == 0) continue
                val frames = ArrayList<FloatArray>()
                if (arr.optJSONArray(0) != null) { // lista de listas = secuencia
                    for (i in 0 until arr.length()) {
                        val f = arr.optJSONArray(i) ?: continue
                        aFrame(f)?.let { frames.add(it) }
                    }
                } else {                           // lista de números = pose fija
                    aFrame(arr)?.let { frames.add(it) }
                }
                if (frames.isNotEmpty()) out[letra] = normalizarSecuencia(frames)
            }
            out
        } catch (e: Exception) {
            android.util.Log.e("Poses", "poses_letras.json: ${e.message}")
            emptyMap()
        }
    }

    /** Una pose por letra: promedio de las muestras guardadas, cada una normalizada. */
    private suspend fun calcularPoses(): Map<String, FloatArray> {
        val gson = Gson()
        val grupos = HashMap<String, MutableList<FloatArray>>()
        val todas = sampleRepository.getAllSamples()
        android.util.Log.d("Poses", "total muestras en BD: ${todas.size}; letras: ${todas.map { it.letter }.distinct()}")
        for (m in todas) {
            val arr = try {
                gson.fromJson(m.landmarksJson, FloatArray::class.java)
            } catch (e: Exception) {
                null
            }
            if (arr == null || arr.size < 63) continue
            grupos.getOrPut(m.letter) { ArrayList() }.add(normalizarMano(arr))
        }
        android.util.Log.d("Poses", "muestras por letra: " + grupos.mapValues { it.value.size })
        return grupos.mapValues { (_, lista) ->
            val prom = FloatArray(63)
            for (p in lista) for (i in 0 until 63) prom[i] += p[i]
            for (i in 0 until 63) prom[i] /= lista.size
            prom
        }
    }

    private val _uiState = MutableStateFlow(TextToSignUiState())
    val uiState: StateFlow<TextToSignUiState> = _uiState.asStateFlow()

    fun onInputTextChanged(newText: String) {
        val uppercaseText = newText.uppercase()
        _uiState.update { it.copy(inputText = uppercaseText, isLoading = true) }

        viewModelScope.launch {
            val items = uppercaseText.map { char ->
                val letterStr = char.toString()
                val bitmap = if (char.isLetter()) {
                    lettersRepository.loadReferenceImage(letterStr)
                } else null
                SignImageItem(char, bitmap)
            }
            _uiState.update { it.copy(signItems = items, isLoading = false) }
        }
    }
}
