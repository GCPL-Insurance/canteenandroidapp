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
import com.globalcalcium.canteenmonitor.ui.CanteenDashboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private var tcpJob: Job? = null
    private var admsClient: AdmsClient? = null

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
                // BUGFIX: persist every synced employee to the database as it
                // arrives, not just the in-memory cache -- this is what makes
                // "push all employees" from the admin portal actually survive an
                // app restart instead of needing to be re-pushed every time.
                lifecycleScope.launch(Dispatchers.IO) {
                    db.employeeDao().upsert(emp)
                }
            }.also { it.startSyncLoop(lifecycleScope) }

            // Start Serial Listener
            val tcpListener = TcpPunchListener(ip, port)
            tcpJob = lifecycleScope.launch {
                tcpListener.startListening().collect { map ->
                    serial++
                    val empId = map["User ID"] ?: ""
                    val cachedEmp = employeeCache[empId] ?: withContext(Dispatchers.IO) {
                        // In-memory cache is empty right after a restart until the
                        // next sync poll arrives -- fall back to the database,
                        // which already has whatever was synced before the app
                        // was last closed.
                        db.employeeDao().getById(empId)?.also { employeeCache[empId] = it }
                    }

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

                    // BUGFIX: persist every punch to the database as it happens --
                    // this is the actual token history now, not just a
                    // process-lifetime in-memory list.
                    withContext(Dispatchers.IO) { db.punchDao().insert(event) }
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
                // Resume the token counter from the highest serialNo already
                // stored, not always restart at 0 -- otherwise a restart mid-day
                // would start reissuing token numbers that were already used
                // earlier, which is confusing at best and a real duplicate-token
                // problem at worst.
                val resume = db.punchDao().maxSerialNo() ?: 0
                Triple(employees, punches, resume)
            }
            loadedEmployees.forEach { employeeCache[it.empId] = it }
            punchHistoryState.addAll(loadedPunches)
            latestPunchState.value = loadedPunches.firstOrNull()
            serial = resumeSerial
            totalCountState.value = loadedPunches.size

            startListeners(serialIp, serialPort.toIntOrNull() ?: 8234, serverUrl, deviceSn)
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
                database = db,
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
