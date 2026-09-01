package com.example.touchevidence.report

import com.example.touchevidence.DEFAULT_RETENTION_MINUTES
import com.example.touchevidence.data.TouchEventTypes
import com.example.touchevidence.data.TouchLogEntry
import com.example.touchevidence.retentionMinutesToMs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class EvidenceCsvRow(
    val generatedAt: String,
    val windowMinutes: String,
    val eventTime: String,
    val eventType: String,
    val appName: String,
    val packageName: String,
)

object EvidenceCsvGenerator {
    private val header = listOf(
        "generated_at",
        "window_minutes",
        "event_time",
        "event_type",
        "app_name",
        "package_name",
    )

    fun generate(
        logs: List<TouchLogEntry>,
        generatedAt: Long = System.currentTimeMillis(),
        retentionMinutes: Int = DEFAULT_RETENTION_MINUTES,
        locale: Locale = Locale.getDefault(),
    ): String {
        val cutoff = generatedAt - retentionMinutesToMs(retentionMinutes)
        val sortedLogs = logs.filter { it.timestamp >= cutoff }.sortedByDescending { it.timestamp }
        val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", locale)
        val generated = dateTimeFormat.format(Date(generatedAt))

        return buildString {
            appendCsvRow(header)
            if (sortedLogs.isEmpty()) {
                appendCsvRow(
                    listOf(
                        generated,
                        retentionMinutes.toString(),
                        "",
                        "NO_EVENTS_RECORDED",
                        "",
                        "",
                    ),
                )
            } else {
                sortedLogs.forEach { entry ->
                    appendCsvRow(
                        listOf(
                            generated,
                            retentionMinutes.toString(),
                            dateTimeFormat.format(Date(entry.timestamp)),
                            entry.eventType,
                            entry.appLabel?.takeIf { it.isNotBlank() } ?: "Unknown",
                            entry.packageName,
                        ),
                    )
                }
            }
        }
    }

    fun parse(csv: String): List<EvidenceCsvRow> {
        return parseRows(csv)
            .drop(1)
            .filter { it.size >= header.size }
            .map { row ->
                EvidenceCsvRow(
                    generatedAt = row[0],
                    windowMinutes = row[1],
                    eventTime = row[2],
                    eventType = row[3],
                    appName = row[4],
                    packageName = row[5],
                )
            }
    }

    fun touchCount(logs: List<TouchLogEntry>, generatedAt: Long, retentionMinutes: Int): Int {
        val cutoff = generatedAt - retentionMinutesToMs(retentionMinutes)
        return logs.count { it.timestamp >= cutoff && it.eventType in TouchEventTypes.touchEvents }
    }

    private fun StringBuilder.appendCsvRow(values: List<String>) {
        append(values.joinToString(",") { escape(it) })
        append('\n')
    }

    private fun escape(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }

    private fun parseRows(csv: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val currentCell = StringBuilder()
        var inQuotes = false
        var index = 0

        while (index < csv.length) {
            val char = csv[index]
            when {
                char == '"' && inQuotes && index + 1 < csv.length && csv[index + 1] == '"' -> {
                    currentCell.append('"')
                    index++
                }
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    currentRow += currentCell.toString()
                    currentCell.clear()
                }
                (char == '\n' || char == '\r') && !inQuotes -> {
                    if (char == '\r' && index + 1 < csv.length && csv[index + 1] == '\n') {
                        index++
                    }
                    currentRow += currentCell.toString()
                    currentCell.clear()
                    if (currentRow.any { it.isNotEmpty() }) {
                        rows += currentRow.toList()
                    }
                    currentRow.clear()
                }
                else -> currentCell.append(char)
            }
            index++
        }

        if (currentCell.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow += currentCell.toString()
            rows += currentRow.toList()
        }

        return rows
    }
}
