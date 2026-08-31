package com.globalcalcium.canteenmonitor.network

import android.content.Context
import android.util.Base64
import com.globalcalcium.canteenmonitor.data.Employee
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

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
                    val fullUrl = "$serverUrl/iclock/cdata?SN=$deviceSn&options=all&pushver=2.4.1"
                    val url = URL(fullUrl)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 5000
                        readTimeout = 5000
                    }

                    if (conn.responseCode == 200) {
                        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                        parseServerPayload(responseText)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(intervalSeconds * 1000)
            }
        }
    }

    private fun parseServerPayload(payload: String) {
        val lines = payload.split("\n")
        var currentPin = ""
        var currentName = ""

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("DATA UPDATE USERINFO") || trimmed.startsWith("UPDATE USERINFO")) {
                val params = parseKeyValuePairs(trimmed)
                currentPin = params["PIN"] ?: ""
                currentName = params["Name"] ?: "Unknown"

                if (currentPin.isNotEmpty()) {
                    val emp = Employee(empId = currentPin, name = currentName)
                    onEmployeeUpdated(emp)
                }
            } else if (trimmed.startsWith("DATA UPDATE BIOPHOTO") || trimmed.startsWith("UPDATE BIOPHOTO")) {
                val params = parseKeyValuePairs(trimmed)
                val pin = params["PIN"] ?: currentPin
                val base64Content = params["Content"] ?: ""

                if (pin.isNotEmpty() && base64Content.isNotEmpty()) {
                    val photoPath = saveBase64Image(pin, base64Content)
                    val emp = Employee(empId = pin, name = currentName, photoPath = photoPath)
                    onEmployeeUpdated(emp)
                }
            }
        }
    }

    private fun parseKeyValuePairs(line: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val parts = line.split(" ", "\t")
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