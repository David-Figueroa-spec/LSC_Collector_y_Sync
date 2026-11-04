package com.example.lsccollectorsync.data.repository

import android.content.Context
import com.example.lsccollectorsync.AppDatabase
import com.example.lsccollectorsync.SampleDao
import com.example.lsccollectorsync.SampleEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SampleRepository(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao: SampleDao = db.sampleDao()
    private val gson = Gson()

    suspend fun saveSample(letter: String, landmarks: List<Float>, timestamp: Long): Long = withContext(Dispatchers.IO) {
        val json = gson.toJson(landmarks)
        dao.insert(
            SampleEntity(
                letter = letter,
                landmarksJson = json,
                timestamp = timestamp,
                synced = false
            )
        )
    }

    suspend fun countForLetter(letter: String): Int = withContext(Dispatchers.IO) {
        dao.countForLetter(letter)
    }

    suspend fun getPendingSamples(): List<SampleEntity> = withContext(Dispatchers.IO) {
        dao.getPendingSamples()
    }

    suspend fun markSynced(ids: List<Long>) = withContext(Dispatchers.IO) {
        dao.markSynced(ids)
    }

    suspend fun getAllSamples(): List<SampleEntity> = withContext(Dispatchers.IO) {
        dao.getAllSamples()
    }

    suspend fun exportSamplesToCsv(context: Context): File? = withContext(Dispatchers.IO) {
        val samples = dao.getAllSamples()
        if (samples.isEmpty()) return@withContext null

        val floatListType = object : TypeToken<List<Float>>() {}.type
        val numPoints = samples.asSequence()
            .mapNotNull { runCatching { gson.fromJson<List<Float>>(it.landmarksJson, floatListType) }.getOrNull() }
            .firstOrNull()
            ?.let { it.size / 3 } ?: 21

        val sb = StringBuilder()
        sb.append("letra,timestamp,sincronizado")
        for (i in 0 until numPoints) {
            sb.append(",p${i}_x,p${i}_y,p${i}_z")
        }
        sb.append('\n')

        for (sample in samples) {
            val landmarks: List<Float> = runCatching {
                gson.fromJson<List<Float>>(sample.landmarksJson, floatListType)
            }.getOrDefault(emptyList())

            sb.append(sample.letter)
            sb.append(',')
            sb.append(sample.timestamp)
            sb.append(',')
            sb.append(if (sample.synced) "1" else "0")
            for (value in landmarks) {
                sb.append(',')
                sb.append(value)
            }
            sb.append('\n')
        }

        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val fileName = "lsc_muestras_${System.currentTimeMillis()}.csv"
        File(exportDir, fileName).apply { writeText(sb.toString()) }
    }
}
