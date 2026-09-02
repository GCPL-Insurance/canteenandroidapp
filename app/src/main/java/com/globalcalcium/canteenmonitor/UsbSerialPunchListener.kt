package com.globalcalcium.canteenmonitor.network

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * FEATURE (Aug-2026): direct USB serial input — an RS485-to-USB converter plugged
 * straight into the tablet's USB-C port, no RS232-to-LAN converter or network hop
 * in between. This is an ALTERNATIVE to TcpPunchListener, not a replacement —
 * MainActivity picks one or the other based on the connection mode the admin
 * chooses in Settings. Deliberately reuses the exact same line-based
 * "Key: Value" ... "Access Granted" parsing protocol as TcpPunchListener, and
 * emits the same Flow<Map<String, String>> shape, so everything downstream of the
 * listener (punch handling, database writes, punch history) doesn't need to know
 * or care which transport a punch actually arrived through.
 *
 * FEATURE (Aug-2026): "automate reconnect on cable disconnect, no manual
 * settings/reconnect/re-grant-access" — this is the part that didn't exist
 * before. The old version only ever attempted to find+open a device ONCE, when
 * startListening() was first called — nothing was watching for the cable
 * actually being unplugged and replugged, so nothing ever tried again on its
 * own; the connection just sat there dead until the admin manually reopened
 * Settings and hit Save & Reconnect. Now registers for the OS's own
 * ACTION_USB_DEVICE_ATTACHED / ACTION_USB_DEVICE_DETACHED broadcasts and
 * automatically re-runs the connect attempt on attach, tears down cleanly on
 * detach. Re-granting permission should NOT be needed for the same physical
 * device being replugged — Android's USB permission grant is tied to the
 * device's own identity (vendor/product/serial), not to a single connection
 * session, so hasPermission() should still return true across a simple
 * unplug/replug of the same cable. It only prompts again if a genuinely
 * different USB device is plugged in, which is correct.
 *
 * Two things this genuinely depends on that can't be verified from here:
 *   1. The tablet's USB-C port must support USB Host/OTG mode. Not universal —
 *      check the tablet's own spec sheet, or just try it.
 *   2. The RS485-to-USB converter's chipset must be one this library recognizes
 *      (FTDI, CP210x/Silicon Labs, CH340/CH341, PL2303 — the four most common).
 *      If findAllDrivers() comes back empty with the converter actually plugged
 *      in, that's the chipset not being recognized, not a bug in this code.
 */
class UsbSerialPunchListener(
    private val context: Context,
    private val baudRate: Int = 9600
) {
    companion object {
        private const val ACTION_USB_PERMISSION = "com.globalcalcium.canteenmonitor.USB_PERMISSION"
    }

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null

    /**
     * Emits a status string ("no_device", "permission_denied", "connected", or an
     * error message) on connect/disconnect events, and a parsed punch Map for
     * every completed "Access Granted" block — mirrors TcpPunchListener's output
     * shape closely enough that the same downstream punch-handling code works
     * for both, distinguished by which keys are present.
     */
    fun startListening(): Flow<Map<String, String>> = callbackFlow {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        // BUGFIX (Aug-2026): captured explicitly rather than relying on a bare
        // launch{} call from inside the nested anonymous BroadcastReceiver
        // objects below -- an object : BroadcastReceiver() { } does NOT inherit
        // this callbackFlow lambda's implicit ProducerScope receiver the way a
        // nested lambda would, so an unqualified launch{} there would be an
        // unresolved reference. Capturing it as a named val and calling
        // producerScope.launch { ... } explicitly avoids that entirely.
        val producerScope = this

        fun tryConnect() {
            val availableDrivers: List<UsbSerialDriver> = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            if (availableDrivers.isEmpty()) {
                // Either nothing is plugged in right now, the tablet doesn't
                // support USB host mode, or the converter's chipset isn't one of
                // the four this library recognizes — genuinely can't tell which
                // from here, all three look identical from this API. Harmless to
                // report repeatedly (e.g. right after a detach, before the
                // replug happens) — the dashboard only shows the latest status.
                trySend(mapOf("__status" to "no_compatible_usb_device_found"))
                return
            }

            val driver = availableDrivers[0]
            val device: UsbDevice = driver.device

            if (usbManager.hasPermission(device)) {
                openAndListen(usbManager, driver) { parsed -> trySend(parsed) }
            } else {
                // BUGFIX (Aug-2026): a MUTABLE PendingIntent wrapping an IMPLICIT
                // intent (no explicit target) is disallowed since Android 12 (API
                // 31) and throws IllegalArgumentException at runtime.
                // setPackage() makes it explicit (restricted to this app only,
                // which is what we want anyway) without needing to reference a
                // specific class.
                val permissionIntent = PendingIntent.getBroadcast(
                    context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                    PendingIntent.FLAG_MUTABLE
                )
                usbManager.requestPermission(device, permissionIntent)
            }
        }

        val permissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    synchronized(this) {
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            val device = if (Build.VERSION.SDK_INT >= 33)
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                            else
                                @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                            val driver = device?.let { d -> UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull { it.device == d } }
                            if (driver != null) {
                                openAndListen(usbManager, driver) { parsed -> trySend(parsed) }
                            } else {
                                tryConnect()
                            }
                        } else {
                            trySend(mapOf("__status" to "usb_permission_denied"))
                        }
                    }
                }
            }
        }

        // FEATURE (Aug-2026): the actual automatic-reconnect mechanism — the OS
        // itself broadcasts these when a USB device is physically plugged in or
        // unplugged, regardless of which app is running. Listening for them is
        // what replaces "go to Settings and click reconnect" with "just plug the
        // cable back in".
        val attachDetachReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        trySend(mapOf("__status" to "usb_device_attached_reconnecting"))
                        // Small delay before enumerating — gives the OS a moment
                        // to finish settling the newly-attached device rather
                        // than racing it immediately on the attach event.
                        producerScope.launch {
                            delay(500)
                            tryConnect()
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        trySend(mapOf("__status" to "usb_device_detached"))
                        try {
                            ioManager?.stop()
                            port?.close()
                        } catch (_: Exception) {}
                        ioManager = null
                        port = null
                    }
                }
            }
        }

        val permFilter = IntentFilter(ACTION_USB_PERMISSION)
        val attachDetachFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // BUGFIX (Aug-2026): Android 13+ (API 33) requires an explicit
        // RECEIVER_EXPORTED/RECEIVER_NOT_EXPORTED flag for any dynamically
        // registered receiver, or registerReceiver() throws at runtime.
        // RECEIVER_NOT_EXPORTED is correct for both receivers here — the
        // permission broadcast is only ever sent by this app to itself, and the
        // attach/detach broadcasts only need to be received FROM the system, not
        // sent BY other apps (the exported flag governs the latter, not whether
        // this app can still receive genuine system broadcasts).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, permFilter, Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(attachDetachReceiver, attachDetachFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(permissionReceiver, permFilter)
            context.registerReceiver(attachDetachReceiver, attachDetachFilter)
        }

        // Initial connection attempt, same as before — this covers the normal
        // case where the cable is already plugged in when the app starts.
        tryConnect()

        awaitClose {
            try { context.unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
            try { context.unregisterReceiver(attachDetachReceiver) } catch (_: Exception) {}
            stop()
        }
    }

    private fun openAndListen(
        usbManager: UsbManager,
        driver: UsbSerialDriver,
        onPunchParsed: (Map<String, String>) -> Unit
    ) {
        try {
            val connection = usbManager.openDevice(driver.device) ?: run {
                onPunchParsed(mapOf("__status" to "usb_open_failed"))
                return
            }
            val serialPort = driver.ports[0]
            serialPort.open(connection)
            serialPort.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port = serialPort

            onPunchParsed(mapOf("__status" to "connected"))

            // BUGFIX (Aug-2026): this is the actual reason no punches were ever
            // being parsed, confirmed against a real device log capture. The
            // previous version REMOVED each line from the buffer as it was
            // processed, before checking for "Access Granted" -- so by the time
            // "Access Granted" arrived, every preceding field line (User ID,
            // Name, Punch Time, etc.) had ALREADY been deleted, and there was
            // nothing left to parse but the device's trailing control-character
            // padding. Fixed to match TcpPunchListener's proven-correct approach
            // exactly: APPEND every line to the buffer and never remove anything
            // until "Access Granted" appears, then parse the FULL accumulated
            // buffer (which still has every field line intact) before clearing
            // it for the next block. The device's own control-character padding
            // lines (^K etc., visible in a raw serial capture) are harmless
            // either way -- parsePunchBlock only picks up lines containing ":".
            val buffer = StringBuilder()
            var partialLine = StringBuilder()
            val listener = object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    val text = String(data, Charsets.UTF_8)
                    // FEATURE (Aug-2026): "raw parser for testing purpose" -- emit
                    // exactly what arrived, byte-for-byte as decoded text, before
                    // any parsing logic touches it. This is what actually settles
                    // "is data arriving at all" vs "data arrives but doesn't
                    // parse" instead of guessing between them.
                    onPunchParsed(mapOf("__raw" to text))
                    partialLine.append(text)
                    while (partialLine.contains("\n")) {
                        val idx = partialLine.indexOf("\n")
                        val line = partialLine.substring(0, idx)
                        partialLine.delete(0, idx + 1)

                        buffer.append(line).append("\n")

                        // FEATURE (Aug-2026): "show invalid/rejected verification"
                        // -- the current device firmware only ever sends "Access
                        // Granted", so this recognizes the most likely keywords a
                        // future firmware update might use for a failed
                        // verification, without knowing the exact wording ESSL
                        // will actually send yet. Once confirmed, add/adjust the
                        // exact keyword here and in TcpPunchListener's matching
                        // list -- this is deliberately the one place that needs
                        // updating, not scattered logic.
                        val rejectionKeywords = listOf("Access Denied", "Verification Failed", "Access Rejected", "Invalid Card", "Unauthorized")
                        val isRejection = rejectionKeywords.any { line.contains(it, ignoreCase = true) }
                        if (line.contains("Access Granted") || isRejection) {
                            val parsed = parsePunchBlock(buffer.toString())
                            if (parsed.containsKey("User ID")) {
                                onPunchParsed(parsed)
                            }
                            buffer.clear()
                        }
                    }
                }

                override fun onRunError(e: Exception) {
                    onPunchParsed(mapOf("__status" to "usb_read_error: ${e.message}"))
                }
            }
            // BUGFIX (Aug-2026): SerialInputOutputManager stopped being submittable
            // to an ExecutorService as of this library's v3.9.0 release -- it
            // manages its own internal thread now via start()/stop() directly.
            // We're on 3.10.0, so the old Executors.newSingleThreadExecutor()
            // .submit(ioManager) pattern (which worked on older versions) no
            // longer compiles: ioManager doesn't implement Runnable anymore.
            ioManager = SerialInputOutputManager(serialPort, listener)
            ioManager?.start()
        } catch (e: Exception) {
            onPunchParsed(mapOf("__status" to "usb_connect_exception: ${e.message}"))
        }
    }

    private fun parsePunchBlock(block: String): Map<String, String> {
        val rejectionKeywords = listOf("Access Denied", "Verification Failed", "Access Rejected", "Invalid Card", "Unauthorized")
        val result = mutableMapOf<String, String>()
        block.lines().forEach { line ->
            val matchedRejection = rejectionKeywords.firstOrNull { line.contains(it, ignoreCase = true) }
            when {
                matchedRejection != null -> {
                    result["Status"] = "Rejected"
                    result["RejectionReason"] = matchedRejection
                }
                line.contains("Access Granted") -> result["Status"] = "Access Granted"
                line.contains(":") -> {
                    val parts = line.split(":", limit = 2)
                    result[parts[0].trim()] = parts[1].trim()
                }
            }
        }
        return result
    }

    fun stop() {
        try {
            ioManager?.stop()
            port?.close()
        } catch (_: Exception) {}
        ioManager = null
        port = null
    }
}
