package com.globalcalcium.canteenmonitor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.globalcalcium.canteenmonitor.data.AppDatabase
import com.globalcalcium.canteenmonitor.data.Employee
import com.globalcalcium.canteenmonitor.data.PunchEvent
import com.globalcalcium.canteenmonitor.network.AdmsClient
import com.globalcalcium.canteenmonitor.network.TcpPunchListener
import com.globalcalcium.canteenmonitor.network.UsbSerialPunchListener
import com.globalcalcium.canteenmonitor.ui.CanteenDashboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private var punchJob: Job? = null
    private var admsClient: AdmsClient? = null
    private var usbListener: UsbSerialPunchListener? = null

    private lateinit var db: AppDatabase
    private val employeeCache = mutableMapOf<String, Employee>()

    // FEATURE (Aug-2026): this app is meant to run continuously as a kiosk-style
    // serving display -- request an exemption from battery optimization so a
    // budget device's background-process killer doesn't stop it (a separate,
    // common cause of "randomly stops working" distinct from the two real
    // crashes fixed in UsbSerialPunchListener). Only requested once per install
    // (checked against isIgnoringBatteryOptimizations); if the user dismisses
    // the system prompt, this doesn't ask again every single launch.
    // BUGFIX (Aug-2026): this used to check isIgnoringBatteryOptimizations() on
    // every launch and re-prompt if it was still false -- but that stays false
    // forever if the user dismisses or declines the system dialog, meaning it
    // would ask again every single time the app opened, with no way to say "no,
    // don't ask again." Now tracked explicitly via SharedPreferences: asked once,
    // and that choice (granted or declined) is respected from then on.
    private fun requestBatteryOptimizationExemption(prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean("battery_optimization_asked", false)) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        prefs.edit().putBoolean("battery_optimization_asked", true).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = AppDatabase.getInstance(this)

        val prefs = getSharedPreferences("canteen_settings", Context.MODE_PRIVATE)
        requestBatteryOptimizationExemption(prefs)

        // BUGFIX (Aug-2026): these were plain Kotlin `var`s, read once from
        // SharedPreferences at startup and handed to Compose as simple values.
        // Saving settings correctly wrote the new value to SharedPreferences AND
        // correctly restarted the listener with it (the app really was using
        // whatever baud rate was last saved) -- but Compose had no way to know a
        // plain, non-State variable had changed, so reopening Settings always
        // showed whatever was read at app launch, never the actual current
        // value. Converting these to mutableStateOf, and having onSaveSettings
        // update them too, is what makes the UI actually reflect reality.
        var serialIp by mutableStateOf(prefs.getString("serial_ip", "10.253.27.100") ?: "10.253.27.100")
        var serialPort by mutableStateOf(prefs.getString("serial_port", "8234") ?: "8234")
        var serverUrl by mutableStateOf(prefs.getString("server_url", "http://10.253.27.106:8000") ?: "http://10.253.27.106:8000")
        var deviceSn by mutableStateOf(prefs.getString("device_sn", "GCPLCANTEEN01") ?: "GCPLCANTEEN01")
        var connectionMode by mutableStateOf(prefs.getString("connection_mode", "network") ?: "network")
        var usbBaudRate by mutableStateOf(prefs.getString("usb_baud_rate", "9600") ?: "9600")

        val latestPunchState = mutableStateOf<PunchEvent?>(null)
        // FEATURE (Aug-2026): "raw parser for testing purpose" -- a visible,
        // timestamped log of exactly what's arriving (raw text) and the
        // connection's own status (connected, permission denied, no compatible
        // device, read errors). This used to only go to Log.i(), invisible
        // without adb logcat -- exactly the wrong thing to be invisible when
        // diagnosing "why isn't this receiving/parsing data".
        val rawLogState = mutableStateListOf<String>()
        fun logRaw(line: String) {
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            rawLogState.add(0, "$ts  $line")
            if (rawLogState.size > 500) rawLogState.removeAt(rawLogState.size - 1)
        }
        val punchHistoryState = mutableStateListOf<PunchEvent>()
        val totalCountState = mutableStateOf(0)
        var serial = 0

        suspend fun handlePunchMap(map: Map<String, String>) {
            val empId = map["User ID"] ?: return
            val cachedEmp = employeeCache[empId] ?: withContext(Dispatchers.IO) {
                db.employeeDao().getById(empId)?.also { employeeCache[empId] = it }
            }

            val photoFile = File(filesDir, "photos/$empId.jpg")
            val photoPath = if (photoFile.exists()) photoFile.absolutePath else cachedEmp?.photoPath

            serial++
            val event = PunchEvent(
                serialNo = serial,
                empId = empId,
                // BUGFIX (Aug-2026): confirmed from a real device capture that
                // "Name:" arrives as an empty string, not absent -- ?: alone only
                // falls through on null, so an unsynced employee would show a
                // blank name instead of the intended placeholder. ifBlank treats
                // empty-or-whitespace the same as null for fallback purposes.
                name = cachedEmp?.name?.ifBlank { null } ?: map["Name"]?.ifBlank { null } ?: "Employee $empId",
                department = cachedEmp?.department ?: "General",
                mealType = map["Punch State"] ?: "BREAKFAST",
                punchTime = map["Punch Time"] ?: "",
                verificationMode = map["Verification Mode"] ?: "Face",
                photoPath = photoPath
            )
            latestPunchState.value = event
            punchHistoryState.add(0, event)
            totalCountState.value = serial

            withContext(Dispatchers.IO) { db.punchDao().insert(event) }
        }

        fun startListeners(ip: String, port: Int, sUrl: String, sn: String, mode: String, baud: Int) {
            punchJob?.cancel()
            admsClient?.stop()
            usbListener?.stop()

            admsClient = AdmsClient(this, sUrl, sn) { emp ->
                employeeCache[emp.empId] = emp
                lifecycleScope.launch(Dispatchers.IO) {
                    db.employeeDao().upsert(emp)
                }
            }.also { it.startSyncLoop(lifecycleScope) }

            punchJob = when (mode) {
                "usb_direct" -> {
                    val listener = UsbSerialPunchListener(this, baud)
                    usbListener = listener
                    lifecycleScope.launch {
                        listener.startListening().collect { map ->
                            when {
                                map.containsKey("__status") -> {
                                    logRaw("[STATUS] ${map["__status"]}")
                                }
                                map.containsKey("__raw") -> {
                                    logRaw("[RAW] ${map["__raw"]?.replace("\n", "\\n")}")
                                }
                                else -> handlePunchMap(map)
                            }
                        }
                    }
                }
                else -> {
                    val tcpListener = TcpPunchListener(ip, port)
                    lifecycleScope.launch {
                        tcpListener.startListening().collect { map ->
                            if (map.containsKey("__raw")) {
                                logRaw("[RAW] ${map["__raw"]?.replace("\n", "\\n")}")
                            } else {
                                handlePunchMap(map)
                            }
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            val (loadedEmployees, loadedPunches, resumeSerial) = withContext(Dispatchers.IO) {
                val employees = db.employeeDao().getAll()
                val punches = db.punchDao().getAll()
                val resume = db.punchDao().maxSerialNo() ?: 0
                Triple(employees, punches, resume)
            }
            loadedEmployees.forEach { employeeCache[it.empId] = it }
            punchHistoryState.addAll(loadedPunches)
            latestPunchState.value = loadedPunches.firstOrNull()
            serial = resumeSerial
            totalCountState.value = loadedPunches.size

            startListeners(
                serialIp, serialPort.toIntOrNull() ?: 8234, serverUrl, deviceSn,
                connectionMode, usbBaudRate.toIntOrNull() ?: 9600
            )
        }

        setContent {
            CanteenDashboard(
                latestPunch = latestPunchState.value,
                punchHistory = punchHistoryState,
                totalCount = totalCountState.value,
                rawLog = rawLogState,
                serialIp = serialIp,
                serialPort = serialPort,
                serverUrl = serverUrl,
                deviceSn = deviceSn,
                connectionMode = connectionMode,
                usbBaudRate = usbBaudRate,
                database = db,
                onSaveSettings = { newIp, newPort, newUrl, newSn, newMode, newBaud ->
                    prefs.edit()
                        .putString("serial_ip", newIp)
                        .putString("serial_port", newPort)
                        .putString("server_url", newUrl)
                        .putString("device_sn", newSn)
                        .putString("connection_mode", newMode)
                        .putString("usb_baud_rate", newBaud)
                        .apply()
                    // BUGFIX: update the actual Compose state, not just
                    // SharedPreferences -- this is what makes Settings show the
                    // real current value the next time it's opened, instead of
                    // whatever was read once at app launch.
                    serialIp = newIp
                    serialPort = newPort
                    serverUrl = newUrl
                    deviceSn = newSn
                    connectionMode = newMode
                    usbBaudRate = newBaud
                    startListeners(
                        newIp, newPort.toIntOrNull() ?: 8234, newUrl, newSn,
                        newMode, newBaud.toIntOrNull() ?: 9600
                    )
                }
            )
        }
    }
}
