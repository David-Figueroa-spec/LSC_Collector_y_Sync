package com.example.lsccollectorsync.ui.signtotext

data class SignToTextUiState(
    val detectedText: String = "",
    val currentPredictedLetter: String? = null,
    val confidence: Float = 0f,
    val progreso: Float = 0f,
    val statusMessage: String = "Cámara lista para detectar señas"
)
