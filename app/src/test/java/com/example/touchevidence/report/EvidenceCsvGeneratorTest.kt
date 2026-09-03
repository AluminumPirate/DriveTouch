package com.example.touchevidence.report

import com.example.touchevidence.data.InputSourceTypes
import com.example.touchevidence.data.TouchEventTypes
import com.example.touchevidence.data.TouchLogEntry
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceCsvGeneratorTest {
    @Test
    fun generate_emptyLog_includesHeaderAndEmptyEventRow() {
        val csv = EvidenceCsvGenerator.generate(
            logs = emptyList(),
            generatedAt = 1_700_000_000_000,
            locale = Locale.US,
        )

        assertTrue(csv.startsWith("generated_at,window_minutes,event_time,event_type,app_name,package_name,input_source"))
        assertTrue(csv.contains("NO_EVENTS_RECORDED"))
        assertEquals(1, EvidenceCsvGenerator.parse(csv).size)
    }

    @Test
    fun generate_multipleApps_includesReadableAppAndPackageColumns() {
        val generatedAt = 1_700_000_300_000
        val logs = listOf(
            TouchLogEntry(
                timestamp = generatedAt - 240_000,
                packageName = "com.waze",
                appLabel = "Waze",
                eventType = TouchEventTypes.AppSwitch,
            ),
            TouchLogEntry(
                timestamp = generatedAt - 180_000,
                packageName = "com.google.android.apps.maps",
                appLabel = "Maps",
                eventType = TouchEventTypes.ScreenTouchClick,
                inputSource = InputSourceTypes.DirectTouchObserved,
            ),
        )

        val csv = EvidenceCsvGenerator.generate(logs, generatedAt, locale = Locale.US)
        val rows = EvidenceCsvGenerator.parse(csv)

        assertEquals(2, rows.size)
        assertEquals("Maps", rows[0].appName)
        assertEquals(TouchEventTypes.ScreenTouchClick, rows[0].eventType)
        assertEquals(InputSourceTypes.DirectTouchObserved, rows[0].inputSource)
        assertEquals("Waze", rows[1].appName)
        assertEquals("com.waze", rows[1].packageName)
        assertEquals(InputSourceTypes.UnknownLegacy, rows[1].inputSource)
    }

    @Test
    fun generate_escapesCsvValues() {
        val generatedAt = 1_700_000_300_000
        val csv = EvidenceCsvGenerator.generate(
            logs = listOf(
                TouchLogEntry(
                    timestamp = generatedAt - 60_000,
                    packageName = "com.example",
                    appLabel = "Maps, \"Beta\"",
                    eventType = TouchEventTypes.ScreenTouchClick,
                ),
            ),
            generatedAt = generatedAt,
            locale = Locale.US,
        )

        assertTrue(csv.contains("\"Maps, \"\"Beta\"\"\""))
        assertEquals("Maps, \"Beta\"", EvidenceCsvGenerator.parse(csv).single().appName)
    }

    @Test
    fun generate_filtersLogsOutsideSelectedWindow() {
        val generatedAt = 1_700_000_300_000
        val csv = EvidenceCsvGenerator.generate(
            logs = listOf(
                TouchLogEntry(
                    timestamp = generatedAt - 12 * 60_000,
                    packageName = "com.old",
                    appLabel = "Old App",
                    eventType = TouchEventTypes.ScreenTouchClick,
                ),
                TouchLogEntry(
                    timestamp = generatedAt - 4 * 60_000,
                    packageName = "com.current",
                    appLabel = "Current App",
                    eventType = TouchEventTypes.ScreenSwipeScroll,
                ),
            ),
            generatedAt = generatedAt,
            retentionMinutes = 5,
            locale = Locale.US,
        )

        assertTrue(csv.contains("Current App"))
        assertFalse(csv.contains("Old App"))
        assertEquals("5", EvidenceCsvGenerator.parse(csv).single().windowMinutes)
    }

    @Test
    fun parse_legacyCsvWithoutInputSource_usesUnknownLegacy() {
        val csv = """
            generated_at,window_minutes,event_time,event_type,app_name,package_name
            2026-09-03 10:00:00,10,2026-09-03 09:59:00,SCREEN_TOUCH_CLICK,Waze,com.waze
        """.trimIndent()

        assertEquals(InputSourceTypes.UnknownLegacy, EvidenceCsvGenerator.parse(csv).single().inputSource)
    }

    @Test
    fun touchCount_countsOnlySelectedWindowTouchEvents() {
        val generatedAt = 1_700_000_300_000
        val logs = listOf(
            TouchLogEntry(
                timestamp = generatedAt - 6 * 60_000,
                packageName = "com.old",
                appLabel = "Old",
                eventType = TouchEventTypes.ScreenTouchClick,
            ),
            TouchLogEntry(
                timestamp = generatedAt - 4 * 60_000,
                packageName = "com.switch",
                appLabel = "Switch",
                eventType = TouchEventTypes.AppSwitch,
            ),
            TouchLogEntry(
                timestamp = generatedAt - 2 * 60_000,
                packageName = "com.current",
                appLabel = "Current",
                eventType = TouchEventTypes.ScreenSwipeScroll,
            ),
        )

        assertEquals(1, EvidenceCsvGenerator.touchCount(logs, generatedAt, retentionMinutes = 5))
    }
}
