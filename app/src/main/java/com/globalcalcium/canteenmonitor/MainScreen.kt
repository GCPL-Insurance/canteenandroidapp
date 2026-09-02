package com.globalcalcium.canteenmonitor.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.globalcalcium.canteenmonitor.data.AppDatabase
import com.globalcalcium.canteenmonitor.data.Employee
import com.globalcalcium.canteenmonitor.data.PunchEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// ── Theme (bright palette) ─────────────────────────────────────────────────
private val BgColor = Color(0xFFF1F5F9)
private val CardColor = Color.White
private val HeaderColor = Color(0xFF0F766E)
private val HeaderColorDark = Color(0xFF115E59)
private val TextPrimary = Color(0xFF0F172A)
private val TextMuted = Color(0xFF64748B)
private val AccentGreen = Color(0xFF059669)
private val AccentAmber = Color(0xFFB45309)
private val AccentBlue = Color(0xFF2563EB)
private val AccentPurple = Color(0xFF7C3AED)
private val RowBg = Color(0xFFF8FAFC)

/**
 * Which full screen is currently showing. Settings used to be an AlertDialog —
 * replaced entirely, not just patched again, because the bug wasn't really about
 * scrolling: a scrollable Column inside a dialog with no bounded height creates a
 * conflicting size measurement in Compose, and it resolves by squishing later
 * children toward zero height, which is exactly what the screenshot showed even
 * with verticalScroll already in place. A full screen has the whole display's
 * bounded height to work with from the start, which sidesteps the entire class of
 * bug rather than trying to patch around it a third time.
 */
private enum class Screen { DASHBOARD, SETTINGS, HISTORY, ADMIN_LOGIN, DATABASE_VIEWER, RAW_DATA }

/**
 * BUGFIX (Aug-2026): MainActivity has its own todayDateStamp() but it's a
 * file-private function in a different package (com.globalcalcium.canteenmonitor
 * vs .ui here) -- not accessible from this file at all. Duplicated locally
 * rather than made cross-file-visible, matching this codebase's existing pattern
 * for small helpers (parsePunchBlock is similarly duplicated between
 * TcpPunchListener and UsbSerialPunchListener rather than shared).
 */
private fun todayDateStamp(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

@Composable
fun CanteenDashboard(
    latestPunch: PunchEvent?,
    latestRejection: PunchEvent?,
    punchHistory: List<PunchEvent>,
    totalCount: Int,
    rawLog: List<String>,
    connectionStatus: String?,
    serialIp: String,
    serialPort: String,
    serverUrl: String,
    deviceSn: String,
    connectionMode: String,
    usbBaudRate: String,
    database: AppDatabase,
    onSaveSettings: (String, String, String, String, String, String) -> Unit
) {
    var screen by remember { mutableStateOf(Screen.DASHBOARD) }

    // FEATURE (Aug-2026): "count should reset at midnight, new day starts fresh"
    // — filtered to today's dateStamp, not all-time cumulative history (which is
    // still exactly what History/the database show, unchanged). Re-checked every
    // minute via LaunchedEffect so the reset actually happens at midnight itself,
    // not just whenever the next punch happens to arrive — a canteen sitting idle
    // right at midnight would otherwise keep showing yesterday's numbers
    // indefinitely until the next punch nudged a recomposition.
    var currentDate by remember { mutableStateOf(todayDateStamp()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            currentDate = todayDateStamp()
        }
    }

    val todaysPunches = punchHistory.filter { it.dateStamp == currentDate }
    val breakfastCount = todaysPunches.count { it.mealType.equals("BREAKFAST", ignoreCase = true) }
    val lunchCount = todaysPunches.count { it.mealType.equals("LUNCH", ignoreCase = true) }
    val dinnerCount = todaysPunches.count { it.mealType.equals("DINNER", ignoreCase = true) }
    // BUGFIX (Aug-2026): PUNCH TOTAL in the header used to be the all-time
    // cumulative total while the meal breakdown below it was daily-only, which
    // would show two numbers that visibly don't add up (e.g. "Total: 5000" next
    // to "Breakfast 12 / Lunch 8 / Dinner 3"). Deriving it the same way keeps
    // both halves of the dashboard consistent with each other.
    val todayTotalCount = breakfastCount + lunchCount + dinnerCount

    // FEATURE (Aug-2026): "show invalid/rejected verification... popup with red"
    // — shown as a global overlay regardless of which screen is currently open,
    // so a rejection is never missed just because the operator happened to be
    // browsing Settings or History at that moment. Tracks the last-dismissed
    // rejection's own row id so re-composition doesn't keep re-showing the same
    // one after it's already been acknowledged.
    var dismissedRejectionId by remember { mutableStateOf<Long?>(null) }
    val showRejectionPopup = latestRejection != null && latestRejection.id != dismissedRejectionId

    Surface(modifier = Modifier.fillMaxSize(), color = BgColor) {
        when (screen) {
            Screen.SETTINGS -> SettingsScreen(
                currentSerialIp = serialIp,
                currentSerialPort = serialPort,
                currentServerUrl = serverUrl,
                currentDeviceSn = deviceSn,
                currentConnectionMode = connectionMode,
                currentUsbBaudRate = usbBaudRate,
                onBack = { screen = Screen.DASHBOARD },
                onSave = { ip, port, url, sn, mode, baud ->
                    onSaveSettings(ip, port, url, sn, mode, baud)
                    screen = Screen.DASHBOARD
                }
            )
            Screen.HISTORY -> HistoryScreen(
                punchHistory = punchHistory,
                onBack = { screen = Screen.DASHBOARD }
            )
            Screen.ADMIN_LOGIN -> AdminLoginScreen(
                onBack = { screen = Screen.DASHBOARD },
                onLoggedIn = { screen = Screen.DATABASE_VIEWER }
            )
            Screen.DATABASE_VIEWER -> DatabaseViewerScreen(
                database = database,
                onBack = { screen = Screen.DASHBOARD }
            )
            Screen.RAW_DATA -> RawDataScreen(
                rawLog = rawLog,
                connectionMode = connectionMode,
                onBack = { screen = Screen.DASHBOARD }
            )
            Screen.DASHBOARD -> DashboardScreen(
                latestPunch = latestPunch,
                punchHistory = punchHistory,
                totalCount = todayTotalCount,
                breakfastCount = breakfastCount,
                lunchCount = lunchCount,
                dinnerCount = dinnerCount,
                connectionStatus = connectionStatus,
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenHistory = { screen = Screen.HISTORY },
                onOpenAdmin = { screen = Screen.ADMIN_LOGIN },
                onOpenRawData = { screen = Screen.RAW_DATA }
            )
        }

        if (showRejectionPopup && latestRejection != null) {
            RejectionPopup(
                rejection = latestRejection,
                onDismiss = { dismissedRejectionId = latestRejection.id }
            )
        }
    }
}

/**
 * FEATURE (Aug-2026): full-screen red alert for a failed verification. Auto
 * dismisses after 6 seconds on its own (LaunchedEffect keyed to the rejection's
 * own id, so a NEW rejection arriving resets the timer rather than inheriting
 * whatever was left of the previous one's countdown) — but can also be dismissed
 * immediately by tapping anywhere, since a busy serving counter shouldn't be
 * blocked waiting on a timer if staff have already seen it.
 */
@Composable
private fun RejectionPopup(rejection: PunchEvent, onDismiss: () -> Unit) {
    LaunchedEffect(rejection.id) {
        kotlinx.coroutines.delay(6000)
        onDismiss()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC7F1D1D))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFDC2626)),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Column(
                modifier = Modifier.padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("✕ ACCESS DENIED", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp)
                Text("Emp ID: ${rejection.empId}", color = Color.White, fontSize = 20.sp)
                if (!rejection.rejectionReason.isNullOrBlank()) {
                    Text(rejection.rejectionReason, color = Color(0xFFFECACA), fontSize = 16.sp)
                }
                Text(rejection.punchTime, color = Color(0xFFFECACA), fontSize = 14.sp)
                Text("(tap anywhere to dismiss)", color = Color(0xFFFECACA), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    latestPunch: PunchEvent?,
    punchHistory: List<PunchEvent>,
    totalCount: Int,
    breakfastCount: Int,
    lunchCount: Int,
    dinnerCount: Int,
    connectionStatus: String?,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAdmin: () -> Unit,
    onOpenRawData: () -> Unit
) {
    // FEATURE (Aug-2026): "touch and see the full pic and back to normal
    // dashboard" — tapping a row in Last 5 opens this person's photo full-screen;
    // setting it back to null returns to the normal dashboard.
    var fullScreenPhoto by remember { mutableStateOf<PunchEvent?>(null) }

    // FEATURE (Aug-2026): "full screen mode for token... admin raw rows and
    // breakfast lunch dinner total rows should be hidden" — a single toggle for
    // a minimal, distraction-free view showing only the current token and Last 5.
    // A small button stays reachable even in focused mode so it can be turned
    // back off.
    var focusedMode by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {

        // FEATURE (Aug-2026): "show warning/error on main window" if USB/network
        // disconnects or fails — shown regardless of focused mode, since a
        // connection problem is exactly the kind of thing that shouldn't be
        // hideable.
        if (connectionStatus != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFDC2626), RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(connectionStatus, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (!focusedMode) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(HeaderColor, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.size(12.dp).background(Color(0xFF6EE7B7), CircleShape))
                Column {
                    Text("GLOBAL CALCIUM PVT LTD", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Text("Canteen Live Verification Terminal", color = Color(0xFFCCFBF1), fontSize = 12.sp)
                }
            }

            // BUGFIX (Aug-2026): this row has grown to 5 buttons plus a text
            // column across several rounds (History, Raw Data, Admin, Settings,
            // Focus added one at a time) without ever checking whether they all
            // actually fit within the header's width. On anything narrower than
            // a wide tablet, the later buttons -- Focus being the very last one
            // added -- could silently overflow off-screen with no visual
            // indication anything was cut off, which is almost certainly why it
            // wasn't visible. horizontalScroll guarantees every button stays
            // reachable regardless of screen width, same reasoning as the
            // Settings screen scroll fix from earlier.
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("PUNCH TOTAL", color = Color(0xFFCCFBF1), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("$totalCount", color = Color(0xFF6EE7B7), fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                }
                Button(
                    onClick = onOpenHistory,
                    colors = ButtonDefaults.buttonColors(containerColor = HeaderColorDark),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("📋 History", color = Color.White, fontSize = 12.sp)
                }
                Button(
                    onClick = onOpenRawData,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB45309)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("🔍 Raw Data", color = Color.White, fontSize = 12.sp)
                }
                Button(
                    onClick = onOpenAdmin,
                    colors = ButtonDefaults.buttonColors(containerColor = HeaderColorDark),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("🔐 Admin", color = Color.White, fontSize = 12.sp)
                }
                Button(
                    onClick = onOpenSettings,
                    colors = ButtonDefaults.buttonColors(containerColor = HeaderColorDark),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("⚙ Settings", color = Color.White, fontSize = 12.sp)
                }
                Button(
                    onClick = { focusedMode = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF115E59)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("🔲 Focus", color = Color.White, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Meal-wise breakdown strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MealCountCard("🌅 BREAKFAST", breakfastCount, AccentAmber, Modifier.weight(1f))
            MealCountCard("☀️ LUNCH", lunchCount, AccentBlue, Modifier.weight(1f))
            MealCountCard("🌙 DINNER", dinnerCount, AccentPurple, Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(10.dp))
        } else {
            // FEATURE (Aug-2026): small, unobtrusive way back to the normal
            // dashboard from focused mode — deliberately compact so it doesn't
            // reintroduce the visual clutter focused mode is meant to remove.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = { focusedMode = false },
                    colors = ButtonDefaults.buttonColors(containerColor = HeaderColorDark),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("⤢ Exit Focus", color = Color.White, fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Main Content Area
        Row(modifier = Modifier.fillMaxSize()) {

            // Spotlight Left Card
            Card(
                modifier = Modifier.weight(0.42f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = CardColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (latestPunch != null) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Text("TOKEN #${latestPunch.serialNo}", color = AccentGreen, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp)

                        // FEATURE (Aug-2026): "increase the size for visibility" —
                        // enlarged from 170x210 to 230x280, and made tappable
                        // (same full-screen photo viewer as the Last 5 list).
                        val photoModel = if (!latestPunch.photoPath.isNullOrEmpty()) File(latestPunch.photoPath) else "https://via.placeholder.com/230x280/e2e8f0/64748b?text=No+Photo"
                        AsyncImage(
                            model = photoModel,
                            contentDescription = null,
                            modifier = Modifier
                                .size(230.dp, 280.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { fullScreenPhoto = latestPunch },
                            contentScale = ContentScale.Crop
                        )

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(latestPunch.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                            Text(latestPunch.department, color = TextMuted, fontSize = 16.sp)
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            InfoItem("EMP ID", latestPunch.empId)
                            InfoItem("MEAL", latestPunch.mealType, AccentAmber)
                            InfoItem("TIME", latestPunch.punchTime)
                        }

                        // FEATURE (Aug-2026): verification mode badge — a vendor
                        // benefits from seeing HOW someone was verified (face vs
                        // fingerprint vs card), not just that they were.
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFECFDF5), RoundedCornerShape(20.dp))
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("✓ Verified via ${latestPunch.verificationMode}", color = AccentGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Waiting for punch signal...", color = TextMuted, fontSize = 16.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Last 5 tokens — large, made explicitly for at-a-glance reading
            // from a serving counter a few feet away. Full history is behind
            // the 📋 History screen above, not crammed into this list too.
            // FEATURE (Aug-2026): thumbnail photo per row, tap anywhere on a row
            // to see that person's photo full-screen.
            Card(
                modifier = Modifier.weight(0.58f).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = CardColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Text("LAST 5 SERVED", color = TextMuted, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp, start = 4.dp))
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(punchHistory.take(5)) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .background(RowBg, RoundedCornerShape(12.dp))
                                    .clickable { fullScreenPhoto = item }
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val thumbModel = if (!item.photoPath.isNullOrEmpty()) File(item.photoPath) else null
                                if (thumbModel != null) {
                                    AsyncImage(
                                        model = thumbModel,
                                        contentDescription = null,
                                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                }
                                Text("#${item.serialNo}", color = AccentGreen, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
                                Column(horizontalAlignment = Alignment.Start, modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    Text(item.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 23.sp)
                                    Text(item.empId, color = TextMuted, fontSize = 16.sp)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(item.mealType, color = AccentAmber, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                    Text(item.punchTime, color = TextMuted, fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (fullScreenPhoto != null) {
        FullScreenPhotoViewer(event = fullScreenPhoto!!, onBack = { fullScreenPhoto = null })
    }
}

/**
 * FEATURE (Aug-2026): "touch and see the full pic and back to normal dashboard" —
 * tapping anywhere (or the explicit Back button) returns to the dashboard exactly
 * as it was, since this is an overlay rather than a real screen navigation.
 */
@Composable
private fun FullScreenPhotoViewer(event: PunchEvent, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .clickable(onClick = onBack),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val photoModel = if (!event.photoPath.isNullOrEmpty()) File(event.photoPath) else "https://via.placeholder.com/400x500/1e293b/94a3b8?text=No+Photo"
            AsyncImage(
                model = photoModel,
                contentDescription = null,
                modifier = Modifier.size(420.dp, 520.dp).clip(RoundedCornerShape(24.dp)),
                contentScale = ContentScale.Crop
            )
            Text(event.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 28.sp)
            Text("Emp ID: ${event.empId}  •  Token #${event.serialNo}", color = Color(0xFFCBD5E1), fontSize = 18.sp)
            Text("${event.mealType} • ${event.punchTime}", color = Color(0xFF94A3B8), fontSize = 15.sp)
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = HeaderColor),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("← Back to Dashboard", color = Color.White, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun MealCountCard(label: String, count: Int, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = CardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("$count", color = color, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
fun InfoItem(label: String, value: String, valueColor: Color = TextPrimary) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) HeaderColor else RowBg,
            contentColor = if (selected) Color.White else TextMuted
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

/**
 * FEATURE (Aug-2026): "review previous tokens" — full-screen now (previously a
 * dialog capped at a fixed height). LazyColumn is safe on its own here (it
 * manages its own scrolling internally, unlike a raw Column+verticalScroll) — the
 * move to a full screen is mainly for consistency with Settings and to give a
 * long list more room to breathe, not because this one had the same bug.
 */
/**
 * FEATURE (Aug-2026): "raw parser for testing purpose" — shows exactly what's
 * arriving from whichever connection is active (network or USB), completely
 * unprocessed, plus the connection's own status (connected, permission denied,
 * no compatible device, read errors). This settles definitively whether a "no
 * data" problem is "nothing is arriving at all" vs "data arrives but doesn't
 * parse" — instead of guessing between those two very different problems.
 */
@Composable
private fun RawDataScreen(rawLog: List<String>, connectionMode: String, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFB45309), RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("🔍 Raw Data (Diagnostic)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    "Mode: ${if (connectionMode == "usb_direct") "Direct USB" else "Network (LAN)"}",
                    color = Color(0xFFFEF3C7), fontSize = 12.sp
                )
            }
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF92400E)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("← Back", color = Color.White, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            "Shows exactly what's arriving, unprocessed. [STATUS] lines are connection events; [RAW] lines are the literal bytes received. If you see no lines at all, nothing is reaching the app — that's a connection/hardware issue, not a parsing one. If you see [RAW] lines but no punches ever appear on the dashboard, that's a parsing issue with whatever format is showing here.",
            color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp)
        )

        Card(
            modifier = Modifier.fillMaxSize(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            if (rawLog.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing received yet.", color = Color(0xFF94A3B8), fontSize = 15.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                    items(rawLog) { line ->
                        Text(
                            line,
                            color = when {
                                line.contains("[STATUS]") -> Color(0xFFFBBF24)
                                line.contains("Access Granted") -> Color(0xFF6EE7B7)
                                else -> Color(0xFFA5B4FC)
                            },
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(punchHistory: List<PunchEvent>, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(HeaderColor, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Token History (${punchHistory.size} total)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = HeaderColorDark),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("← Back", color = Color.White, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxSize(),
            colors = CardDefaults.cardColors(containerColor = CardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            if (punchHistory.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No tokens recorded yet", color = TextMuted, fontSize = 16.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    items(punchHistory) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .background(RowBg, RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("#${item.serialNo}", color = AccentGreen, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(item.empId, color = TextMuted, fontSize = 14.sp)
                            Text(item.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                            Text(item.mealType, color = AccentAmber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(item.punchTime, color = TextMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * FEATURE (Aug-2026): rebuilt from a dialog into a full screen. This is the fix
 * that actually matters this time -- a full screen gets the whole display's
 * bounded height from the very start, so a Column+verticalScroll here behaves
 * correctly and reliably, unlike inside an AlertDialog's ambiguously-sized
 * content slot.
 */
@Composable
private fun SettingsScreen(
    currentSerialIp: String,
    currentSerialPort: String,
    currentServerUrl: String,
    currentDeviceSn: String,
    currentConnectionMode: String,
    currentUsbBaudRate: String,
    onBack: () -> Unit,
    onSave: (String, String, String, String, String, String) -> Unit
) {
    var ip by remember { mutableStateOf(currentSerialIp) }
    var port by remember { mutableStateOf(currentSerialPort) }
    var url by remember { mutableStateOf(currentServerUrl) }
    var sn by remember { mutableStateOf(currentDeviceSn) }
    var mode by remember { mutableStateOf(currentConnectionMode) }
    var baud by remember { mutableStateOf(currentUsbBaudRate) }

    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(HeaderColor, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Hardware & Server Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = HeaderColorDark),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("← Cancel", color = Color.White, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = CardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // FEATURE (Aug-2026): connection mode -- Network (existing
                // RS232-to-LAN converter) vs Direct USB (RS485-to-USB converter
                // into the tablet's USB-C port). Whether USB mode actually works
                // depends on hardware this app can't verify on its own (does the
                // tablet support USB host/OTG mode, and is the converter's chip
                // one of the four this app recognizes) -- Network stays the
                // default so nothing changes unless this is deliberately switched.
                Text("CONNECTION MODE", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ModeButton("🌐 Network (LAN)", selected = mode == "network", modifier = Modifier.weight(1f)) { mode = "network" }
                    ModeButton("🔌 Direct USB", selected = mode == "usb_direct", modifier = Modifier.weight(1f)) { mode = "usb_direct" }
                }

                if (mode == "network") {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("SERIAL CONVERTER (RS232 → LAN)", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = ip, onValueChange = { ip = it },
                        label = { Text("USR Serial Converter IP") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = port, onValueChange = { port = it },
                        label = { Text("USR Port (e.g. 8234)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                } else {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("DIRECT USB (RS485 → USB-C)", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Requires the tablet's USB-C port to support USB Host/OTG mode, and a converter using a recognized chipset (FTDI, CP210x, CH340, or PL2303). Not all tablets support this — check your device's spec sheet if it doesn't connect.",
                        color = TextMuted, fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = baud, onValueChange = { baud = it },
                        label = { Text("Baud Rate (e.g. 9600)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text("SERVER SYNC", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("AWS ADMS Server URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = sn, onValueChange = { sn = it },
                    label = { Text("eSSL Emulated Serial No") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onSave(ip, port, url, sn, mode, baud) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HeaderColor),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Save & Reconnect", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * FEATURE (Aug-2026): admin login gate for the database viewer. There's exactly
 * one admin account (no separate username needed) — password stored in
 * SharedPreferences, default "admin", changeable from the viewer screen once
 * logged in. This is a low-stakes internal tool (a serving-counter display, not
 * a public-facing system), so a straightforward stored-password check is
 * proportionate — not treated as a hardened security boundary.
 */
@Composable
private fun AdminLoginScreen(onBack: () -> Unit, onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("admin_settings", Context.MODE_PRIVATE) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(HeaderColor, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Admin Login", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = HeaderColorDark),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("← Cancel", color = Color.White, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "Sign in to view synced employee data and confirm what's actually stored on this device.",
                    color = TextMuted, fontSize = 13.sp
                )
                Text("Username: admin", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = error != null
                )
                if (error != null) {
                    Text(error!!, color = Color(0xFFDC2626), fontSize = 13.sp)
                }
                Button(
                    onClick = {
                        val stored = prefs.getString("admin_password", "admin") ?: "admin"
                        if (password == stored) {
                            onLoggedIn()
                        } else {
                            error = "Incorrect password"
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HeaderColor),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Login", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * FEATURE (Aug-2026): "see the database data like employee ID" — exactly what
 * this screen shows, so a vendor/admin can confirm on the device itself whether
 * a push from the admin portal actually arrived and was stored, without needing
 * any external tool.
 */
@Composable
private fun DatabaseViewerScreen(database: AppDatabase, onBack: () -> Unit) {
    val context = LocalContext.current
    var employees by remember { mutableStateOf<List<Employee>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showChangePassword by remember { mutableStateOf(false) }
    // FEATURE (Aug-2026): "take photo and save for the user" -- admin-only
    // capture flow, for when a synced photo is missing or wrong.
    var showPhotoCapture by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        employees = withContext(Dispatchers.IO) { database.employeeDao().getAll() }
        isLoading = false
    }

    if (showPhotoCapture) {
        AdminPhotoCaptureScreen(
            onBack = {
                showPhotoCapture = false
                // Refresh the list on return in case the captured photo's
                // employee record needs to reflect it (photo lookup itself is
                // file-path-based and picks it up automatically either way, but
                // this keeps the visible "✓ Synced" status accurate immediately
                // rather than waiting for the next full screen reload).
            }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(HeaderColor, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Synced Employees (${employees.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { showPhotoCapture = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("📷 Take Photo", color = Color.White, fontSize = 13.sp)
                }
                Button(
                    onClick = { showChangePassword = true },
                    colors = ButtonDefaults.buttonColors(containerColor = HeaderColorDark),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("🔑 Change Password", color = Color.White, fontSize = 13.sp)
                }
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = HeaderColorDark),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("← Back", color = Color.White, fontSize = 13.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxSize(),
            colors = CardDefaults.cardColors(containerColor = CardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            when {
                isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = HeaderColor)
                }
                employees.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No employees synced yet.\nPush employee data from the admin portal, then check back here.",
                        color = TextMuted, fontSize = 15.sp, textAlign = TextAlign.Center
                    )
                }
                else -> Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("EMP ID", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("NAME", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("PHOTO", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(employees) { emp ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .background(RowBg, RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(emp.empId, color = AccentGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(emp.name, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                                Text(
                                    if (!emp.photoPath.isNullOrEmpty() && File(emp.photoPath).exists()) "✓ Synced" else "✗ Missing",
                                    color = if (!emp.photoPath.isNullOrEmpty() && File(emp.photoPath).exists()) AccentGreen else Color(0xFFDC2626),
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showChangePassword) {
        ChangePasswordDialog(onDismiss = { showChangePassword = false })
    }
}

@Composable
private fun ChangePasswordDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("admin_settings", Context.MODE_PRIVATE) }
    var current by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Admin Password", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (success) {
                    Text("Password updated.", color = AccentGreen, fontWeight = FontWeight.Bold)
                } else {
                    OutlinedTextField(
                        value = current, onValueChange = { current = it; error = null },
                        label = { Text("Current Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = newPass, onValueChange = { newPass = it; error = null },
                        label = { Text("New Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = confirm, onValueChange = { confirm = it; error = null },
                        label = { Text("Confirm New Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    if (error != null) {
                        Text(error!!, color = Color(0xFFDC2626), fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (!success) {
                Button(onClick = {
                    val stored = prefs.getString("admin_password", "admin") ?: "admin"
                    when {
                        current != stored -> error = "Current password is incorrect"
                        newPass.isBlank() -> error = "New password can't be empty"
                        newPass != confirm -> error = "New password and confirmation don't match"
                        else -> {
                            prefs.edit().putString("admin_password", newPass).apply()
                            success = true
                        }
                    }
                }) { Text("Update") }
            } else {
                Button(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            if (!success) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

/**
 * FEATURE (Aug-2026): "take photo and save for the user additional features on
 * admin login" -- capture a photo directly on the tablet's own camera and save
 * it for a specific employee. Uses TakePicturePreview (returns a Bitmap
 * directly) rather than TakePicture (which needs a FileProvider set up in the
 * manifest) -- simpler and lower-risk given this can't be compile-tested here.
 * Saves to the exact same path (filesDir/photos/$empId.jpg) the rest of the app
 * already reads from, so it's picked up automatically on the next punch for
 * that employee with no other wiring needed.
 */
@Composable
private fun AdminPhotoCaptureScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var empIdInput by remember { mutableStateOf("") }
    var capturedBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var hasCameraPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap -> capturedBitmap = bitmap }

    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF7C3AED), RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📷 Take Photo for Employee", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5B21B6)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("← Back", color = Color.White, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = CardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = empIdInput,
                    onValueChange = { empIdInput = it; statusMessage = null },
                    label = { Text("Employee ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (capturedBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = capturedBitmap!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(220.dp, 270.dp).clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.size(220.dp, 270.dp).background(RowBg, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No photo captured yet", color = TextMuted, fontSize = 13.sp)
                    }
                }

                Button(
                    onClick = {
                        if (hasCameraPermission) {
                            // BUGFIX (Aug-2026): this app explicitly also targets
                            // Android TV boxes (per the HDMI display use case
                            // described earlier in this project), many of which
                            // have no camera hardware or camera app installed at
                            // all. TakePicturePreview launches an implicit
                            // MediaStore.ACTION_IMAGE_CAPTURE intent -- with
                            // nothing on the device able to handle it, .launch()
                            // throws ActivityNotFoundException synchronously,
                            // which would otherwise crash the whole app rather
                            // than just fail this one feature gracefully.
                            try {
                                // BUGFIX (Aug-2026): TakePicturePreview's contract
                                // is typed <Void?, Bitmap?> -- it needs no actual
                                // input data, but Kotlin's type system still
                                // requires an explicit argument matching that
                                // type, which can only ever be null. launch()
                                // with no argument at all doesn't compile against
                                // this specific contract.
                                cameraLauncher.launch(null)
                            } catch (e: android.content.ActivityNotFoundException) {
                                statusMessage = "No camera app available on this device"
                            }
                        } else {
                            permissionLauncher.launch(android.Manifest.permission.CAMERA)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(if (capturedBitmap == null) "📷 Open Camera" else "📷 Retake", color = Color.White, fontSize = 15.sp)
                }

                Button(
                    onClick = {
                        val empId = empIdInput.trim()
                        val bitmap = capturedBitmap
                        when {
                            empId.isBlank() -> statusMessage = "Enter an Employee ID first"
                            bitmap == null -> statusMessage = "Take a photo first"
                            else -> {
                                try {
                                    val photosDir = java.io.File(context.filesDir, "photos")
                                    if (!photosDir.exists()) photosDir.mkdirs()
                                    val photoFile = java.io.File(photosDir, "$empId.jpg")
                                    java.io.FileOutputStream(photoFile).use { out ->
                                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                                    }
                                    statusMessage = "✓ Saved for Employee $empId"
                                    empIdInput = ""
                                    capturedBitmap = null
                                } catch (e: Exception) {
                                    statusMessage = "Failed to save: ${e.message}"
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("💾 Save Photo", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }

                if (statusMessage != null) {
                    Text(
                        statusMessage!!,
                        color = if (statusMessage!!.startsWith("✓")) AccentGreen else Color(0xFFDC2626),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                    )
                }

                if (!hasCameraPermission) {
                    Text(
                        "Camera permission is needed to take a photo — tap Open Camera to grant it.",
                        color = TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
