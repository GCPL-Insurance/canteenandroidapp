package com.globalcalcium.canteenmonitor.network

import android.content.Context
import com.globalcalcium.canteenmonitor.data.Employee
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import android.util.Base64

/**
 * FEATURE (Aug-2026): "complete different sync method... change both the ends" —
 * the original AdmsClient emulates a real ZK/eSSL biometric device polling
 * getrequest.aspx, inheriting every constraint that protocol carries for real
 * hardware: an 8KB response cap (one photo per response, since a base64 JPEG is
 * ~30-70KB), the has_face confirmation gate, and strict USERINFO-before-BIOPHOTO
 * ordering. None of that applies to this tablet — no tiny hardware buffer, no
 * ghost-user risk (this is a display, not an access-control device). This talks
 * to a dedicated backend endpoint (/api/mobile-sync/employees-bulk) built
 * specifically for this app: one HTTP call returns many full employee+photo
 * records at once, dramatically cutting the number of round-trips needed for an
 * initial sync of a large roster (confirmed on the backend: 60 employees with
 * real photos synced completely in 3 calls instead of 60+ individual polls).
 *
 * "Burst mode": while the server reports more data available (has_more), this
 * fetches the next batch immediately with no delay — this is what makes an
 * initial sync of a large roster fast. Once caught up (has_more=false), it falls
 * back to periodic polling at the normal interval, since at that point there's
 * nothing to catch up on, just occasional updates to pick up.
 */
class FastBulkSyncClient(
    private val context: Context,
    private val serverUrl: String,
    private val deviceSn: String
) {
    private var isRunning = true
    private val prefs = context.getSharedPreferences("fast_bulk_sync", Context.MODE_PRIVATE)

    fun startSyncLoop(scope: CoroutineScope, steadyStateIntervalSeconds: Long = 30): Flow<Employee> = callbackFlow {
        val job = scope.launch(Dispatchers.IO) {
            while (isRunning && isActive) {
                try {
                    val since = prefs.getString("since", "1970-01-01 00:00:00") ?: "1970-01-01 00:00:00"
                    val encodedSn = URLEncoder.encode(deviceSn, "UTF-8")
                    val url = URL("$serverUrl/api/mobile-sync/employees-bulk?SN=$encodedSn&since=${URLEncoder.encode(since, "UTF-8")}&limit=25")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15000
                        readTimeout = 20000  // a 25-employee batch with photos is a meaningfully larger payload than a single small poll
                    }

                    var hasMore = false
                    if (conn.responseCode == 200) {
                        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(responseText)
                        val employeesArray = json.optJSONArray("employees")
                        val nextSince = json.optString("next_since", since)
                        hasMore = json.optBoolean("has_more", false)

                        if (employeesArray != null) {
                            for (i in 0 until employeesArray.length()) {
                                val e = employeesArray.getJSONObject(i)
                                val empId = e.optString("emp_id", "")
                                if (empId.isEmpty()) continue
                                val photoB64 = e.optString("photo_base64", "")
                                var photoPath: String? = null
                                if (photoB64.isNotBlank()) {
                                    photoPath = savePhoto(empId, photoB64)
                                }
                                trySend(Employee(
                                    empId = empId,
                                    name = e.optString("name", "").ifBlank { "Employee $empId" },
                                    department = e.optString("department", "General"),
                                    photoPath = photoPath
                                ))
                            }
                        }
                        if (nextSince > since || employeesArray?.length() == 0) {
                            prefs.edit().putString("since", nextSince).apply()
                        }
                    }
                    conn.disconnect()

                    // Burst mode: no delay while there's more to catch up on.
                    if (!hasMore) {
                        delay(steadyStateIntervalSeconds * 1000)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                    delay(steadyStateIntervalSeconds * 1000)
                }
            }
        }
        awaitClose { job.cancel() }
    }

    private fun savePhoto(empId: String, base64Data: String): String? {
        return try {
            val cleanBase64 = base64Data.substringAfter("base64,").trim()
            val imageBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
            val photosDir = File(context.filesDir, "photos")
            if (!photosDir.exists()) photosDir.mkdirs()
            val photoFile = File(photosDir, "$empId.jpg")
            FileOutputStream(photoFile).use { it.write(imageBytes) }
            photoFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun stop() {
        isRunning = false
    }
}
