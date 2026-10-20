package com.example.lsccollectorsync

data class HandSample(
    val letter: String,
    val landmarks: List<Float>, // 21 puntos * (x, y, z) = 63 valores planos
    val timestamp: Long
)

object LscAlphabet {
    val LETTERS = listOf(
        "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
        "N", "Ñ", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z"
    )
}