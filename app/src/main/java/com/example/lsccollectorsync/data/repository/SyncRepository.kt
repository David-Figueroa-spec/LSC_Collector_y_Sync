package com.example.lsccollectorsync.data.repository

import android.util.Log
import com.example.lsccollectorsync.AckMessage
import com.example.lsccollectorsync.SamplePayload
import com.example.lsccollectorsync.SyncMessage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class SyncRepository(private val sampleRepository: SampleRepository) {

    private val httpClient = OkHttpClient()
    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private val repositoryScope = CoroutineScope(Dispatchers.IO)

    private val _syncStatus = MutableStateFlow("⚪ Cámara lista (sin PC)")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    companion object {
        private const val PC_WS_URL = "ws://127.0.0.1:8765"
        private const val TAG = "SyncRepository"
    }

    suspend fun syncWithPc() = withContext(Dispatchers.IO) {
        if (_isSyncing.value) return@withContext
        _isSyncing.value = true
        _syncStatus.value = "⏳ Conectando con el PC..."

        val request = Request.Builder().url(PC_WS_URL).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                _syncStatus.value = "🟢 PC conectado, enviando..."
                sendPendingSamples(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _syncStatus.value = "🔴 Sin PC (offline)"
                _isSyncing.value = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _isSyncing.value = false
            }
        })
    }

    private fun sendPendingSamples(ws: WebSocket) {
        repositoryScope.launch {
            val pending = sampleRepository.getPendingSamples()
            if (pending.isEmpty()) {
                _syncStatus.value = "✅ PC conectado (nada pendiente)"
                _isSyncing.value = false
                ws.close(1000, "Nada que enviar")
                return@launch
            }

            val floatListType = object : TypeToken<List<Float>>() {}.type
            val items = pending.map { sample ->
                val landmarks: List<Float> = gson.fromJson(sample.landmarksJson, floatListType)
                SamplePayload(
                    id = sample.id,
                    letter = sample.letter,
                    landmarks = landmarks,
                    timestamp = sample.timestamp
                )
            }

            val message = SyncMessage(items = items)
            val json = gson.toJson(message)
            ws.send(json)
        }
    }

    private fun handleServerMessage(text: String) {
        try {
            val ack = gson.fromJson(text, AckMessage::class.java)
            if (ack.type == "ack" && ack.ids.isNotEmpty()) {
                repositoryScope.launch {
                    sampleRepository.markSynced(ack.ids)
                    _syncStatus.value = "✅ Sincronizado (${ack.ids.size} enviadas)"
                    _isSyncing.value = false
                    webSocket?.close(1000, "Listo")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando ACK: ${e.message}")
        }
    }
}
