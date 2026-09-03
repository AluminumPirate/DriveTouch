package com.example.touchevidence

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import com.example.touchevidence.data.AppDatabase
import com.example.touchevidence.data.InputSourceTypes
import com.example.touchevidence.data.TouchEventTypes
import com.example.touchevidence.data.TouchLogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TouchLoggerService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: AppDatabase
    private var currentPackage: String = UNKNOWN_PACKAGE
    private var touchInteractionActive: Boolean = false
    private var lastDirectTouchAt: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        db = AppDatabase.getInstance(applicationContext)
        serviceScope.launch {
            pruneRollingLogs(System.currentTimeMillis())
        }
        serviceScope.launch {
            while (true) {
                delay(PRUNE_WATCHDOG_INTERVAL_MS)
                pruneRollingLogs(System.currentTimeMillis())
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val timestamp = System.currentTimeMillis()
        when (event.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                touchInteractionActive = true
                lastDirectTouchAt = timestamp
                return
            }
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                touchInteractionActive = false
                lastDirectTouchAt = timestamp
                return
            }
        }

        val packageName = event.packageName?.toString()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !packageName.isNullOrBlank()) {
            currentPackage = packageName
        }

        val eventType = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> TouchEventTypes.ScreenTouchClick
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> TouchEventTypes.ScreenSwipeScroll
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> TouchEventTypes.AppSwitch
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> TouchEventTypes.ViewFocused
            else -> null
        } ?: return

        val resolvedPackage = packageName ?: currentPackage
        if (resolvedPackage == applicationContext.packageName) return
        val inputSource = if (touchInteractionActive || timestamp - lastDirectTouchAt <= DIRECT_TOUCH_GRACE_MS) {
            InputSourceTypes.DirectTouchObserved
        } else {
            InputSourceTypes.NoDirectTouchObserved
        }

        serviceScope.launch {
            runCatching {
                pruneRollingLogs(timestamp)
                val dao = db.touchLogDao()
                dao.insert(
                    TouchLogEntry(
                        timestamp = timestamp,
                        packageName = resolvedPackage,
                        appLabel = appLabelFor(resolvedPackage),
                        eventType = eventType,
                        inputSource = inputSource,
                        durationMs = null,
                    ),
                )
                pruneRollingLogs(timestamp)
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun appLabelFor(packageName: String): String? {
        if (packageName == UNKNOWN_PACKAGE) return null

        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private suspend fun pruneRollingLogs(now: Long) {
        val dao = db.touchLogDao()
        val selectedCutoff = now - retentionMinutesToMs(AppSettings(applicationContext).retentionMinutes())
        val absoluteCutoff = now - retentionMinutesToMs(MAX_RETENTION_MINUTES)
        dao.pruneOlderThan(maxOf(selectedCutoff, absoluteCutoff))
        dao.deleteByPackage(applicationContext.packageName)
        dao.trimToNewest(MAX_ROLLING_LOG_ROWS)
    }

    companion object {
        private const val UNKNOWN_PACKAGE = "Unknown"
        private const val PRUNE_WATCHDOG_INTERVAL_MS = 30_000L
        private const val DIRECT_TOUCH_GRACE_MS = 1_500L
    }
}
