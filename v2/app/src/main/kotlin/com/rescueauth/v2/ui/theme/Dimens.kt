package com.rescueauth.v2.ui.theme

import androidx.compose.ui.unit.dp

/**
 * RescueAuth v2 spacing scale (design tokens).
 *
 * Small consistent spacing scale used across all reusable components. Named
 * after the Material 3 density guidelines and kept as a single source of truth
 * so components do not hard-code magic numbers.
 */
object Spacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
}

/** Corner radius scale (design tokens). */
object CornerRadius {
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val pill = 24.dp
}

/** Elevation scale (design tokens). */
object ElevationTokens {
    val none = 0.dp
    val xs = 1.dp
    val sm = 2.dp
    val md = 4.dp
    val lg = 8.dp
}
