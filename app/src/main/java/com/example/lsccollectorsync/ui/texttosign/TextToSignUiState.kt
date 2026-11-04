package com.example.lsccollectorsync.ui.texttosign

import android.graphics.Bitmap

data class TextToSignUiState(
    val inputText: String = "",
    val signItems: List<SignImageItem> = emptyList(),
    val isLoading: Boolean = false
)

data class SignImageItem(
    val character: Char,
    val bitmap: Bitmap?
)
