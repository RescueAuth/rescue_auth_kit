package com.rescueauth.v2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing

/**
 * TOTP countdown visual contract.
 *
 * Renders a horizontal linear progress bar (fraction of the TOTP period
 * remaining) above the remaining seconds, replacing the earlier circular ring
 * (see design decision 2026-08: a linear bar reads as a calmer, more modern
 * "time left" cue and scales better inside dense account rows).
 *
 * - [progressFraction] is the fraction of the period still remaining (1.0 →
 *   fresh code, 0.0 → expiring).
 * - [remainingSeconds] is the number of seconds until the code rotates.
 *
 * Color semantics mirror a traffic-light ramp:
 *  - ≤ 18% remaining → error (about to expire)
 *  - ≤ 40% remaining → tertiary (getting close)
 *  - otherwise       → primary
 */
@Composable
fun CountdownIndicator(
    progressFraction: Float,
    remainingSeconds: Int,
    modifier: Modifier = Modifier,
) {
    val clamped = progressFraction.coerceIn(0f, 1f)
    val barColor = when {
        clamped <= 0.18f -> MaterialTheme.colorScheme.error
        clamped <= 0.4f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Text(
            text = remainingSeconds.toString(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = labelColor,
        )
        LinearProgressIndicator(
            progress = { clamped },
            modifier = Modifier.fillMaxWidth(),
            color = barColor,
            trackColor = trackColor,
            strokeCap = StrokeCap.Round,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CountdownIndicatorFullPreview() {
    RescueAuthTheme {
        CountdownIndicator(
            progressFraction = 0.92f,
            remainingSeconds = 28,
            modifier = Modifier.width(72.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun CountdownIndicatorLowPreview() {
    RescueAuthTheme {
        CountdownIndicator(
            progressFraction = 0.1f,
            remainingSeconds = 3,
            modifier = Modifier.width(72.dp),
        )
    }
}
