package com.globalcalcium.canteenmonitor.network

import android.content.Context
import android.util.Base64
import com.globalcalcium.canteenmonitor.data.Employee
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class AdmsClient(
    private val context: Context,
    private val serverUrl: String,
    private val deviceSn: String,
    private val onEmployeeUpdated: (Employee) -> Unit
) {
    private var isRunning = true

    fun startSyncLoop(scope: CoroutineScope, intervalSeconds: Long = 10) {
        scope.launch(Dispatchers.IO) {
            while (isRunning) {
                try {
                    // BUGFIX (Aug-2026): this was polling /iclock/cdata, which doesn't
                    // exist on the server at all (confirmed: returns 404) -- that's
                    // the entire reason employee/photo sync was never actually
                    // happening. The real endpoint devices poll for pending commands
                    // is /iclock/getrequest.aspx?SN=<device_sn> -- same one every real
                    // biometric device in this deployment already uses.
                    val encodedSn = URLEncoder.encode(deviceSn, "UTF-8")
                    val fullUrl = "$serverUrl/iclock/getrequest.aspx?SN=$encodedSn"
                    val url = URL(fullUrl)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 5000
                        readTimeout = 5000
                    }

                    if (conn.responseCode == 200) {
                        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                        val ackedIds = parseServerPayload(responseText)
                        // Ack each delivered command back to the server, same as a
                        // real device would via devicecmd.aspx -- not required for
                        // delivery itself (the server marks a command "sent" the
                        // moment it hands it out, before any ack), but without this
                        // the admin's Cmd Queue view would show these stuck at "sent"
                        // forever instead of "done".
                        for (cmdId in ackedIds) {
                            postAck(cmdId)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(intervalSeconds * 1000)
            }
        }
    }

    private fun postAck(cmdId: String) {
        try {
            val encodedSn = URLEncoder.encode(deviceSn, "UTF-8")
            val url = URL("$serverUrl/iclock/devicecmd.aspx?SN=$encodedSn")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            val body = "ID=$cmdId&Return=0&CMD=DATA"
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.responseCode // triggers the request; response body not needed
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Returns the list of command IDs successfully processed, so they can be
     * acked back to the server afterward.
     *
     * BUGFIX (Aug-2026): every line the server sends is prefixed "C:<cmd_id>:"
     * (confirmed from the server's actual response format) -- the old parser
     * checked trimmed.startsWith("DATA UPDATE USERINFO") directly against that
     * prefixed line, which never matched, so no employee data was ever parsed out
     * even once the URL itself was fixed. Now strips the prefix and captures the
     * ID for acking.
     */
    private fun parseServerPayload(payload: String): List<String> {
        val ackedIds = mutableListOf<String>()
        val lines = payload.split("\r\n", "\n").filter { it.isNotBlank() }
        var currentPin = ""
        var currentName = ""

        for (rawLine in lines) {
            var line = rawLine.trim()
            var cmdId: String? = null
            if (line.startsWith("C:")) {
                // Format: C:<cmd_id>:<actual command text>
                val afterC = line.substring(2)
                val sep = afterC.indexOf(':')
                if (sep >= 0) {
                    cmdId = afterC.substring(0, sep)
                    line = afterC.substring(sep + 1).trim()
                }
            }

            if (line.startsWith("DATA UPDATE USERINFO") || line.startsWith("UPDATE USERINFO")) {
                val params = parseKeyValuePairs(line)
                currentPin = params["PIN"] ?: ""
                currentName = params["Name"] ?: "Unknown"

                if (currentPin.isNotEmpty()) {
                    val emp = Employee(empId = currentPin, name = currentName)
                    onEmployeeUpdated(emp)
                    cmdId?.let { ackedIds.add(it) }
                }
            } else if (line.startsWith("DATA UPDATE BIOPHOTO") || line.startsWith("UPDATE BIOPHOTO")) {
                val params = parseKeyValuePairs(line)
                val pin = params["PIN"] ?: currentPin
                val base64Content = params["Content"] ?: ""

                if (pin.isNotEmpty() && base64Content.isNotEmpty()) {
                    val photoPath = saveBase64Image(pin, base64Content)
                    val emp = Employee(empId = pin, name = currentName, photoPath = photoPath)
                    onEmployeeUpdated(emp)
                    cmdId?.let { ackedIds.add(it) }
                }
            }
        }
        return ackedIds
    }

    private fun parseKeyValuePairs(line: String): Map<String, String> {
        // BUGFIX (Aug-2026): was splitting on BOTH space and tab, but fields in this
        // wire format are tab-separated while a value itself (most commonly Name=)
        // can legitimately contain spaces -- e.g. "Name=K.H. KANTHARAJU" would have
        // silently become "K.H." only. Splitting on tab alone preserves the full
        // value.
        val map = mutableMapOf<String, String>()
        val parts = line.split("\t")
        for (part in parts) {
            if (part.contains("=")) {
                val kv = part.split("=", limit = 2)
                if (kv.size == 2) {
                    map[kv[0].trim()] = kv[1].trim()
                }
            }
        }
        return map
    }

    private fun saveBase64Image(pin: String, base64Data: String): String {
        return try {
            val cleanBase64 = base64Data.substringAfter("base64,").trim()
            val imageBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
            val photosDir = File(context.filesDir, "photos")
            if (!photosDir.exists()) photosDir.mkdirs()

            val photoFile = File(photosDir, "$pin.jpg")
            FileOutputStream(photoFile).use { it.write(imageBytes) }
            photoFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    fun stop() {
        isRunning = false
    }
}