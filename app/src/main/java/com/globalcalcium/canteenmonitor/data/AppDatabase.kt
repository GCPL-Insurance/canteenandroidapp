package com.globalcalcium.canteenmonitor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * BUGFIX (Aug-2026): this class didn't exist at all before — Employee had a Room
 * @Entity annotation sitting unused, and there was no database, no DAO wiring, no
 * instantiation anywhere in the app. Everything (synced employee data, photo
 * paths, punch history) lived only in memory, which is the entire reason it all
 * disappeared on every app restart. This is the actual persistent SQLite database
 * backing the app going forward — see MainActivity for how it's loaded on startup
 * and written to as data arrives.
 */
@Database(entities = [Employee::class, PunchEvent::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun employeeDao(): EmployeeDao
    abstract fun punchDao(): PunchDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "canteen_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
