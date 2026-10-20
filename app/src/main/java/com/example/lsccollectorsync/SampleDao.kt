package com.example.lsccollectorsync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SampleDao {

    @Insert
    fun insert(sample: SampleEntity): Long

    @Query("SELECT COUNT(*) FROM samples WHERE letter = :letter")
    fun countForLetter(letter: String): Int

    @Query("SELECT * FROM samples WHERE synced = 0")
    fun getPendingSamples(): List<SampleEntity>

    @Query("UPDATE samples SET synced = 1 WHERE id IN (:ids)")
    fun markSynced(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM samples WHERE synced = 0")
    fun countPending(): Int

    @Query("SELECT * FROM samples ORDER BY letter ASC, timestamp ASC")
    fun getAllSamples(): List<SampleEntity>
}