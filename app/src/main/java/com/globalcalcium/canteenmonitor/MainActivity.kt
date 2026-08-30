package com.globalcalcium.canteenmonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.globalcalcium.canteenmonitor.data.PunchEvent
import com.globalcalcium.canteenmonitor.network.TcpPunchListener
import com.globalcalcium.canteenmonitor.ui.CanteenDashboard
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val tcpListener = TcpPunchListener("10.253.27.100", 8234)
    private var serial = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val latestPunchState = mutableStateOf<PunchEvent?>(null)
        val punchHistoryState = mutableStateListOf<PunchEvent>()
        val totalCountState = mutableStateOf(0)

        lifecycleScope.launch {
            tcpListener.startListening().collect { map ->
                serial++
                val event = PunchEvent(
                    serialNo = serial,
                    empId = map["User ID"] ?: "",
                    name = map["Name"] ?: "Unknown",
                    department = "General",
                    mealType = map["Punch State"] ?: "MEAL",
                    punchTime = map["Punch Time"] ?: "",
                    verificationMode = map["Verification Mode"] ?: "Face",
                    photoUrl = null
                )
                latestPunchState.value = event
                punchHistoryState.add(0, event)
                totalCountState.value = serial
            }
        }

        setContent {
            CanteenDashboard(
                latestPunch = latestPunchState.value,
                punchHistory = punchHistoryState,
                totalCount = totalCountState.value
            )
        }
    }
}