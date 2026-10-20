package com.example.lsccollectorsync

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "samples")
data class SampleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val letter: String,
    val landmarksJson: String, // 63 floats (21 puntos * x,y,z) serializados con Gson
    val timestamp: Long,
    val synced: Boolean = false
)