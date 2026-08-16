package com.aurasight.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen splash shown while the model file is being extracted on first run.
 * [statusText]  — current step description (e.g. "Preparing model…")
 * [progress]    — 0f..1f progress fraction, or null for indeterminate
 */
@Composable
fun SplashScreen(
    statusText: String,
    progress: Float? = null,
) {
    // Pulsing animation for the logo ring
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0D1117), Color(0xFF161B22))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {

            // ── Logo ring ──────────────────────────────────────────────────
            Box(contentAlignment = Alignment.Center) {
                // Outer glow ring
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .scale(scale)
                        .clip(CircleShape)
                        .background(
                            Color(0xFF58A6FF).copy(alpha = ringAlpha * 0.25f)
                        )
                )
                // Inner filled circle
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(Color(0xFF58A6FF), Color(0xFF1F6FEB))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "👁",
                        fontSize = 36.sp
                    )
                }
            }

            // ── App name ───────────────────────────────────────────────────
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "AuraSight",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE6EDF3),
                    letterSpacing = 1.sp
                )
                Text(
                    text = "آپ کا ذہین ساتھی",   // "Your intelligent companion"
                    fontSize = 14.sp,
                    color = Color(0xFF8B949E),
                    textAlign = TextAlign.Center
                )
            }

            // ── Progress bar ───────────────────────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 48.dp)
            ) {
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape),
                        color = Color(0xFF58A6FF),
                        trackColor = Color(0xFF30363D),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape),
                        color = Color(0xFF58A6FF),
                        trackColor = Color(0xFF30363D),
                    )
                }

                Text(
                    text = statusText,
                    fontSize = 13.sp,
                    color = Color(0xFF8B949E),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
