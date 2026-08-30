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
                    buffer.append(line).append("\n")

                    if (line.contains("Access Granted")) {
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
}