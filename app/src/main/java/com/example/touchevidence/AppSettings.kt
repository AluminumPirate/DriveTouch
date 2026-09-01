package com.example.touchevidence

import android.content.Context

class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun retentionMinutes(): Int {
        return prefs.getInt(KEY_RETENTION_MINUTES, DEFAULT_RETENTION_MINUTES)
            .coerceIn(MIN_RETENTION_MINUTES, MAX_RETENTION_MINUTES)
    }

    fun setRetentionMinutes(minutes: Int) {
        prefs.edit()
            .putInt(KEY_RETENTION_MINUTES, minutes.coerceIn(MIN_RETENTION_MINUTES, MAX_RETENTION_MINUTES))
            .apply()
    }

    fun safePackages(): Set<String> {
        return prefs.getStringSet(KEY_SAFE_PACKAGES, emptySet()).orEmpty()
    }

    fun setSafePackages(packageNames: Set<String>) {
        prefs.edit()
            .putStringSet(KEY_SAFE_PACKAGES, packageNames.filter { it.isNotBlank() }.toSet())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "drive_touch_settings"
        private const val KEY_RETENTION_MINUTES = "retention_minutes"
        private const val KEY_SAFE_PACKAGES = "safe_packages"
    }
}
