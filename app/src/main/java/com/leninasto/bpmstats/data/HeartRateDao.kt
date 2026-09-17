package com.leninasto.bpmstats.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HeartRateDao {
    @Insert
    suspend fun insert(entry: HeartRateEntry)

    @Query("SELECT * FROM heart_rate_entries ORDER BY timestamp DESC")
    fun getAllEntries(): Flow<List<HeartRateEntry>>

    @Query("SELECT * FROM heart_rate_entries WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp ASC")
    fun getEntriesBetween(startTime: Long, endTime: Long): Flow<List<HeartRateEntry>>

    @Query("SELECT * FROM heart_rate_entries WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    fun getEntriesSince(startTime: Long): Flow<List<HeartRateEntry>>

    @Query("DELETE FROM heart_rate_entries")
    suspend fun deleteAll()
}
