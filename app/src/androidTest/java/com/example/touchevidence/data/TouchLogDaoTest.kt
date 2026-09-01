package com.example.touchevidence.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TouchLogDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: TouchLogDao
    private lateinit var savedDao: SavedEvidenceLogDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.touchLogDao()
        savedDao = db.savedEvidenceLogDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun logsSince_returnsOnlyRowsInsideRangeOrderedAscending() = runBlocking {
        dao.insert(entry(timestamp = 1_000, eventType = TouchEventTypes.ScreenTouchClick))
        dao.insert(entry(timestamp = 3_000, eventType = TouchEventTypes.ScreenSwipeScroll))
        dao.insert(entry(timestamp = 2_000, eventType = TouchEventTypes.AppSwitch))

        val logs = dao.logsSince(2_000)

        assertEquals(listOf(2_000L, 3_000L), logs.map { it.timestamp })
    }

    @Test
    fun pruneOlderThan_deletesExpiredRows() = runBlocking {
        dao.insert(entry(timestamp = 1_000, eventType = TouchEventTypes.ScreenTouchClick))
        dao.insert(entry(timestamp = 2_000, eventType = TouchEventTypes.ScreenSwipeScroll))

        val deleted = dao.pruneOlderThan(2_000)

        assertEquals(1, deleted)
        assertEquals(listOf(2_000L), dao.logsSince(0).map { it.timestamp })
    }

    @Test
    fun pruneOlderThan_supportsFiveTenAndFifteenMinuteWindows() = runBlocking {
        val now = 1_000_000L
        dao.insert(entry(timestamp = now - 16 * 60_000, eventType = TouchEventTypes.ScreenTouchClick))
        dao.insert(entry(timestamp = now - 12 * 60_000, eventType = TouchEventTypes.ScreenTouchClick))
        dao.insert(entry(timestamp = now - 7 * 60_000, eventType = TouchEventTypes.ScreenTouchClick))
        dao.insert(entry(timestamp = now - 3 * 60_000, eventType = TouchEventTypes.ScreenTouchClick))

        dao.pruneOlderThan(now - 15 * 60_000)
        assertEquals(3, dao.totalStoredCount())

        dao.pruneOlderThan(now - 10 * 60_000)
        assertEquals(2, dao.totalStoredCount())

        dao.pruneOlderThan(now - 5 * 60_000)
        assertEquals(1, dao.totalStoredCount())
        assertEquals(now - 3 * 60_000, dao.oldestLog()?.timestamp)
    }

    @Test
    fun touchQueries_countAndReturnOnlyTouchInteractions() = runBlocking {
        dao.insert(entry(timestamp = 1_000, eventType = TouchEventTypes.AppSwitch))
        dao.insert(entry(timestamp = 2_000, eventType = TouchEventTypes.ScreenTouchClick))
        dao.insert(entry(timestamp = 3_000, eventType = TouchEventTypes.ScreenSwipeScroll))

        val count = dao.countTouchEventsSince(0, TouchEventTypes.touchEvents)
        val mostRecentTouch = dao.mostRecentTouch(TouchEventTypes.touchEvents)

        assertEquals(2, count)
        assertEquals(3_000L, mostRecentTouch?.timestamp)
        assertTrue(mostRecentTouch?.eventType in TouchEventTypes.touchEvents)
    }

    @Test
    fun mostRecentTouch_returnsNullWhenOnlyAppSwitchesExist() = runBlocking {
        dao.insert(entry(timestamp = 1_000, eventType = TouchEventTypes.AppSwitch))

        assertNull(dao.mostRecentTouch(TouchEventTypes.touchEvents))
    }

    @Test
    fun oldestLogAndTotalStoredCount_reflectStoredRows() = runBlocking {
        dao.insert(entry(timestamp = 5_000, eventType = TouchEventTypes.ScreenTouchClick))
        dao.insert(entry(timestamp = 4_000, eventType = TouchEventTypes.ScreenSwipeScroll))

        assertEquals(2, dao.totalStoredCount())
        assertEquals(4_000L, dao.oldestLog()?.timestamp)
    }

    @Test
    fun deleteByPackage_removesOnlyMatchingPackageRows() = runBlocking {
        dao.insert(entry(timestamp = 1_000, eventType = TouchEventTypes.ScreenTouchClick).copy(packageName = "com.example.touchevidence"))
        dao.insert(entry(timestamp = 2_000, eventType = TouchEventTypes.ScreenSwipeScroll).copy(packageName = "com.waze"))

        val deleted = dao.deleteByPackage("com.example.touchevidence")

        assertEquals(1, deleted)
        assertEquals(listOf("com.waze"), dao.logsSince(0).map { it.packageName })
    }

    @Test
    fun trimToNewest_keepsOnlyNewestRows() = runBlocking {
        dao.insert(entry(timestamp = 1_000, eventType = TouchEventTypes.ScreenTouchClick))
        dao.insert(entry(timestamp = 2_000, eventType = TouchEventTypes.ScreenSwipeScroll))
        dao.insert(entry(timestamp = 3_000, eventType = TouchEventTypes.AppSwitch))

        val deleted = dao.trimToNewest(2)

        assertEquals(1, deleted)
        assertEquals(listOf(2_000L, 3_000L), dao.logsSince(0).map { it.timestamp })
    }

    @Test
    fun savedEvidenceLogs_insertListFetchAndDelete() = runBlocking {
        val id = savedDao.insert(
            SavedEvidenceLogEntry(
                createdAt = 2_000,
                windowMinutes = 10,
                eventCount = 2,
                touchCount = 1,
                fileName = "drive_touch_20260901_120000_10min.csv",
                csvContent = "generated_at,window_minutes,event_time,event_type,app_name,package_name\n",
            ),
        )

        val saved = savedDao.byId(id)

        assertEquals(id, saved?.id)
        assertEquals(listOf(id), savedDao.all().map { it.id })

        savedDao.delete(requireNotNull(saved))

        assertNull(savedDao.byId(id))
        assertTrue(savedDao.all().isEmpty())
    }

    @Test
    fun deletingSavedEvidenceLog_doesNotDeleteRollingLogs() = runBlocking {
        dao.insert(entry(timestamp = 1_000, eventType = TouchEventTypes.ScreenTouchClick))
        val id = savedDao.insert(
            SavedEvidenceLogEntry(
                createdAt = 2_000,
                windowMinutes = 5,
                eventCount = 1,
                touchCount = 1,
                fileName = "drive_touch_20260901_120000_5min.csv",
                csvContent = "generated_at,window_minutes,event_time,event_type,app_name,package_name\n",
            ),
        )

        savedDao.delete(requireNotNull(savedDao.byId(id)))

        assertEquals(1, dao.totalStoredCount())
        assertTrue(savedDao.all().isEmpty())
    }

    private fun entry(timestamp: Long, eventType: String): TouchLogEntry {
        return TouchLogEntry(
            timestamp = timestamp,
            packageName = "com.waze",
            appLabel = "Waze",
            eventType = eventType,
        )
    }
}
