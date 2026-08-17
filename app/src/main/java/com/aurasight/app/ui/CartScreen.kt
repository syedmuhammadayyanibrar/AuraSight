package com.aurasight.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurasight.app.GemmaViewModel
import com.aurasight.app.ai.CartEntry

@Composable
fun CartScreen(viewModel: GemmaViewModel) {
    val entries by viewModel.cartEntries.collectAsState(initial = emptyList())
    val total by viewModel.cartTotal.collectAsState(initial = 0.0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp)
    ) {
        Text(
            "کھاتہ (Khata)",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF111827),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "کھاتہ خالی ہے\n(Cart is empty)",
                    fontSize = 16.sp,
                    color = Color(0xFF6B7280),
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFF3F4F6))
                    .padding(16.dp)
            ) {
                items(entries) { entry ->
                    CartEntryRow(entry)
                    Divider(color = Color(0xFFE5E7EB), modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Total
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF2563EB))
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Total (کل):", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Rs. ${"%.2f".format(total)}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
fun CartEntryRow(entry: CartEntry) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(entry.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111827))
            Text("${entry.quantity} x Rs.${"%.2f".format(entry.price)}", fontSize = 14.sp, color = Color(0xFF6B7280))
        }
        Text("Rs. ${"%.2f".format(entry.lineTotal)}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111827))
    }
}
