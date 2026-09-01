package com.example.touchevidence

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils

object AccessibilityStatus {
    fun isTouchLoggerEnabled(context: Context): Boolean {
        val expectedService = ComponentName(context, TouchLoggerService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        return splitter.any { it.equals(expectedService, ignoreCase = true) }
    }
}
