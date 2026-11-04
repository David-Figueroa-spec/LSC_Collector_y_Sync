package com.example.lsccollectorsync.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lsccollectorsync.data.repository.LettersRepository
import com.example.lsccollectorsync.data.repository.SampleRepository
import com.example.lsccollectorsync.data.repository.SyncRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class CaptureViewModel(application: Application) : AndroidViewModel(application) {

    private val lettersRepository = LettersRepository(application)
    private val sampleRepository = SampleRepository(application)
    private val syncRepository = SyncRepository(sampleRepository)

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    private var countdownJob: Job? = null
    private var handFirstSeenAt = 0L
    private var lastCaptureTime = 0L

    companion object {
        const val HAND_STABLE_MS = 800L
        const val CAPTURE_INTERVAL_MS = 150L
        const val TARGET_SAMPLES = 30
    }

    init {
        selectLetter(_uiState.value.selectedLetter)
        observeSyncStatus()
    }

    private fun observeSyncStatus() {
        viewModelScope.launch {
            syncRepository.syncStatus.collect { status ->
                _uiState.update { it.copy(syncStatus = status) }
            }
        }
        viewModelScope.launch {
            syncRepository.isSyncing.collect { syncing ->
                _uiState.update { it.copy(isSyncing = syncing) }
            }
        }
    }

    fun getAlphabet(): List<String> = lettersRepository.getAlphabet()

    fun selectLetter(letter: String) {
        if (_uiState.value.recordingState != RecordingState.IDLE) return
        viewModelScope.launch {
            val bitmap = lettersRepository.loadReferenceImage(letter)
            val count = sampleRepository.countForLetter(letter)
            _uiState.update {
                it.copy(
                    selectedLetter = letter,
                    referenceBitmap = bitmap,
                    savedCountForSelectedLetter = count
                )
            }
        }
    }

    fun toggleCameraLens() {
        if (_uiState.value.recordingState != RecordingState.IDLE) return
        val nextFacing = if (_uiState.value.lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        _uiState.update { it.copy(lensFacing = nextFacing, isTorchOn = false) }
    }

    fun toggleTorch(): Boolean {
        val newTorchState = !_uiState.value.isTorchOn
        _uiState.update { it.copy(isTorchOn = newTorchState) }
        return newTorchState
    }

    fun startRecordingFlow() {
        handFirstSeenAt = 0L
        _uiState.update {
            it.copy(recordingState = RecordingState.WAITING_FOR_HAND)
        }
    }

    fun cancelRecording() {
        countdownJob?.cancel()
        _uiState.update {
            it.copy(recordingState = RecordingState.IDLE)
        }
    }

    fun onHandPresenceChanged(handPresent: Boolean, latestLandmarks: List<Float>?) {
        val currentState = _uiState.value.recordingState
        val now = System.currentTimeMillis()

        when (currentState) {
            RecordingState.WAITING_FOR_HAND -> {
                if (handPresent) {
                    if (handFirstSeenAt == 0L) handFirstSeenAt = now
                    if (now - handFirstSeenAt >= HAND_STABLE_MS) {
                        startCountdown()
                    }
                } else {
                    handFirstSeenAt = 0L
                }
            }
            RecordingState.RECORDING -> {
                if (handPresent && latestLandmarks != null) {
                    if (now - lastCaptureTime >= CAPTURE_INTERVAL_MS) {
                        lastCaptureTime = now
                        captureSample(latestLandmarks)
                    }
                }
            }
            else -> {}
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        _uiState.update {
            it.copy(recordingState = RecordingState.COUNTDOWN, countdownValue = 3)
        }
        countdownJob = viewModelScope.launch {
            for (i in 3 downTo 1) {
                _uiState.update { it.copy(countdownValue = i) }
                delay(1000)
            }
            beginActualRecording()
        }
    }

    private fun beginActualRecording() {
        lastCaptureTime = 0L
        _uiState.update {
            it.copy(
                recordingState = RecordingState.RECORDING,
                capturedCount = 0
            )
        }
    }

    private fun captureSample(landmarks: List<Float>) {
        val currentLetter = _uiState.value.selectedLetter
        val timestamp = System.currentTimeMillis()

        viewModelScope.launch {
            sampleRepository.saveSample(currentLetter, landmarks, timestamp)
            val newCaptured = _uiState.value.capturedCount + 1
            val newSavedCount = sampleRepository.countForLetter(currentLetter)

            _uiState.update {
                it.copy(
                    capturedCount = newCaptured,
                    savedCountForSelectedLetter = newSavedCount
                )
            }

            if (newCaptured >= TARGET_SAMPLES) {
                finishRecording()
            }
        }
    }

    private fun finishRecording() {
        _uiState.update {
            it.copy(recordingState = RecordingState.IDLE)
        }
    }

    fun syncWithPc() {
        viewModelScope.launch {
            syncRepository.syncWithPc()
            refreshSavedCount()
        }
    }

    fun exportSamples(onResult: (File?) -> Unit) {
        viewModelScope.launch {
            val file = sampleRepository.exportSamplesToCsv(getApplication())
            onResult(file)
        }
    }

    fun refreshSavedCount() {
        viewModelScope.launch {
            val count = sampleRepository.countForLetter(_uiState.value.selectedLetter)
            _uiState.update { it.copy(savedCountForSelectedLetter = count) }
        }
    }
}
