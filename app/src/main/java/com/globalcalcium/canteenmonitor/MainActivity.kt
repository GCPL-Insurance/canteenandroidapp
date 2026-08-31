package com.globalcalcium.canteenmonitor

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.globalcalcium.canteenmonitor.data.Employee
import com.globalcalcium.canteenmonitor.data.PunchEvent
import com.globalcalcium.canteenmonitor.network.AdmsClient
import com.globalcalcium.canteenmonitor.network.TcpPunchListener
import com.globalcalcium.canteenmonitor.ui.CanteenDashboard
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private var tcpJob: Job? = null
    private var admsClient: AdmsClient? = null
    private val employeeCache = mutableMapOf<String, Employee>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("canteen_settings", Context.MODE_PRIVATE)
        var serialIp = prefs.getString("serial_ip", "10.253.27.100") ?: "10.253.27.100"
        var serialPort = prefs.getString("serial_port", "8234") ?: "8234"
        var serverUrl = prefs.getString("server_url", "http://10.253.27.106:8000") ?: "http://10.253.27.106:8000"
        var deviceSn = prefs.getString("device_sn", "GCPLCANTEEN01") ?: "GCPLCANTEEN01"

        val latestPunchState = mutableStateOf<PunchEvent?>(null)
        val punchHistoryState = mutableStateListOf<PunchEvent>()
        val totalCountState = mutableStateOf(0)
        var serial = 0

        fun startListeners(ip: String, port: Int, sUrl: String, sn: String) {
            tcpJob?.cancel()
            admsClient?.stop()

            // Start eSSL Push Listener
            admsClient = AdmsClient(this, sUrl, sn) { emp ->
                employeeCache[emp.empId] = emp
            }.also { it.startSyncLoop(lifecycleScope) }

            // Start Serial Listener
            val tcpListener = TcpPunchListener(ip, port)
            tcpJob = lifecycleScope.launch {
                tcpListener.startListening().collect { map ->
                    serial++
                    val empId = map["User ID"] ?: ""
                    val cachedEmp = employeeCache[empId]

                    val photoFile = File(filesDir, "photos/$empId.jpg")
                    val photoPath = if (photoFile.exists()) photoFile.absolutePath else cachedEmp?.photoPath

                    val event = PunchEvent(
                        serialNo = serial,
                        empId = empId,
                        name = cachedEmp?.name ?: (map["Name"] ?: "Employee $empId"),
                        department = cachedEmp?.department ?: "General",
                        mealType = map["Punch State"] ?: "BREAKFAST",
                        punchTime = map["Punch Time"] ?: "",
                        verificationMode = map["Verification Mode"] ?: "Face",
                        photoPath = photoPath
                    )
                    latestPunchState.value = event
                    punchHistoryState.add(0, event)
                    totalCountState.value = serial
                }
            }
        }

        startListeners(serialIp, serialPort.toIntOrNull() ?: 8234, serverUrl, deviceSn)

        setContent {
            CanteenDashboard(
                latestPunch = latestPunchState.value,
                punchHistory = punchHistoryState,
                totalCount = totalCountState.value,
                serialIp = serialIp,
                serialPort = serialPort,
                serverUrl = serverUrl,
                deviceSn = deviceSn,
                onSaveSettings = { newIp, newPort, newUrl, newSn ->
                    prefs.edit()
                        .putString("serial_ip", newIp)
                        .putString("serial_port", newPort)
                        .putString("server_url", newUrl)
                        .putString("device_sn", newSn)
                        .apply()
                    startListeners(newIp, newPort.toIntOrNull() ?: 8234, newUrl, newSn)
                }
            )
        }
    }
}