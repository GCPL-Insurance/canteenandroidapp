package com.globalcalcium.canteenmonitor.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "employees")
data class Employee(
    @PrimaryKey val empId: String,
    val name: String,
    val department: String,
    val photoUrl: String?,
    val localPhotoPath: String?
)

data class PunchEvent(
    val serialNo: Int,
    val empId: String,
    val name: String,
    val department: String,
    val mealType: String,
    val punchTime: String,
    val verificationMode: String,
    val photoUrl: String?
)