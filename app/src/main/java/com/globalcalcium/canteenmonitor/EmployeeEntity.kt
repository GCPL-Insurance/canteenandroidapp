package com.globalcalcium.canteenmonitor.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "employees")
data class Employee(
    @PrimaryKey val empId: String,
    val name: String,
    val department: String = "General",
    val privilege: Int = 0,
    val cardNumber: String? = null,
    val photoPath: String? = null
)

/**
 * BUGFIX (Aug-2026): this was a plain data class with no Room annotations at all —
 * punch history lived only in an in-memory list, wiped the moment the app process
 * was killed. Now a real Room entity, persisted to disk. `id` is the actual Room
 * primary key (auto-generated, not something callers need to supply — every
 * existing call site that builds a PunchEvent by name continues to work
 * unchanged, since it defaults to 0 and Room assigns the real value on insert).
 * `serialNo` is kept as-is — it's the meaningful "Token #" shown in the UI, a
 * separate concept from the underlying database row id.
 */
@Entity(tableName = "punch_events")
data class PunchEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serialNo: Int,
    val empId: String,
    val name: String,
    val department: String,
    val mealType: String,
    val punchTime: String,
    val verificationMode: String,
    val photoPath: String?
)
