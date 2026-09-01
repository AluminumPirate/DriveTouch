package com.example.touchevidence

import android.content.Context
import com.example.touchevidence.data.AppDatabase
import com.example.touchevidence.data.SavedEvidenceLogEntry
import com.example.touchevidence.report.EvidenceCsvGenerator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EvidenceLogSaver {
    suspend fun save(context: Context): SavedEvidenceLogEntry {
        val appContext = context.applicationContext
        val db = AppDatabase.getInstance(appContext)
        val retentionMinutes = AppSettings(appContext).retentionMinutes()
        val now = System.currentTimeMillis()
        val since = now - retentionMinutesToMs(retentionMinutes)
        val logs = db.touchLogDao()
            .logsSince(since)
            .filterNot { it.packageName == appContext.packageName }
        val csv = EvidenceCsvGenerator.generate(
            logs = logs,
            generatedAt = now,
            retentionMinutes = retentionMinutes,
        )
        val entry = SavedEvidenceLogEntry(
            createdAt = now,
            windowMinutes = retentionMinutes,
            eventCount = logs.size,
            touchCount = EvidenceCsvGenerator.touchCount(logs, now, retentionMinutes),
            fileName = "drive_touch_${fileTimestamp(now)}_${retentionMinutes}min.csv",
            csvContent = csv,
        )
        val id = db.savedEvidenceLogDao().insert(entry)
        return db.savedEvidenceLogDao().byId(id) ?: entry.copy(id = id)
    }

    private fun fileTimestamp(timestamp: Long): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
    }
}
