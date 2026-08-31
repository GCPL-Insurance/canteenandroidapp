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

data class PunchEvent(
    val serialNo: Int,
    val empId: String,
    val name: String,
    val department: String,
    val mealType: String,
    val punchTime: String,
    val verificationMode: String,
    val photoPath: String?
)