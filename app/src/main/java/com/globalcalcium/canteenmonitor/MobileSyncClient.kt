package com.globalcalcium.canteenmonitor.network

import android.content.Context
import com.globalcalcium.canteenmonitor.data.PunchEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * FEATURE (Aug-2026): "our idea is this Android app is like one of our ESSL
 * machines... better data sync capability... data available offline even with
 * no network." Pulls tokens the app wouldn't otherwise see through its own
 * serial/USB connection — most notably manual vendor tokens issued from the
 * admin portal, which never touch this device's hardware at all, but also
 * punches from other devices in the same unit.
 *
 * Cursor-based (since_id), not a snapshot — only ever asks for what's new since
 * the last successful call, and persists that cursor so a restart resumes from
 * where it left off rather than re-fetching everything. Authenticated by
 * registered device SN, matching every other endpoint this app already talks to
 * — no session/cookie involved, same as a real biometric device wouldn't have
 * one either.
 *
 * Known simplification, stated plainly rather than hidden: a cloud-synced
 * token's displayed "Token #" is the server's own per-meal/day token_number,
 * which is a different numbering space from this device's own local scan
 * counter. In practice they won't usually collide (different times of day), but
 * it's not mathematically guaranteed — this is a low-stakes display label, not
 * used for any billing/audit calculation on the Android side, so this
 * simplification was chosen over the added complexity of a fully separate
 * numbering scheme.
 */
class MobileSyncClient(
    private val context: Context,
    private val serverUrl: String,
    private val deviceSn: String
) {
    private var isRunning = true
    private val prefs = context.getSharedPreferences("mobile_sync", Context.MODE_PRIVATE)

    fun startSyncLoop(scope: CoroutineScope, intervalSeconds: Long = 30): Flow<PunchEvent> = callbackFlow {
        val job = scope.launch(Dispatchers.IO) {
            while (isRunning && isActive) {
                try {
                    val sinceId = prefs.getLong("since_id", 0)
                    val encodedSn = URLEncoder.encode(deviceSn, "UTF-8")
                    val url = URL("$serverUrl/api/mobile-sync/recent-tokens?SN=$encodedSn&since_id=$sinceId")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 8000
                        readTimeout = 8000
                    }

                    if (conn.responseCode == 200) {
                        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(responseText)
                        val tokensArray = json.optJSONArray("tokens")
                        val maxId = json.optLong("max_id", sinceId)

                        if (tokensArray != null) {
                            for (i in 0 until tokensArray.length()) {
                                val t = tokensArray.getJSONObject(i)
                                val event = PunchEvent(
                                    serialNo = t.optInt("token_number", 0),
                                    empId = t.optString("emp_id", ""),
                                    name = t.optString("name", "").ifBlank { "Unknown" },
                                    department = t.optString("department", ""),
                                    mealType = t.optString("meal", "").uppercase(),
                                    punchTime = t.optString("issued_at", ""),
                                    verificationMode = if (t.optString("source", "device") == "manual") "Manual Token" else "Synced",
                                    photoPath = null,  // resolved from the local employee cache at display time, not carried in the sync payload itself
                                    isRejected = false,
                                    source = t.optString("source", "cloud")
                                )
                                trySend(event)
                            }
                        }
                        if (maxId > sinceId) {
                            prefs.edit().putLong("since_id", maxId).apply()
                        }
                    }
                    conn.disconnect()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(intervalSeconds * 1000)
            }
        }
        awaitClose { job.cancel() }
    }

    fun stop() {
        isRunning = false
    }
}
