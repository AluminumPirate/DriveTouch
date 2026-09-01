package com.example.touchevidence

const val MIN_RETENTION_MINUTES = 5
const val DEFAULT_RETENTION_MINUTES = 10
const val MAX_RETENTION_MINUTES = 15
const val MAX_ROLLING_LOG_ROWS = 5_000

fun retentionMinutesToMs(minutes: Int): Long {
    return minutes.coerceIn(MIN_RETENTION_MINUTES, MAX_RETENTION_MINUTES) * 60_000L
}
