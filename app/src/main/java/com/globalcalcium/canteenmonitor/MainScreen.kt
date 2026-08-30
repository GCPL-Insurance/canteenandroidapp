package com.globalcalcium.canteenmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

@Composable
fun CanteenDashboard(
    latestPunch: PunchEvent?,
    punchHistory: List<PunchEvent>,
    totalCount: Int
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0F172A)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E293B), RoundedCornerShape(12.dp))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("GLOBAL CALCIUM PVT LTD", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Token Count: $totalCount", color = Color(0xFF34D399), fontWeight = FontWeight.Black, fontSize = 22.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main Grid Split
            Row(modifier = Modifier.fillMaxSize()) {
                
                // Left Spotlight Box
                Card(
                    modifier = Modifier.weight(0.45f).fillMaxHeight(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (latestPunch != null) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Text("TOKEN #${latestPunch.serialNo}", color = Color(0xFF34D399), fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
                            
                            AsyncImage(
                                model = latestPunch.photoUrl,
                                contentDescription = null,
                                modifier = Modifier.size(180.dp, 220.dp).clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                            
                            Text(latestPunch.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                            Text(latestPunch.department, color = Color(0xFF94A3B8), fontSize = 16.sp)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                BadgeText("ID", latestPunch.empId)
                                BadgeText("Meal", latestPunch.mealType)
                                BadgeText("Time", latestPunch.punchTime)
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Waiting for punch...", color = Color.Gray, fontSize = 18.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Right Recent Log List
                Card(
                    modifier = Modifier.weight(0.55f).fillMaxHeight(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        items(punchHistory) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("#${item.serialNo}", color = Color(0xFF34D399), fontWeight = FontWeight.Bold)
                                Text(item.empId, color = Color(0xFF94A3B8))
                                Text(item.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text(item.mealType, color = Color(0xFFFBBF24))
                                Text(item.punchTime, color = Color(0xFF94A3B8), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BadgeText(title: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color(0xFF64748B), fontSize = 12.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}