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
import com.aurasight.app.ai.KhataSummary

@Composable
fun KhataScreen(viewModel: GemmaViewModel) {
    val summaries by viewModel.khataSummaries.collectAsState()

    // Positive balance means they owe money (Udhaar), negative means advanced payment (Jama)
    val totalOutstanding = summaries.filter { it.balance > 0 }.sumOf { it.balance }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .padding(24.dp)
    ) {
        Text(
            "کھاتہ (Ledger)",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFE6EDF3),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (summaries.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "کوئی کھاتہ نہیں\n(No ledger entries)",
                    fontSize = 16.sp,
                    color = Color(0xFF8B949E),
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF161B22))
                    .padding(16.dp)
            ) {
                items(summaries) { summary ->
                    KhataSummaryRow(summary)
                    Divider(color = Color(0xFF30363D), modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Total Outstanding
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF8B0000))
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Total Udhaar:", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Rs. ${"%.2f".format(totalOutstanding)}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
fun KhataSummaryRow(summary: KhataSummary) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(summary.customerName, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE6EDF3))
        
        val color = if (summary.balance > 0) Color(0xFFF85149) else Color(0xFF3FB950)
        val prefix = if (summary.balance > 0) "Udhaar:" else "Jama:"
        val displayAmount = Math.abs(summary.balance)
        
        Column(horizontalAlignment = Alignment.End) {
            Text(prefix, fontSize = 12.sp, color = Color(0xFF8B949E))
            Text("Rs. ${"%.2f".format(displayAmount)}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}
