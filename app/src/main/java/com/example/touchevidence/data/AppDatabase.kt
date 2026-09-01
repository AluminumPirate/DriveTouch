package com.example.touchevidence.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TouchLogEntry::class, SavedEvidenceLogEntry::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun touchLogDao(): TouchLogDao
    abstract fun savedEvidenceLogDao(): SavedEvidenceLogDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_evidence_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `created_at` INTEGER NOT NULL,
                        `window_minutes` INTEGER NOT NULL,
                        `event_count` INTEGER NOT NULL,
                        `touch_count` INTEGER NOT NULL,
                        `file_name` TEXT NOT NULL,
                        `csv_content` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_evidence_logs_created_at` ON `saved_evidence_logs` (`created_at`)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "drive_touch_verifier.db",
                ).addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
