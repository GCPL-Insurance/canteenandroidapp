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
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    // FEATURE (Aug-2026): bright theme, per explicit request — replaced the dark
    // navy/near-black palette with a light background and dark text throughout.
    // Accent colors (green for success/token, amber for meal type) are kept, just
    // deepened slightly so they still have enough contrast against a light
    // background instead of the dark one they were tuned for originally.
    val bgColor = Color(0xFFF1F5F9)
    val cardColor = Color.White
    val headerColor = Color(0xFF0F766E)
    val textPrimary = Color(0xFF0F172A)
    val textMuted = Color(0xFF64748B)
    val accentGreen = Color(0xFF059669)
    val accentAmber = Color(0xFFB45309)
    val rowBg = Color(0xFFF8FAFC)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = bgColor
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {

            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerColor, RoundedCornerShape(14.dp))
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
                        onClick = { showHistory = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF115E59)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("📋 History", color = Color.White, fontSize = 12.sp)
                    }
                    Button(
                        onClick = { showSettings = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF115E59)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("⚙ Settings", color = Color.White, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Main Content Area
            Row(modifier = Modifier.fillMaxSize()) {

                // Spotlight Left Card
                Card(
                    modifier = Modifier.weight(0.42f).fillMaxHeight(),
                    colors = CardDefaults.cardColors(containerColor = cardColor),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (latestPunch != null) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Text("TOKEN #${latestPunch.serialNo}", color = accentGreen, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)

                            val photoModel = if (!latestPunch.photoPath.isNullOrEmpty()) File(latestPunch.photoPath) else "https://via.placeholder.com/200x240/e2e8f0/64748b?text=No+Photo"
                            AsyncImage(
                                model = photoModel,
                                contentDescription = null,
                                modifier = Modifier.size(170.dp, 210.dp).clip(RoundedCornerShape(14.dp)),
                                contentScale = ContentScale.Crop
                            )

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(latestPunch.name, color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text(latestPunch.department, color = textMuted, fontSize = 14.sp)
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                InfoItem("EMP ID", latestPunch.empId, textPrimary, textMuted)
                                InfoItem("MEAL", latestPunch.mealType, accentAmber, textMuted)
                                InfoItem("TIME", latestPunch.punchTime, textPrimary, textMuted)
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Waiting for punch signal...", color = textMuted, fontSize = 16.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Last 5 tokens — large, made explicitly for at-a-glance reading
                // from a serving counter a few feet away. Full history is behind
                // the 📋 History button above, not crammed into this list too.
                Card(
                    modifier = Modifier.weight(0.58f).fillMaxHeight(),
                    colors = CardDefaults.cardColors(containerColor = cardColor),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        Text("LAST 5 SERVED", color = textMuted, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp, start = 4.dp))
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(punchHistory.take(5)) { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp)
                                        .background(rowBg, RoundedCornerShape(10.dp))
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("#${item.serialNo}", color = accentGreen, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                                    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                        Text(item.name, color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                        Text(item.empId, color = textMuted, fontSize = 15.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(item.mealType, color = accentAmber, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                        Text(item.punchTime, color = textMuted, fontSize = 15.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            currentSerialIp = serialIp,
            currentSerialPort = serialPort,
            currentServerUrl = serverUrl,
            currentDeviceSn = deviceSn,
            onDismiss = { showSettings = false },
            onSave = { ip, port, url, sn ->
                onSaveSettings(ip, port, url, sn)
                showSettings = false
            }
        )
    }

    if (showHistory) {
        HistoryDialog(punchHistory = punchHistory, onDismiss = { showHistory = false })
    }
}

/**
 * FEATURE (Aug-2026): "review previous tokens" — the full punch history, not just
 * the last 5 shown on the main screen. Reuses the same punchHistory list already
 * maintained in MainActivity (every punch is appended to it as it happens), so
 * this needed no new data plumbing, just a screen to browse it.
 */
@Composable
fun HistoryDialog(punchHistory: List<PunchEvent>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Token History (${punchHistory.size} total)", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                items(punchHistory) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(Color(0xFFF8FAFC), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("#${item.serialNo}", color = Color(0xFF059669), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(item.empId, color = Color(0xFF64748B), fontSize = 13.sp)
                        Text(item.name, color = Color(0xFF0F172A), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
                        Text(item.mealType, color = Color(0xFFB45309), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(item.punchTime, color = Color(0xFF64748B), fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun InfoItem(label: String, value: String, valueColor: Color = Color(0xFF0F172A), labelColor: Color = Color(0xFF64748B)) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = labelColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
fun SettingsDialog(
    currentSerialIp: String,
    currentSerialPort: String,
    currentServerUrl: String,
    currentDeviceSn: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit
) {
    var ip by remember { mutableStateOf(currentSerialIp) }
    var port by remember { mutableStateOf(currentSerialPort) }
    var url by remember { mutableStateOf(currentServerUrl) }
    var sn by remember { mutableStateOf(currentDeviceSn) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hardware & Server Settings", fontWeight = FontWeight.Bold) },
        text = {
            // BUGFIX (Aug-2026): Compose's AlertDialog does NOT auto-scroll its
            // content -- with 4 stacked text fields, if they don't all fit the
            // dialog's available height (very plausible on a landscape HDMI/TV
            // display, where vertical space is tight), the ones further down
            // silently get clipped below the visible area with no scrollbar or any
            // other indication they exist. That's exactly why Server URL and
            // Device SN (the last two fields) looked broken while IP and Port (the
            // first two) worked fine -- nothing was wrong with those two fields
            // specifically, they just weren't reachable. verticalScroll guarantees
            // every field can always be reached regardless of dialog/screen size.
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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
            }
        },
        confirmButton = {
            Button(onClick = { onSave(ip, port, url, sn) }) { Text("Save & Reconnect") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}