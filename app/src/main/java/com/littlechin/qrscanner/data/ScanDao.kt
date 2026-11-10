package com.littlechin.qrscanner.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanDao {
    @Query("SELECT * FROM scans ORDER BY timestamp DESC")
    fun all(): Flow<List<ScanRecord>>

    @Query("SELECT * FROM scans WHERE id = :id")
    suspend fun byId(id: Long): ScanRecord?

    @Query("SELECT * FROM scans WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    suspend fun bySession(sessionId: Long): List<ScanRecord>

    @Insert
    suspend fun insert(record: ScanRecord): Long

    @Delete
    suspend fun delete(record: ScanRecord)

    @Delete
    suspend fun deleteAll(records: List<ScanRecord>)
}
