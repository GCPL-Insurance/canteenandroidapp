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
 *
 * FEATURE (Aug-2026): isRejected/rejectionReason — for showing failed
 * verification attempts (red popup) once the device firmware actually sends
 * them; source distinguishes a punch scanned by this device from one synced
 * down from the cloud (e.g. a manual vendor token issued from the admin
 * portal) — both are real defaults so this doesn't require a destructive
 * migration for anyone upgrading from before these fields existed.
 *
 * FEATURE (Aug-2026): dateStamp — "counts should reset at midnight, new day
 * starts fresh". Recorded once at creation time (yyyy-MM-dd, this device's own
 * local clock), not derived by parsing punchTime — punchTime's actual format
 * differs between a locally-scanned punch (whatever the device's own serial
 * output uses) and a cloud-synced token (the server's issued_at timestamp),
 * so it isn't a reliable thing to parse a date back out of. History still
 * shows everything regardless of date — this field is used only for the
 * dashboard's daily meal-count totals, not for what's stored or what History
 * displays.
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
    val photoPath: String?,
    val isRejected: Boolean = false,
    val rejectionReason: String? = null,
    val source: String = "device",
    val dateStamp: String = ""
)
