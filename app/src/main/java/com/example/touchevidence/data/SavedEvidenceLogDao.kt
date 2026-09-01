package com.example.touchevidence.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SavedEvidenceLogDao {
    @Insert
    suspend fun insert(entry: SavedEvidenceLogEntry): Long

    @Query("SELECT * FROM saved_evidence_logs ORDER BY created_at DESC")
    suspend fun all(): List<SavedEvidenceLogEntry>

    @Query("SELECT * FROM saved_evidence_logs WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): SavedEvidenceLogEntry?

    @Delete
    suspend fun delete(entry: SavedEvidenceLogEntry)
}
