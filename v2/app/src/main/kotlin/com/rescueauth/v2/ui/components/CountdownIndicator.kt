package com.rescueauth.v2.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.ui.theme.RescueAuthTheme

/**
 * TOTP countdown visual contract.
 *
 * Renders a circular progress ring (fraction of the TOTP period remaining)
 * plus the remaining seconds.
 *
 * - [progressFraction] is the fraction of the period still remaining (1.0 →
 *   fresh code, 0.0 → expiring).
 * - [remainingSeconds] is the number of seconds until the code rotates.
 * - In @Preview (detected via [LocalInspectionMode]) the ring is drawn as a
 *   static snapshot so screenshots are deterministic.
 *
 * This is purely a presentation contract — no TOTP generation or real clock
 * wiring is implemented in this foundation PR. Production slices drive
 * [progressFraction]/[remainingSeconds] from an actual countdown source.
 */
@Composable
fun CountdownIndicator(
    progressFraction: Float,
    remainingSeconds: Int,
    modifier: Modifier = Modifier,
    ringSize: Int = 48,
) {
    val ringDp = ringSize.dp
    val isPreview = LocalInspectionMode.current
    val clamped = progressFraction.coerceIn(0f, 1f)
    val ringColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurface
    val labelStyle = MaterialTheme.typography.labelMedium

    Box(modifier = modifier.size(ringDp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(ringDp)) {
            val strokeWidth = 4.dp.toPx()
            val arcSize = size.minDimension
            val topLeft = Offset(
                (size.width - arcSize) / 2f,
                (size.height - arcSize) / 2f,
            )
            val arc = Size(arcSize, arcSize)

            // Track
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arc,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            // Remaining-fraction ring (static in preview for screenshots).
            val progressSweep = clamped * 360f
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = progressSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arc,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
        Text(
            text = remainingSeconds.toString(),
            style = labelStyle,
            color = labelColor,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CountdownIndicatorFullPreview() {
    RescueAuthTheme {
        CountdownIndicator(progressFraction = 0.92f, remainingSeconds = 28)
    }
}

@Preview(showBackground = true)
@Composable
private fun CountdownIndicatorLowPreview() {
    RescueAuthTheme {
        CountdownIndicator(progressFraction = 0.1f, remainingSeconds = 3)
    }
}
