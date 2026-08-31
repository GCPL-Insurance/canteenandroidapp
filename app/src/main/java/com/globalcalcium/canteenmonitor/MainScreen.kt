package com.globalcalcium.canteenmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.globalcalcium.canteenmonitor.data.PunchEvent
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
private enum class Screen { DASHBOARD, SETTINGS, HISTORY }

@Composable
fun CanteenDashboard(
    latestPunch: PunchEvent?,
    punchHistory: List<PunchEvent>,
    totalCount: Int,
    serialIp: String,
    serialPort: String,
    serverUrl: String,
    deviceSn: String,
    onSaveSettings: (String, String, String, String) -> Unit
) {
    var screen by remember { mutableStateOf(Screen.DASHBOARD) }

    // FEATURE (Aug-2026): "verification overall count" — meal-wise breakdown, not
    // just one grand total. Derived directly from punchHistory (mealType is
    // already recorded per punch), no new data plumbing needed in MainActivity.
    val breakfastCount = punchHistory.count { it.mealType.equals("BREAKFAST", ignoreCase = true) }
    val lunchCount = punchHistory.count { it.mealType.equals("LUNCH", ignoreCase = true) }
    val dinnerCount = punchHistory.count { it.mealType.equals("DINNER", ignoreCase = true) }

    Surface(modifier = Modifier.fillMaxSize(), color = BgColor) {
        when (screen) {
            Screen.SETTINGS -> SettingsScreen(
                currentSerialIp = serialIp,
                currentSerialPort = serialPort,
                currentServerUrl = serverUrl,
                currentDeviceSn = deviceSn,
                onBack = { screen = Screen.DASHBOARD },
                onSave = { ip, port, url, sn ->
                    onSaveSettings(ip, port, url, sn)
                    screen = Screen.DASHBOARD
                }
            )
            Screen.HISTORY -> HistoryScreen(
                punchHistory = punchHistory,
                onBack = { screen = Screen.DASHBOARD }
            )
            Screen.DASHBOARD -> DashboardScreen(
                latestPunch = latestPunch,
                punchHistory = punchHistory,
                totalCount = totalCount,
                breakfastCount = breakfastCount,
                lunchCount = lunchCount,
                dinnerCount = dinnerCount,
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenHistory = { screen = Screen.HISTORY }
            )
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
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {

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

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
                    onClick = onOpenSettings,
                    colors = ButtonDefaults.buttonColors(containerColor = HeaderColorDark),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("⚙ Settings", color = Color.White, fontSize = 12.sp)
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
                        Text("TOKEN #${latestPunch.serialNo}", color = AccentGreen, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)

                        val photoModel = if (!latestPunch.photoPath.isNullOrEmpty()) File(latestPunch.photoPath) else "https://via.placeholder.com/200x240/e2e8f0/64748b?text=No+Photo"
                        AsyncImage(
                            model = photoModel,
                            contentDescription = null,
                            modifier = Modifier.size(170.dp, 210.dp).clip(RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.Crop
                        )

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(latestPunch.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text(latestPunch.department, color = TextMuted, fontSize = 14.sp)
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
                                    .padding(vertical = 5.dp)
                                    .background(RowBg, RoundedCornerShape(10.dp))
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("#${item.serialNo}", color = AccentGreen, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                                Column(horizontalAlignment = Alignment.Start, modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    Text(item.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                    Text(item.empId, color = TextMuted, fontSize = 15.sp)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(item.mealType, color = AccentAmber, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Text(item.punchTime, color = TextMuted, fontSize = 15.sp)
                                }
                            }
                        }
                    }
                }
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

/**
 * FEATURE (Aug-2026): "review previous tokens" — full-screen now (previously a
 * dialog capped at a fixed height). LazyColumn is safe on its own here (it
 * manages its own scrolling internally, unlike a raw Column+verticalScroll) — the
 * move to a full screen is mainly for consistency with Settings and to give a
 * long list more room to breathe, not because this one had the same bug.
 */
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
    onBack: () -> Unit,
    onSave: (String, String, String, String) -> Unit
) {
    var ip by remember { mutableStateOf(currentSerialIp) }
    var port by remember { mutableStateOf(currentSerialPort) }
    var url by remember { mutableStateOf(currentServerUrl) }
    var sn by remember { mutableStateOf(currentDeviceSn) }

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
                Text("SERIAL CONVERTER", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                    onClick = { onSave(ip, port, url, sn) },
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
