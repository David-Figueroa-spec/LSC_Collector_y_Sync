package com.example.lsccollectorsync.ui.capture

import android.graphics.Bitmap
import androidx.camera.core.CameraSelector

enum class RecordingState { IDLE, WAITING_FOR_HAND, COUNTDOWN, RECORDING }

data class CaptureUiState(
    val selectedLetter: String = "A",
    val recordingState: RecordingState = RecordingState.IDLE,
    val countdownValue: Int = 0,
    val capturedCount: Int = 0,
    val savedCountForSelectedLetter: Int = 0,
    val referenceBitmap: Bitmap? = null,
    val syncStatus: String = "⚪ Cámara lista (sin PC)",
    val isSyncing: Boolean = false,
    val lensFacing: Int = CameraSelector.LENS_FACING_FRONT,
    val isTorchOn: Boolean = false
)
