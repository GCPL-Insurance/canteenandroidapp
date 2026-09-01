package com.globalcalcium.canteenmonitor

import android.content.Context
import android.os.Bundle
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

    // BUGFIX (Aug-2026): "the magic bug" -- everything (employee cache, punch
    // history, the running token counter) used to live ONLY in memory (a plain
    // HashMap and a Compose state list). The moment the app process was killed --
    // closing it, the OS reclaiming memory on a budget TV box, anything -- all of
    // it was gone, and reopening the app looked exactly like a fresh install with
    // default settings. Settings themselves (SharedPreferences) were never
    // actually affected -- they always persisted correctly -- but with the
    // dashboard showing zero employees and zero punch history every time, it
    // understandably looked like everything had reset. Root cause: Room's
    // dependencies and one @Entity annotation existed, but there was no actual
    // @Database class, no DAO wiring, and no room-compiler configured to generate
    // any of it -- Room was never actually being used at all. Now it is.
    private lateinit var db: AppDatabase
    private val employeeCache = mutableMapOf<String, Employee>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = AppDatabase.getInstance(this)

        val prefs = getSharedPreferences("canteen_settings", Context.MODE_PRIVATE)
        var serialIp = prefs.getString("serial_ip", "10.253.27.100") ?: "10.253.27.100"
        var serialPort = prefs.getString("serial_port", "8234") ?: "8234"
        var serverUrl = prefs.getString("server_url", "http://10.253.27.106:8000") ?: "http://10.253.27.106:8000"
        var deviceSn = prefs.getString("device_sn", "GCPLCANTEEN01") ?: "GCPLCANTEEN01"
        // FEATURE (Aug-2026): connection mode -- "network" (existing RS232-to-LAN
        // converter, TCP) or "usb_direct" (RS485-to-USB converter straight into
        // the tablet's USB-C port). Defaults to "network" -- the existing,
        // already-confirmed-working setup -- so nothing changes for anyone who
        // doesn't explicitly opt into USB mode.
        var connectionMode = prefs.getString("connection_mode", "network") ?: "network"
        var usbBaudRate = prefs.getString("usb_baud_rate", "9600") ?: "9600"

        val latestPunchState = mutableStateOf<PunchEvent?>(null)
        val punchHistoryState = mutableStateListOf<PunchEvent>()
        val totalCountState = mutableStateOf(0)
        var serial = 0

        // Shared by both connection modes -- a punch is a punch regardless of
        // which transport it arrived through. Only the listener that produces
        // this Map<String, String> differs between TCP and USB serial.
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

            withContext(Dispatchers.IO) { db.punchDao().insert(event) }
        }

        fun startListeners(ip: String, port: Int, sUrl: String, sn: String, mode: String, baud: Int) {
            punchJob?.cancel()
            admsClient?.stop()
            usbListener?.stop()

            // Start eSSL Push Listener (employee/photo sync -- unaffected by
            // which punch-input transport is chosen)
            admsClient = AdmsClient(this, sUrl, sn) { emp ->
                employeeCache[emp.empId] = emp
                lifecycleScope.launch(Dispatchers.IO) {
                    db.employeeDao().upsert(emp)
                }
            }.also { it.startSyncLoop(lifecycleScope) }

            // FEATURE (Aug-2026): pick ONE punch-input transport based on the
            // chosen connection mode -- never both at once, to avoid the same
            // physical punch being processed twice through two different paths.
            punchJob = when (mode) {
                "usb_direct" -> {
                    val listener = UsbSerialPunchListener(this, baud)
                    usbListener = listener
                    lifecycleScope.launch {
                        listener.startListening().collect { map ->
                            val status = map["__status"]
                            if (status != null) {
                                // Connection/permission/error status, not a real
                                // punch -- surfaced for diagnosing USB OTG /
                                // chipset support issues, not treated as data.
                                android.util.Log.i("UsbSerialPunchListener", "status: $status")
                            } else {
                                handlePunchMap(map)
                            }
                        }
                    }
                }
                else -> {
                    val tcpListener = TcpPunchListener(ip, port)
                    lifecycleScope.launch {
                        tcpListener.startListening().collect { map -> handlePunchMap(map) }
                    }
                }
            }
        }

        // BUGFIX: load whatever already exists in the database BEFORE starting the
        // listeners -- this is what makes the dashboard show real data
        // immediately on reopen instead of looking like a blank fresh install.
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
                    startListeners(
                        newIp, newPort.toIntOrNull() ?: 8234, newUrl, newSn,
                        newMode, newBaud.toIntOrNull() ?: 9600
                    )
                }
            )
        }
    }
}
