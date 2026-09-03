package com.example.touchevidence

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.touchevidence.data.AppDatabase
import com.example.touchevidence.data.InputSourceTypes
import com.example.touchevidence.data.SavedEvidenceLogEntry
import com.example.touchevidence.data.TouchEventTypes
import com.example.touchevidence.data.TouchLogEntry
import com.example.touchevidence.report.EvidenceCsvGenerator
import com.example.touchevidence.report.EvidenceCsvRow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val settings = remember { AppSettings(context) }

            DriveTouchTheme {
                DriveTouchApp(settings = settings)
            }
        }
    }
}

private data class DashboardState(
    val refreshedAt: Long = System.currentTimeMillis(),
    val serviceEnabled: Boolean = false,
    val rollingLogs: List<TouchLogEntry> = emptyList(),
    val savedLogs: List<SavedEvidenceLogEntry> = emptyList(),
    val knownApps: List<KnownApp> = emptyList(),
    val touchCount: Int = 0,
    val lastTouch: TouchLogEntry? = null,
    val oldestStoredLog: TouchLogEntry? = null,
    val totalStoredCount: Int = 0,
    val lastPrunedCount: Int = 0,
)

private enum class FilterKind {
    App,
    Event,
    Source,
}

private data class KnownApp(
    val packageName: String,
    val appName: String,
)

@Composable
private fun DriveTouchApp(settings: AppSettings) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()
    var retentionMinutes by remember { mutableStateOf(settings.retentionMinutes()) }
    var state by remember { mutableStateOf(DashboardState()) }
    var selectedLog by remember { mutableStateOf<SavedEvidenceLogEntry?>(null) }
    var deleteCandidate by remember { mutableStateOf<SavedEvidenceLogEntry?>(null) }
    var safeAppsDialogOpen by remember { mutableStateOf(false) }
    var onboardingOpen by remember { mutableStateOf(!settings.hasSeenOnboarding()) }
    var safePackages by remember { mutableStateOf(settings.safePackages()) }
    var saveInProgress by remember { mutableStateOf(false) }

    suspend fun refreshDashboard() {
        val now = System.currentTimeMillis()
        val since = now - retentionMinutesToMs(retentionMinutes)
        val touchDao = db.touchLogDao()
        val savedDao = db.savedEvidenceLogDao()
        state = withContext(Dispatchers.IO) {
            val pruned = touchDao.pruneOlderThan(since)
            val removedSelfRows = touchDao.deleteByPackage(context.packageName)
            DashboardState(
                refreshedAt = now,
                serviceEnabled = AccessibilityStatus.isTouchLoggerEnabled(context),
                rollingLogs = touchDao.logsSince(since),
                savedLogs = savedDao.all(),
                knownApps = knownAppsFrom(touchDao.logsSince(0), savedDao.all()),
                touchCount = touchDao.countTouchEventsSince(since, TouchEventTypes.touchEvents),
                lastTouch = touchDao.mostRecentTouch(TouchEventTypes.touchEvents),
                oldestStoredLog = touchDao.oldestLog(),
                totalStoredCount = touchDao.totalStoredCount(),
                lastPrunedCount = pruned + removedSelfRows,
            )
        }
    }

    fun shareSavedLog(log: SavedEvidenceLogEntry) {
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
                    val csvFile = File(exportDir, log.fileName)
                    csvFile.writeText(log.csvContent)
                    csvFile
                }
            }

            result
                .onSuccess { file ->
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_SUBJECT, "DriveTouch evidence CSV")
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try {
                        context.startActivity(Intent.createChooser(sendIntent, "Share DriveTouch evidence"))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, "No share target is available.", Toast.LENGTH_LONG).show()
                    }
                }
                .onFailure {
                    Toast.makeText(context, "Could not prepare the CSV file.", Toast.LENGTH_LONG).show()
                }
        }
    }

    fun saveEvidenceLog() {
        scope.launch {
            saveInProgress = true
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    EvidenceLogSaver.save(context)
                }
            }
            saveInProgress = false

            result
                .onSuccess { saved ->
                    selectedLog = saved
                    scope.launch { refreshDashboard() }
                }
                .onFailure {
                    Toast.makeText(context, "Could not save the evidence CSV.", Toast.LENGTH_LONG).show()
                }
        }
    }

    LaunchedEffect(retentionMinutes) {
        while (true) {
            refreshDashboard()
            delay(2_000)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        DashboardScreen(
            state = state,
            retentionMinutes = retentionMinutes,
            saveInProgress = saveInProgress,
            modifier = Modifier.padding(innerPadding),
            onRetentionChange = { minutes ->
                val bounded = minutes.coerceIn(MIN_RETENTION_MINUTES, MAX_RETENTION_MINUTES)
                settings.setRetentionMinutes(bounded)
                retentionMinutes = bounded
            },
            onOpenSettings = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            onSaveEvidence = ::saveEvidenceLog,
            onViewLog = { selectedLog = it },
            onShareLog = ::shareSavedLog,
            onDeleteLog = { deleteCandidate = it },
            safePackageCount = safePackages.size,
            onManageSafeApps = { safeAppsDialogOpen = true },
            onShowGuide = { onboardingOpen = true },
        )
    }

    selectedLog?.let { log ->
        SavedLogDialog(
            log = log,
            safePackages = safePackages,
            onDismiss = { selectedLog = null },
            onShare = { shareSavedLog(log) },
            onDelete = { deleteCandidate = log },
        )
    }

    if (safeAppsDialogOpen) {
        SafeAppsDialog(
            knownApps = state.knownApps,
            selectedPackages = safePackages,
            onSelectedPackagesChange = {
                safePackages = it
                settings.setSafePackages(it)
            },
            onDismiss = { safeAppsDialogOpen = false },
        )
    }

    if (onboardingOpen) {
        OnboardingDialog(
            onSkip = {
                settings.setHasSeenOnboarding(true)
                onboardingOpen = false
            },
            onFinish = {
                settings.setHasSeenOnboarding(true)
                onboardingOpen = false
            },
        )
    }

    deleteCandidate?.let { log ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete saved log?") },
            text = {
                Text("This removes the saved CSV from the app. It cannot be edited or recovered here.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                db.savedEvidenceLogDao().delete(log)
                            }
                            if (selectedLog?.id == log.id) {
                                selectedLog = null
                            }
                            deleteCandidate = null
                            refreshDashboard()
                        }
                    },
                    modifier = Modifier.compactButton(),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteCandidate = null },
                    modifier = Modifier.compactButton(),
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun DashboardScreen(
    state: DashboardState,
    retentionMinutes: Int,
    saveInProgress: Boolean,
    modifier: Modifier = Modifier,
    onRetentionChange: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onSaveEvidence: () -> Unit,
    onViewLog: (SavedEvidenceLogEntry) -> Unit,
    onShareLog: (SavedEvidenceLogEntry) -> Unit,
    onDeleteLog: (SavedEvidenceLogEntry) -> Unit,
    safePackageCount: Int,
    onManageSafeApps: () -> Unit,
    onShowGuide: () -> Unit,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "DriveTouch",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Save a CSV snapshot of recent accessibility interaction events.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onShowGuide,
                    modifier = Modifier.compactButton(),
                ) {
                    Text("Guide")
                }
            }
        }

        item {
            StatusPanel(
                enabled = state.serviceEnabled,
                lastTouch = state.lastTouch,
                touchCount = state.touchCount,
                retentionMinutes = retentionMinutes,
                refreshedAt = state.refreshedAt,
                onOpenSettings = onOpenSettings,
            )
        }

        item {
            EvidencePeriodPanel(
                retentionMinutes = retentionMinutes,
                onRetentionChange = onRetentionChange,
            )
        }

        item {
            SavePanel(
                selectedCount = state.rollingLogs.size,
                retentionMinutes = retentionMinutes,
                saveInProgress = saveInProgress,
                onSaveEvidence = onSaveEvidence,
            )
        }

        item {
            SafeAppsPanel(
                safePackageCount = safePackageCount,
                knownAppCount = state.knownApps.size,
                onManageSafeApps = onManageSafeApps,
            )
        }

        item {
            RetentionCheck(state = state, retentionMinutes = retentionMinutes)
        }

        item {
            Text(
                text = "Saved Evidence CSVs",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (state.savedLogs.isEmpty()) {
            item { EmptySavedLogs() }
        } else {
            items(state.savedLogs, key = { it.id }) { log ->
                SavedLogRow(
                    log = log,
                    onView = { onViewLog(log) },
                    onShare = { onShareLog(log) },
                    onDelete = { onDeleteLog(log) },
                )
            }
        }

        item {
            Text(
                text = "Latest Rolling Events",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (state.rollingLogs.isEmpty()) {
            item { EmptyRollingLogs(retentionMinutes) }
        } else {
            items(state.rollingLogs.takeLast(10).reversed()) { entry ->
                RollingLogRow(entry)
            }
        }
    }
}

private data class OnboardingStep(
    val title: String,
    val body: String,
    val focus: GuideFocus,
)

private enum class GuideFocus {
    Service,
    Period,
    Save,
    Review,
}

private val onboardingSteps = listOf(
    OnboardingStep(
        title = "Turn on recording",
        body = "Enable the accessibility service once. Android requires this in Settings, and DriveTouch cannot switch it on silently.",
        focus = GuideFocus.Service,
    ),
    OnboardingStep(
        title = "Choose the evidence period",
        body = "This is the recent history kept ready for a snapshot. The rolling database is pruned aggressively so it does not grow forever.",
        focus = GuideFocus.Period,
    ),
    OnboardingStep(
        title = "Save when it matters",
        body = "Use Save Evidence CSV in the app, or add the Save DriveTouch Quick Settings tile for a silent one-tap save.",
        focus = GuideFocus.Save,
    ),
    OnboardingStep(
        title = "Read the saved CSV",
        body = "Saved logs are separate snapshots. You can view, filter, highlight safe apps, share, or delete them. They cannot be edited here.",
        focus = GuideFocus.Review,
    ),
)

@Composable
private fun OnboardingDialog(
    onSkip: () -> Unit,
    onFinish: () -> Unit,
) {
    var stepIndex by remember { mutableStateOf(0) }
    val step = onboardingSteps[stepIndex]
    val isLastStep = stepIndex == onboardingSteps.lastIndex

    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(step.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                GuidePreview(focus = step.focus)
                Text(
                    text = step.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    onboardingSteps.forEachIndexed { index, _ ->
                        Box(
                            modifier = Modifier
                                .size(width = if (index == stepIndex) 22.dp else 8.dp, height = 8.dp)
                                .background(
                                    color = if (index == stepIndex) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    shape = MaterialTheme.shapes.small,
                                ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isLastStep) {
                        onFinish()
                    } else {
                        stepIndex++
                    }
                },
                modifier = Modifier.compactButton(),
            ) {
                Text(if (isLastStep) "Done" else "Next")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (stepIndex > 0) {
                    TextButton(
                        onClick = { stepIndex-- },
                        modifier = Modifier.compactButton(),
                    ) {
                        Text("Back")
                    }
                }
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.compactButton(),
                ) {
                    Text("Skip")
                }
            }
        },
    )
}

@Composable
private fun GuidePreview(focus: GuideFocus) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GuidePreviewRow(
                label = "Accessibility service",
                value = "Active / Disabled",
                isFocused = focus == GuideFocus.Service,
            )
            GuidePreviewRow(
                label = "Evidence period",
                value = "5 / 10 / 15 min",
                isFocused = focus == GuideFocus.Period,
            )
            GuidePreviewRow(
                label = "Save Evidence CSV",
                value = "Snapshot now",
                isFocused = focus == GuideFocus.Save,
            )
            GuidePreviewRow(
                label = "Saved Evidence CSVs",
                value = "View, filter, share, delete",
                isFocused = focus == GuideFocus.Review,
            )
        }
    }
}

@Composable
private fun GuidePreviewRow(label: String, value: String, isFocused: Boolean) {
    Surface(
        color = if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusPanel(
    enabled: Boolean,
    lastTouch: TouchLogEntry?,
    touchCount: Int,
    retentionMinutes: Int,
    refreshedAt: Long,
    onOpenSettings: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatusDot(enabled)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Accessibility service", style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (enabled) "Active" else "Disabled",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.compactButton(),
                ) {
                    Text("Settings")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MetricCard(
                    label = "Last touch",
                    value = lastTouch?.let { ageText(refreshedAt - it.timestamp) } ?: "None",
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    label = "Touches in $retentionMinutes min",
                    value = touchCount.toString(),
                    modifier = Modifier.weight(1f),
                )
            }

            if (!enabled) {
                Text(
                    text = "Turn this on in Android Accessibility Settings before driving. Android records accessibility events, not raw touch coordinates.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EvidencePeriodPanel(retentionMinutes: Int, onRetentionChange: (Int) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Evidence period", style = MaterialTheme.typography.labelLarge)
            Text(
                text = "DriveTouch keeps this much recent history ready. Saving creates a separate CSV snapshot that stays until you delete it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15).forEach { minutes ->
                    if (minutes == retentionMinutes) {
                        Button(
                            onClick = { onRetentionChange(minutes) },
                            modifier = Modifier.compactButton(),
                        ) {
                            Text("$minutes min")
                        }
                    } else {
                        TextButton(
                            onClick = { onRetentionChange(minutes) },
                            modifier = Modifier.compactButton(),
                        ) {
                            Text("$minutes min")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavePanel(
    selectedCount: Int,
    retentionMinutes: Int,
    saveInProgress: Boolean,
    onSaveEvidence: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "$selectedCount events ready for a $retentionMinutes minute CSV",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onSaveEvidence,
                enabled = !saveInProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
            ) {
                Text(if (saveInProgress) "Saving..." else "Save Evidence CSV")
            }
        }
    }
}

@Composable
private fun RetentionCheck(state: DashboardState, retentionMinutes: Int) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Oldest rolling event: ${state.oldestStoredLog?.let { timeFormat.format(Date(it.timestamp)) } ?: "None"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Evidence period: $retentionMinutes min | Rolling rows: ${state.totalStoredCount} | Pruned now: ${state.lastPrunedCount}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SafeAppsPanel(
    safePackageCount: Int,
    knownAppCount: Int,
    onManageSafeApps: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Safe apps",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "$safePackageCount selected from $knownAppCount seen apps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onManageSafeApps,
                modifier = Modifier.compactButton(),
            ) {
                Text("Manage")
            }
        }
    }
}

@Composable
private fun SavedLogRow(
    log: SavedEvidenceLogEntry,
    onView: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = dateFormat.format(Date(log.createdAt)),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${log.windowMinutes} min | ${log.eventCount} events | ${log.touchCount} touches",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onView, modifier = Modifier.compactButton()) {
                    Text("View")
                }
                TextButton(onClick = onShare, modifier = Modifier.compactButton()) {
                    Text("Share")
                }
                TextButton(onClick = onDelete, modifier = Modifier.compactButton()) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun RollingLogRow(entry: TouchLogEntry) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = timeFormat.format(Date(entry.timestamp)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(72.dp),
                )
                Text(
                    text = entry.eventType,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = entry.appLabel?.takeIf { it.isNotBlank() } ?: "Unknown app",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = entry.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptySavedLogs() {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "No saved evidence CSVs yet. Tap Save Evidence CSV to freeze the current period.",
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyRollingLogs(retentionMinutes: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "No accessibility interaction events recorded in the last $retentionMinutes minutes.",
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SavedLogDialog(
    log: SavedEvidenceLogEntry,
    safePackages: Set<String>,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val rows = remember(log.csvContent) { EvidenceCsvGenerator.parse(log.csvContent) }
    val appOptions = remember(rows) {
        rows
            .map { it.appName.ifBlank { "Unknown" } }
            .distinct()
            .sorted()
    }
    val eventOptions = remember(rows) {
        rows
            .map { it.eventType }
            .distinct()
            .sorted()
    }
    val sourceOptions = remember(rows) {
        rows
            .map { it.inputSource.ifBlank { InputSourceTypes.UnknownLegacy } }
            .distinct()
            .sorted()
    }
    var includedApps by remember(log.id, appOptions) { mutableStateOf(appOptions.toSet()) }
    var includedEvents by remember(log.id, eventOptions) { mutableStateOf(eventOptions.toSet()) }
    var includedSources by remember(log.id, sourceOptions) { mutableStateOf(sourceOptions.toSet()) }
    var activeFilter by remember(log.id) { mutableStateOf<FilterKind?>(null) }
    val filteredRows = remember(rows, includedApps, includedEvents, includedSources) {
        rows.filter { row ->
            row.appName.ifBlank { "Unknown" } in includedApps &&
                row.eventType in includedEvents &&
                row.inputSource.ifBlank { InputSourceTypes.UnknownLegacy } in includedSources
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saved Evidence CSV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "${log.windowMinutes} min | ${log.eventCount} events | ${log.fileName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { activeFilter = FilterKind.App },
                        modifier = Modifier.compactButton(),
                    ) {
                        Text(filterLabel("Apps", includedApps.size, appOptions.size))
                    }
                    OutlinedButton(
                        onClick = { activeFilter = FilterKind.Event },
                        modifier = Modifier.compactButton(),
                    ) {
                        Text(filterLabel("Events", includedEvents.size, eventOptions.size))
                    }
                    OutlinedButton(
                        onClick = { activeFilter = FilterKind.Source },
                        modifier = Modifier.compactButton(),
                    ) {
                        Text(filterLabel("Touch", includedSources.size, sourceOptions.size))
                    }
                }
                Text(
                    text = "${filteredRows.size} rows shown, newest first",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CsvTable(rows = filteredRows, safePackages = safePackages)
            }
        },
        confirmButton = {
            Button(onClick = onShare, modifier = Modifier.compactButton()) {
                Text("Share")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDelete, modifier = Modifier.compactButton()) {
                    Text("Delete")
                }
                TextButton(onClick = onDismiss, modifier = Modifier.compactButton()) {
                    Text("Close")
                }
            }
        },
    )

    activeFilter?.let { kind ->
        val isAppFilter = kind == FilterKind.App
        val isEventFilter = kind == FilterKind.Event
        MultiSelectFilterDialog(
            title = when (kind) {
                FilterKind.App -> "Show apps"
                FilterKind.Event -> "Show events"
                FilterKind.Source -> "Show touch source"
            },
            options = when (kind) {
                FilterKind.App -> appOptions
                FilterKind.Event -> eventOptions
                FilterKind.Source -> sourceOptions
            },
            selected = when (kind) {
                FilterKind.App -> includedApps
                FilterKind.Event -> includedEvents
                FilterKind.Source -> includedSources
            },
            displayName = { option ->
                when (kind) {
                    FilterKind.App -> option
                    FilterKind.Event -> eventLabel(option)
                    FilterKind.Source -> inputSourceLabel(option)
                }
            },
            onSelectedChange = { selected ->
                if (isAppFilter) {
                    includedApps = selected
                } else if (isEventFilter) {
                    includedEvents = selected
                } else {
                    includedSources = selected
                }
            },
            onDismiss = { activeFilter = null },
        )
    }
}

@Composable
private fun SafeAppsDialog(
    knownApps: List<KnownApp>,
    selectedPackages: Set<String>,
    onSelectedPackagesChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    MultiSelectFilterDialog(
        title = "Safe apps",
        options = knownApps.map { it.packageName },
        selected = selectedPackages,
        displayName = { packageName ->
            val app = knownApps.firstOrNull { it.packageName == packageName }
            app?.let { "${it.appName}  |  ${it.packageName}" } ?: packageName
        },
        onSelectedChange = onSelectedPackagesChange,
        onDismiss = onDismiss,
    )
}

@Composable
private fun MultiSelectFilterDialog(
    title: String,
    options: List<String>,
    selected: Set<String>,
    displayName: (String) -> String,
    onSelectedChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { onSelectedChange(options.toSet()) },
                        modifier = Modifier.compactButton(),
                    ) {
                        Text("All")
                    }
                    TextButton(
                        onClick = { onSelectedChange(emptySet()) },
                        modifier = Modifier.compactButton(),
                    ) {
                        Text("None")
                    }
                }
                options.forEach { option ->
                    val checked = option in selected
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { shouldInclude ->
                                onSelectedChange(
                                    if (shouldInclude) selected + option else selected - option,
                                )
                            },
                        )
                        Text(
                            text = displayName(option),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, modifier = Modifier.compactButton()) {
                Text("Done")
            }
        },
    )
}

@Composable
private fun CsvTable(rows: List<EvidenceCsvRow>, safePackages: Set<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { row ->
            EvidenceRow(row, isSafeApp = row.packageName in safePackages)
        }
    }
}

@Composable
private fun EvidenceRow(row: EvidenceCsvRow, isSafeApp: Boolean) {
    Surface(
        color = if (isSafeApp) safeAppColor() else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = row.eventTime.ifBlank { row.generatedAt },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(138.dp),
                )
                Text(
                    text = eventLabel(row.eventType),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = row.appName.ifBlank { "Unknown app" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (isSafeApp) {
                Text(
                    text = "Safe app",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = inputSourceLabel(row.inputSource),
                style = MaterialTheme.typography.labelSmall,
                color = inputSourceColor(row.inputSource),
            )
            if (row.packageName.isNotBlank()) {
                Text(
                    text = row.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusDot(enabled: Boolean) {
    Box(
        modifier = Modifier
            .width(14.dp)
            .height(14.dp)
            .background(
                color = if (enabled) Color(0xFF1D8F65) else Color(0xFFB44343),
                shape = MaterialTheme.shapes.small,
            ),
    )
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DriveTouchTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF71D6C1),
            secondary = Color(0xFF9ABBC7),
            tertiary = Color(0xFFE0A078),
            background = Color(0xFF101418),
            surface = Color(0xFF182026),
            surfaceVariant = Color(0xFF222B32),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF124559),
            secondary = Color(0xFF36C2A1),
            tertiary = Color(0xFFC26D3D),
            background = Color(0xFFF7F8FA),
            surface = Color.White,
            surfaceVariant = Color(0xFFF0F3F6),
        )
    }

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}

private fun ageText(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1_000L).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return when {
        minutes > 0 -> "${minutes}m ${seconds.toString().padStart(2, '0')}s ago"
        else -> "${seconds}s ago"
    }
}

private fun filterLabel(name: String, selectedCount: Int, totalCount: Int): String {
    return if (selectedCount == totalCount) {
        "$name: All"
    } else {
        "$name: $selectedCount/$totalCount"
    }
}

private fun eventLabel(eventType: String): String {
    return when (eventType) {
        TouchEventTypes.ScreenTouchClick -> "Screen click"
        TouchEventTypes.ScreenSwipeScroll -> "Scroll"
        TouchEventTypes.AppSwitch -> "App switch"
        TouchEventTypes.ViewFocused -> "View focused"
        "NO_EVENTS_RECORDED" -> "No events recorded"
        else -> eventType
    }
}

private fun inputSourceLabel(inputSource: String): String {
    return when (inputSource) {
        InputSourceTypes.DirectTouchObserved -> "Direct touch observed"
        InputSourceTypes.NoDirectTouchObserved -> "No direct touch observed"
        InputSourceTypes.UnknownLegacy -> "Unknown legacy source"
        else -> inputSource
    }
}

private fun knownAppsFrom(
    rollingLogs: List<TouchLogEntry>,
    savedLogs: List<SavedEvidenceLogEntry>,
): List<KnownApp> {
    val fromRolling = rollingLogs.mapNotNull { log ->
        log.packageName
            .takeIf { it.isNotBlank() }
            ?.let { KnownApp(packageName = it, appName = log.appLabel?.takeIf { label -> label.isNotBlank() } ?: it) }
    }
    val fromSaved = savedLogs.flatMap { saved ->
        EvidenceCsvGenerator.parse(saved.csvContent).mapNotNull { row ->
            row.packageName
                .takeIf { it.isNotBlank() }
                ?.let { KnownApp(packageName = it, appName = row.appName.ifBlank { it }) }
        }
    }
    return (fromRolling + fromSaved)
        .distinctBy { it.packageName }
        .sortedBy { it.appName.lowercase(Locale.getDefault()) }
}

@Composable
private fun safeAppColor(): Color {
    return if (isSystemInDarkTheme()) Color(0xFF193D34) else Color(0xFFE7F5EF)
}

@Composable
private fun inputSourceColor(inputSource: String): Color {
    return when (inputSource) {
        InputSourceTypes.DirectTouchObserved -> MaterialTheme.colorScheme.primary
        InputSourceTypes.NoDirectTouchObserved -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun Modifier.compactButton(): Modifier {
    return defaultMinSize(minWidth = 1.dp, minHeight = 1.dp).height(36.dp)
}
