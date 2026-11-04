package com.example.lsccollectorsync.ui.signtotext

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.example.lsccollectorsync.data.repository.HandRepository
import com.example.lsccollectorsync.data.repository.ModelRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SignToTextViewModel(application: Application) : AndroidViewModel(application) {

    val handRepository = HandRepository(application)
    private val modelRepository = ModelRepository(application)

    private val _uiState = MutableStateFlow(SignToTextUiState())
    val uiState: StateFlow<SignToTextUiState> = _uiState.asStateFlow()

    // Se emite cada vez que se escribe una letra sola (el fragmento hace vibrar el móvil)
    private val _letraEscrita = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val letraEscrita: SharedFlow<Unit> = _letraEscrita.asSharedFlow()

    init {
        observeHandResults()
    }

    private fun observeHandResults() {
        viewModelScope.launch {
            handRepository.handResults.collect { handData ->
                val landmarks = handData.flatLandmarks
                if (landmarks != null) {
                    registrarFotograma(landmarks)
                    val prediction = predecir(landmarks)
                    if (prediction != null) {
                        val progreso = procesarEstabilidad(prediction, modelRepository.ultimaConfianza)
                        _uiState.update {
                            it.copy(
                                currentPredictedLetter = prediction,
                                progreso = progreso,
                                statusMessage = "Seña detectada: $prediction (${(modelRepository.ultimaConfianza * 100).toInt()}%)"
                            )
                        }
                    }
                } else {
                    espacioAutomatico()
                    fotogramas.clear()
                    _uiState.update {
                        it.copy(
                            currentPredictedLetter = null,
                            progreso = 0f,
                            statusMessage = "Buscando mano..."
                        )
                    }
                }
            }
        }
    }

    // ── Escritura automática: letra estable con buena confianza durante un tiempo ──
    private val tiempoPrimeraMs = 1500L   // primera letra de una palabra (1,5 s)
    private val tiempoSiguienteMs = 1000L // letras siguientes (1 s)
    private val esperaEspacioMs = 1500L   // sin mano tras escribir una letra = espacio automático
    private var espacioPendiente = false
    private val umbralAuto = 0.80f        // confianza mínima para escribir sola
    private val huecoManoMs = 500L        // sin predicciones más de esto = pausa
    private var letraEstable: String? = null
    private var desdeMs = 0L
    private var ultimaVistaMs = 0L
    private var escrita = false

    // Devuelve el progreso 0..1 de la letra que se está manteniendo
    private fun procesarEstabilidad(letra: String, confianza: Float): Float {
        val ahora = SystemClock.uptimeMillis()
        val hueco = ahora - ultimaVistaMs > huecoManoMs
        ultimaVistaMs = ahora
        if (hueco || letra != letraEstable) {
            letraEstable = letra
            desdeMs = ahora
            escrita = false
            return 0f
        }
        if (confianza < umbralAuto) {
            desdeMs = ahora               // confianza baja: el contador vuelve a empezar
            return 0f
        }
        if (escrita) return 1f            // no repite hasta cambiar de seña o hacer una pausa
        val texto = _uiState.value.detectedText
        val tiempo = if (texto.isEmpty() || texto.endsWith(" ")) tiempoPrimeraMs else tiempoSiguienteMs
        if (ahora - desdeMs >= tiempo) {
            appendLetter(letra)
            _letraEscrita.tryEmit(Unit)
            escrita = true
            espacioPendiente = true
            return 1f
        }
        return ((ahora - desdeMs).toFloat() / tiempo).coerceIn(0f, 1f)
    }

    // Se llama cuando no hay mano: tras escribir una letra, 1,5 s sin mano añaden un espacio
    private fun espacioAutomatico() {
        if (!espacioPendiente) return
        if (SystemClock.uptimeMillis() - ultimaVistaMs >= esperaEspacioMs) {
            addSpace()
            espacioPendiente = false
        }
    }

    // ── Ventana de movimiento para el modelo de secuencias (letras dinámicas) ──
    private val ventanaMs = 900L          // igual que CAPTURE_DURATION_S = 0.9 al grabar
    private val minFotogramas = 12
    private val fotogramas = ArrayDeque<Pair<Long, List<Float>>>()

    private fun registrarFotograma(landmarks: List<Float>) {
        val ahora = SystemClock.uptimeMillis()
        fotogramas.addLast(ahora to landmarks)
        while (fotogramas.isNotEmpty() && ahora - fotogramas.first().first > ventanaMs) fotogramas.removeFirst()
    }

    // Misma regla que predict_live.py: si el modelo de letras da una letra dinámica y hay
    // movimiento suficiente, manda el modelo de secuencias. Si no, se queda la letra estática.
    private suspend fun predecir(landmarks: List<Float>): String? {
        val estatica = modelRepository.predictSign(landmarks) ?: return null
        if (!modelRepository.esDinamica(estatica)) return estatica
        val ventana = ventanaParaSecuencia() ?: return estatica
        return modelRepository.predictSequence(ventana) ?: estatica
    }

    // Remuestrea los fotogramas de la ventana a exactamente `largo`, como record_secuencias.py
    private suspend fun ventanaParaSecuencia(): List<List<Float>>? {
        val largo = modelRepository.largoSecuencia() ?: return null
        if (largo < 2 || fotogramas.size < minFotogramas) return null
        if (fotogramas.last().first - fotogramas.first().first < ventanaMs * 3 / 4) return null
        val n = fotogramas.size
        return List(largo) { i ->
            val idx = Math.round(i * (n - 1).toDouble() / (largo - 1)).toInt()
            fotogramas[idx].second
        }
    }

    fun appendLetter(letter: String) {
        _uiState.update { it.copy(detectedText = it.detectedText + letter) }
    }

    fun addSpace() {
        _uiState.update { it.copy(detectedText = it.detectedText + " ") }
    }

    fun clearText() {
        _uiState.update { it.copy(detectedText = "") }
    }

    fun borrarUltima() {
        espacioPendiente = false
        _uiState.update { it.copy(detectedText = it.detectedText.dropLast(1)) }
    }

    // Sustituye la palabra que se está escribiendo por la sugerencia elegida y deja un espacio
    fun reemplazarPalabra(sugerencia: String) {
        espacioPendiente = false
        _uiState.update {
            val t = it.detectedText
            val base = t.substring(0, t.lastIndexOf(' ') + 1)
            it.copy(detectedText = base + sugerencia + " ")
        }
    }

    override fun onCleared() {
        super.onCleared()
        handRepository.close()
        modelRepository.close()
    }
}
