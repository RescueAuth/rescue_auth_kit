package com.rescueauth.v2.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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

/** Screen-level layout tokens. Keep page rhythm consistent across routes. */
object ScreenTokens {
    val horizontalPadding = 20.dp
    val verticalPadding = 16.dp
    val compactVerticalPadding = 10.dp
    val sectionGap = 24.dp
    val headerGap = 6.dp
    val controlMinHeight = 52.dp
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

/**
 * Global card UI tokens — the single source of truth for the RescueAuth card
 * language.
 *
 * **Card-first is a project-wide UI constraint** (see
 * `docs/UI_CARD_CONVENTION.md`): every list row, grouped content block, account
 * / TOTP / recovery entry and form section must be presented inside a card
 * container rather than as a bare flat row. These tokens keep that language
 * visually consistent across Authenticator / Developer / Settings and every
 * nested destination opened from them.
 *
 * Components MUST reference these constants (or a reusable card composable in
 * `ui/components/Card`), not hard-code their own colors / corners / padding,
 * so a future global restyle is a one-place change.
 */
object CardTokens {
    /** Surface container used as the standard card fill (M3 tonal surface). */
    @Composable
    fun containerColor(): Color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer

    /** Slightly elevated card fill used for tappable / emphasis cards. */
    @Composable
    fun elevatedContainerColor(): Color =
        androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow

    /** Subtle outline keeps cards readable on the cool neutral background. */
    @Composable
    fun outlineColor(): Color =
        androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)

    /** Standard card corner radius (rounded-rectangle card silhouette). */
    // A restrained radius keeps the vault feeling like a focused utility rather
    // than a stack of inflated, floating panels.
    val shape = RoundedCornerShape(CornerRadius.xs)

    /** Standard card inner content padding. */
    val contentPadding = Spacing.md

    /** Padding between sibling cards inside a list / column. */
    val listSpacing = Spacing.sm

    /** Padding applied around the whole carded list from its container edges. */
    val listOuterPadding = Spacing.md
}
