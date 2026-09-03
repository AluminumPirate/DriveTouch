package com.example.touchevidence.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "touch_logs",
    indices = [Index(value = ["timestamp"])],
)
data class TouchLogEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "app_label")
    val appLabel: String?,
    @ColumnInfo(name = "event_type")
    val eventType: String,
    @ColumnInfo(name = "input_source")
    val inputSource: String = InputSourceTypes.UnknownLegacy,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long? = null,
)
