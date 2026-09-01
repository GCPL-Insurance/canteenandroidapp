package com.globalcalcium.canteenmonitor.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

class TcpPunchListener(private val host: String, private val port: Int) {

    fun startListening(): Flow<Map<String, String>> = flow {
        while (true) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(host, port), 5000)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val buffer = StringBuilder()

                while (true) {
                    val line = reader.readLine() ?: break
                    // FEATURE (Aug-2026): raw diagnostic emission, matching
                    // UsbSerialPunchListener -- lets the same raw-data viewer work
                    // for either connection mode.
                    emit(mapOf("__raw" to (line + "\n")))
                    buffer.append(line).append("\n")

                    // FEATURE (Aug-2026): "show invalid/rejected verification" --
                    // mirrors the exact same keyword set as
                    // UsbSerialPunchListener. Update both together if ESSL
                    // confirms the real wording their firmware will send.
                    val rejectionKeywords = listOf("Access Denied", "Verification Failed", "Access Rejected", "Invalid Card", "Unauthorized")
                    val isRejection = rejectionKeywords.any { line.contains(it, ignoreCase = true) }
                    if (line.contains("Access Granted") || isRejection) {
                        val parsed = parsePunchBlock(buffer.toString())
                        if (parsed.containsKey("User ID")) {
                            emit(parsed)
                        }
                        buffer.clear()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
            delay(3000) // Auto-reconnect interval
        }
    }.flowOn(Dispatchers.IO)

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
}