package com.globalcalcium.canteenmonitor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * BUGFIX (Aug-2026): this class didn't exist at all before — Employee had a Room
 * @Entity annotation sitting unused, and there was no database, no DAO wiring, no
 * instantiation anywhere in the app. Everything (synced employee data, photo
 * paths, punch history) lived only in memory, which is the entire reason it all
 * disappeared on every app restart. This is the actual persistent SQLite database
 * backing the app going forward — see MainActivity for how it's loaded on startup
 * and written to as data arrives.
 */
@Database(entities = [Employee::class, PunchEvent::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun employeeDao(): EmployeeDao
    abstract fun punchDao(): PunchDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        // FEATURE (Aug-2026): adds isRejected/rejectionReason (for showing failed
        // verification attempts once the device firmware sends them) and source
        // (device vs cloud-synced manual tokens) to punch_events. A real
        // migration, not a destructive fallback — anyone upgrading keeps their
        // existing punch history rather than losing it on the next app update.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE punch_events ADD COLUMN isRejected INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE punch_events ADD COLUMN rejectionReason TEXT")
                db.execSQL("ALTER TABLE punch_events ADD COLUMN source TEXT NOT NULL DEFAULT 'device'")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "canteen_db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
        }
    }
}
