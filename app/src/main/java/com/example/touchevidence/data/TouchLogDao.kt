package com.example.touchevidence.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TouchLogDao {
    @Insert
    suspend fun insert(entry: TouchLogEntry)

    @Query("SELECT * FROM touch_logs WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun logsSince(since: Long): List<TouchLogEntry>

    @Query("SELECT * FROM touch_logs ORDER BY timestamp ASC LIMIT 1")
    suspend fun oldestLog(): TouchLogEntry?

    @Query("SELECT COUNT(*) FROM touch_logs")
    suspend fun totalStoredCount(): Int

    @Query(
        """
        SELECT COUNT(*) FROM touch_logs
        WHERE timestamp >= :since
        AND event_type IN (:touchEventTypes)
        """,
    )
    suspend fun countTouchEventsSince(
        since: Long,
        touchEventTypes: List<String>,
    ): Int

    @Query(
        """
        SELECT * FROM touch_logs
        WHERE event_type IN (:touchEventTypes)
        ORDER BY timestamp DESC
        LIMIT 1
        """,
    )
    suspend fun mostRecentTouch(
        touchEventTypes: List<String>,
    ): TouchLogEntry?

    @Query("DELETE FROM touch_logs WHERE timestamp < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long): Int

    @Query("DELETE FROM touch_logs WHERE package_name = :packageName")
    suspend fun deleteByPackage(packageName: String): Int

    @Query(
        """
        DELETE FROM touch_logs
        WHERE id NOT IN (
            SELECT id FROM touch_logs
            ORDER BY timestamp DESC, id DESC
            LIMIT :maxRows
        )
        """,
    )
    suspend fun trimToNewest(maxRows: Int): Int
}
