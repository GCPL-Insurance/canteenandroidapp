package com.globalcalcium.canteenmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF090D16)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF131B2E), RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.size(12.dp).background(Color(0xFF10B981), CircleShape))
                    Column {
                        Text("GLOBAL CALCIUM PVT LTD", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
                        Text("Canteen Live Verification Terminal", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                }
                
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("PUNCH TOTAL", color = Color(0xFF64748B), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("$totalCount", color = Color(0xFF34D399), fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    }
                    Button(
                        onClick = { showSettings = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
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
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (latestPunch != null) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Text("TOKEN #${latestPunch.serialNo}", color = Color(0xFF34D399), fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                            
                            val photoModel = if (!latestPunch.photoPath.isNullOrEmpty()) File(latestPunch.photoPath) else "https://via.placeholder.com/200x240/1e293b/94a3b8?text=No+Photo"
                            AsyncImage(
                                model = photoModel,
                                contentDescription = null,
                                modifier = Modifier.size(170.dp, 210.dp).clip(RoundedCornerShape(14.dp)),
                                contentScale = ContentScale.Crop
                            )
                            
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(latestPunch.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text(latestPunch.department, color = Color(0xFF94A3B8), fontSize = 14.sp)
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                InfoItem("EMP ID", latestPunch.empId)
                                InfoItem("MEAL", latestPunch.mealType, Color(0xFFFBBF24))
                                InfoItem("TIME", latestPunch.punchTime)
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Waiting for punch signal...", color = Color.DarkGray, fontSize = 16.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // History Table Right
                Card(
                    modifier = Modifier.weight(0.58f).fillMaxHeight(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF131B2E)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        items(punchHistory) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .background(Color(0xFF090D16), RoundedCornerShape(8.dp))
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("#${item.serialNo}", color = Color(0xFF34D399), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(item.empId, color = Color(0xFF94A3B8), fontSize = 13.sp)
                                Text(item.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(item.mealType, color = Color(0xFFFBBF24), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(item.punchTime, color = Color(0xFF64748B), fontSize = 11.sp)
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
}

@Composable
fun InfoItem(label: String, value: String, valueColor: Color = Color.White) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0xFF64748B), fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("USR Serial Converter IP") })
                OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("USR Port (e.g. 8234)") })
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("AWS ADMS Server URL") })
                OutlinedTextField(value = sn, onValueChange = { sn = it }, label = { Text("eSSL Emulated Serial No") })
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