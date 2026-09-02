package com.globalcalcium.canteenmonitor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.globalcalcium.canteenmonitor.data.AppDatabase
import com.globalcalcium.canteenmonitor.data.Employee
import com.globalcalcium.canteenmonitor.data.PunchEvent
import com.globalcalcium.canteenmonitor.network.AdmsClient
import com.globalcalcium.canteenmonitor.network.MobileSyncClient
import com.globalcalcium.canteenmonitor.network.TcpPunchListener
import com.globalcalcium.canteenmonitor.network.UsbSerialPunchListener
import com.globalcalcium.canteenmonitor.ui.CanteenDashboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var punchJob: Job? = null
    private var admsClient: AdmsClient? = null
    private var usbListener: UsbSerialPunchListener? = null
    private var mobileSyncClient: MobileSyncClient? = null
    private var tts: TextToSpeech? = null

    private lateinit var db: AppDatabase
    private val employeeCache = mutableMapOf<String, Employee>()

    // FEATURE (Aug-2026): true full-screen kiosk mode -- hides the status bar and
    // navigation bar, matching a dedicated serving-counter display rather than a
    // normal app. BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE means a swipe from the
    // edge still temporarily reveals the bars if needed (e.g. to reach device
    // settings), rather than requiring a full app restart to get them back.
    private fun enableFullScreenMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

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
        enableFullScreenMode()
        db = AppDatabase.getInstance(this)

        // FEATURE (Aug-2026): voice accepted/rejected -- speaks the verification
        // result aloud as each punch happens, useful for a serving counter where
        // staff aren't always looking directly at the screen. US English locale
        // as a reasonable default; if unavailable on a given device this fails
        // silently rather than crashing (checked via the init status callback).
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // FEATURE (Aug-2026): "pronounced in Indian slang" -- switched to
                // Indian English locale, which gives noticeably more natural
                // pronunciation of Indian names than US English. Falls back
                // gracefully to whatever's available if this exact locale isn't
                // installed on a given device.
                val indianEnglish = Locale("en", "IN")
                tts?.language = if (tts?.isLanguageAvailable(indianEnglish) == TextToSpeech.LANG_AVAILABLE ||
                                    tts?.isLanguageAvailable(indianEnglish) == TextToSpeech.LANG_COUNTRY_AVAILABLE) {
                    indianEnglish
                } else {
                    Locale.US
                }
            }
        }

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
        // FEATURE (Aug-2026): rejections are tracked separately from successful
        // punches -- shown as a distinct red popup, not mixed into the "last 5
        // served" list or counted toward meal totals, since a rejection doesn't
        // represent a meal actually served. Still persisted to the database
        // either way, for the audit trail.
        val latestRejectionState = mutableStateOf<PunchEvent?>(null)
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
        // FEATURE (Aug-2026): "if usb disconnected or failed to connect show the
        // warning/error on main window" -- this is the current connection state,
        // shown as a banner directly on the dashboard, not something you have to
        // go find in Raw Data to notice. null means "connected / no problem to
        // report right now".
        val connectionStatusState = mutableStateOf<String?>(null)
        val punchHistoryState = mutableStateListOf<PunchEvent>()
        val totalCountState = mutableStateOf(0)
        var serial = 0

        // FEATURE (Aug-2026): translates the internal status codes from either
        // listener into a clear, human-readable warning for the dashboard banner
        // -- an operator shouldn't need to know what "usb_permission_denied"
        // means internally to understand there's a problem.
        fun statusToWarning(status: String, connectionLabel: String): String = when {
            status == "no_compatible_usb_device_found" -> "⚠ No USB serial device detected — check the converter is plugged in"
            status == "usb_permission_denied" -> "⚠ USB permission denied — reconnect and allow access when prompted"
            status == "usb_open_failed" -> "⚠ Failed to open USB connection"
            status.startsWith("usb_read_error") -> "⚠ USB connection error — device may have disconnected"
            status.startsWith("usb_connect_exception") -> "⚠ USB connection failed"
            status == "usb_device_detached" -> "⚠ USB cable disconnected — plug it back in to reconnect automatically"
            status == "usb_device_attached_reconnecting" -> "⏳ USB device detected, reconnecting…"
            status == "disconnected" -> "⚠ $connectionLabel disconnected — reconnecting…"
            status.startsWith("connection_failed") -> "⚠ $connectionLabel connection failed — retrying…"
            else -> "⚠ $connectionLabel connection issue: $status"
        }

        suspend fun handlePunchMap(map: Map<String, String>) {
            val empId = map["User ID"] ?: return
            val cachedEmp = employeeCache[empId] ?: withContext(Dispatchers.IO) {
                db.employeeDao().getById(empId)?.also { employeeCache[empId] = it }
            }

            val photoFile = File(filesDir, "photos/$empId.jpg")
            val photoPath = if (photoFile.exists()) photoFile.absolutePath else cachedEmp?.photoPath
            val isRejected = map["Status"] == "Rejected"
            val rejectionReason = map["RejectionReason"]

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
                photoPath = photoPath,
                isRejected = isRejected,
                rejectionReason = rejectionReason
            )

            // FEATURE (Aug-2026): "voice accepted/rejected... his name also
            // pronounced" -- speaks the person's name for a successful punch,
            // not just the bare outcome. Rejections deliberately stay nameless
            // (name resolution can be unreliable exactly when verification
            // failed) and just announce the reason instead.
            tts?.speak(
                if (isRejected) "Access Denied${rejectionReason?.let { ", $it" } ?: ""}" else "Access Granted, ${event.name}",
                TextToSpeech.QUEUE_FLUSH, null, null
            )

            if (isRejected) {
                latestRejectionState.value = event
            } else {
                latestPunchState.value = event
                punchHistoryState.add(0, event)
                // BUGFIX (Aug-2026): this used to set totalCountState = serial
                // directly, which is the LOCAL device's own scan counter only --
                // once mobile sync also started incrementing this same state for
                // cloud-synced tokens, the next LOCAL punch would silently
                // overwrite (not add to) whatever the sync loop had contributed.
                // Deriving it from punchHistoryState.size instead means both
                // sources contribute consistently, since both already append to
                // that same list before this line runs.
                totalCountState.value = punchHistoryState.size
            }

            withContext(Dispatchers.IO) { db.punchDao().insert(event) }
        }

        fun startListeners(ip: String, port: Int, sUrl: String, sn: String, mode: String, baud: Int) {
            punchJob?.cancel()
            admsClient?.stop()
            usbListener?.stop()
            mobileSyncClient?.stop()

            admsClient = AdmsClient(this, sUrl, sn) { emp ->
                employeeCache[emp.empId] = emp
                lifecycleScope.launch(Dispatchers.IO) {
                    db.employeeDao().upsert(emp)
                }
            }.also { it.startSyncLoop(lifecycleScope) }

            // FEATURE (Aug-2026): "our idea is this Android app is like one of
            // our ESSL machines... better data sync capability" -- pulls tokens
            // this device wouldn't otherwise see through its own serial/USB
            // connection, most notably manual vendor tokens. Deliberately does
            // NOT update latestPunchState (the "spotlight" card) -- that stays
            // reserved for someone actually scanned at THIS counter, since a
            // vendor token synced from the admin portal has no photo and
            // overwriting a just-scanned person's photo with it would be
            // confusing at a busy counter. It DOES count toward the total and
            // appear in history, since a vendor token represents a real meal
            // served.
            mobileSyncClient = MobileSyncClient(this, sUrl, sn).also { client ->
                lifecycleScope.launch {
                    client.startSyncLoop(lifecycleScope).collect { event ->
                        punchHistoryState.add(0, event)
                        totalCountState.value = punchHistoryState.size
                        withContext(Dispatchers.IO) { db.punchDao().insert(event) }
                    }
                }
            }

            punchJob = when (mode) {
                "usb_direct" -> {
                    val listener = UsbSerialPunchListener(this, baud)
                    usbListener = listener
                    lifecycleScope.launch {
                        listener.startListening().collect { map ->
                            when {
                                map.containsKey("__status") -> {
                                    val status = map["__status"] ?: ""
                                    logRaw("[STATUS] $status")
                                    connectionStatusState.value = if (status == "connected") null else statusToWarning(status, "USB")
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
                            when {
                                map.containsKey("__status") -> {
                                    val status = map["__status"] ?: ""
                                    logRaw("[STATUS] $status")
                                    connectionStatusState.value = if (status == "connected") null else statusToWarning(status, "Network")
                                }
                                map.containsKey("__raw") -> {
                                    logRaw("[RAW] ${map["__raw"]?.replace("\n", "\\n")}")
                                }
                                else -> handlePunchMap(map)
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
                latestRejection = latestRejectionState.value,
                punchHistory = punchHistoryState,
                totalCount = totalCountState.value,
                rawLog = rawLogState,
                connectionStatus = connectionStatusState.value,
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

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
