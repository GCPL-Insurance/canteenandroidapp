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
@Database(entities = [Employee::class, PunchEvent::class], version = 3, exportSchema = false)
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

        // FEATURE (Aug-2026): "counts should reset at midnight" — adds dateStamp
        // for reliable daily filtering on the dashboard. Existing rows get an
        // empty dateStamp (can't reliably back-derive their real date from
        // punchTime, whose format differs by source), meaning they simply won't
        // match "today" and won't count toward the daily totals going forward.
        // The only imperfect case is punches from EARLIER THE SAME DAY this
        // update happens to install — those won't count toward today either,
        // since their dateStamp is blank rather than actually today's date. That
        // is a one-time, self-correcting quirk limited to whichever single day
        // the app happens to update on; every day after this is exact.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE punch_events ADD COLUMN dateStamp TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "canteen_db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
        }
    }
}
