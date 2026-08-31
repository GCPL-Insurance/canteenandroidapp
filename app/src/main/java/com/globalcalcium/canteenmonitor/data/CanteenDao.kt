package com.globalcalcium.canteenmonitor.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EmployeeDao {
    // REPLACE on conflict: an employee record updated on the server (new name,
    // new photo path, etc.) should overwrite the old local copy, not fail the
    // insert or leave stale data sitting alongside it.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(employee: Employee)

    @Query("SELECT * FROM employees")
    suspend fun getAll(): List<Employee>

    @Query("SELECT * FROM employees WHERE empId = :empId LIMIT 1")
    suspend fun getById(empId: String): Employee?

    @Query("SELECT COUNT(*) FROM employees")
    suspend fun count(): Int
}

@Dao
interface PunchDao {
    @Insert
    suspend fun insert(punch: PunchEvent): Long

    // Ordered newest-first, matching how punch history is displayed (most recent
    // punch at the top of the list).
    @Query("SELECT * FROM punch_events ORDER BY id DESC")
    suspend fun getAll(): List<PunchEvent>

    @Query("SELECT * FROM punch_events ORDER BY id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<PunchEvent>

    @Query("SELECT COUNT(*) FROM punch_events")
    suspend fun count(): Int

    // Highest serialNo currently stored — used to correctly RESUME the token
    // counter after a restart instead of starting back over from 1, which would
    // have caused newly-served tokens to collide with numbers already used
    // before the app was closed.
    @Query("SELECT MAX(serialNo) FROM punch_events")
    suspend fun maxSerialNo(): Int?
}
