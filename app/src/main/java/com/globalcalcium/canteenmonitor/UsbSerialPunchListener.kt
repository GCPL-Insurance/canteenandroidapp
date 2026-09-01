package com.globalcalcium.canteenmonitor.network

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.Executors

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
        val availableDrivers: List<UsbSerialDriver> = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        if (availableDrivers.isEmpty()) {
            // Either no USB device is plugged in at all, the tablet doesn't
            // support USB host mode, or the converter's chipset isn't one of the
            // four this library recognizes — genuinely can't tell which from
            // here, all three look identical from this API.
            trySend(mapOf("__status" to "no_compatible_usb_device_found"))
            awaitClose { }
            return@callbackFlow
        }

        val driver = availableDrivers[0]
        val device: UsbDevice = driver.device

        val permissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    synchronized(this) {
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            openAndListen(usbManager, driver) { parsed -> trySend(parsed) }
                        } else {
                            trySend(mapOf("__status" to "usb_permission_denied"))
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        context.registerReceiver(permissionReceiver, filter)

        if (usbManager.hasPermission(device)) {
            openAndListen(usbManager, driver) { parsed -> trySend(parsed) }
        } else {
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_MUTABLE
            )
            usbManager.requestPermission(device, permissionIntent)
        }

        awaitClose {
            try { context.unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
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

            val buffer = StringBuilder()
            val listener = object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) {
                    val text = String(data, Charsets.UTF_8)
                    buffer.append(text)
                    // Same line-based "Key: Value" ... "Access Granted" protocol
                    // as TcpPunchListener — process complete lines only, keep any
                    // trailing partial line in the buffer for the next chunk.
                    while (buffer.contains("\n")) {
                        val idx = buffer.indexOf("\n")
                        val line = buffer.substring(0, idx).trim()
                        buffer.delete(0, idx + 1)

                        if (line.contains("Access Granted")) {
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
            ioManager = SerialInputOutputManager(serialPort, listener).also {
                Executors.newSingleThreadExecutor().submit(it)
            }
        } catch (e: Exception) {
            onPunchParsed(mapOf("__status" to "usb_connect_exception: ${e.message}"))
        }
    }

    private fun parsePunchBlock(block: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        block.lines().forEach { line ->
            if (line.contains(":")) {
                val parts = line.split(":", limit = 2)
                result[parts[0].trim()] = parts[1].trim()
            } else if (line.contains("Access Granted")) {
                result["Status"] = "Access Granted"
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
