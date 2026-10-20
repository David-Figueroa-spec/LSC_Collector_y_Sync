package com.example.lsccollectorsync

data class SamplePayload(
    val id: Long,
    val letter: String,
    val landmarks: List<Float>,
    val timestamp: Long
)

data class SyncMessage(
    val type: String = "samples",
    val items: List<SamplePayload>
)

data class AckMessage(
    val type: String,
    val ids: List<Long>
)