package com.example.touchevidence.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "saved_evidence_logs",
    indices = [Index(value = ["created_at"])],
)
data class SavedEvidenceLogEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "window_minutes")
    val windowMinutes: Int,
    @ColumnInfo(name = "event_count")
    val eventCount: Int,
    @ColumnInfo(name = "touch_count")
    val touchCount: Int,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "csv_content")
    val csvContent: String,
)
