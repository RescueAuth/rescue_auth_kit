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
    val horizontalPadding = 24.dp
    val verticalPadding = 16.dp
    val compactVerticalPadding = 10.dp
    val sectionGap = 24.dp
    val headerGap = 6.dp
    val controlMinHeight = 48.dp
}

/** Corner radius scale (design tokens). */
object CornerRadius {
    /** Smallest radius — chips, badges, inline controls. */
    val xs = 14.dp
    /** Compact list rows / medium cards. */
    val sm = 18.dp
    /** Standard card container — restrained panel rounding. */
    val md = 24.dp
    /** Fully rounded pill (FAB, tags). */
    val pill = 50.dp
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
    fun containerColor(): Color = androidx.compose.material3.MaterialTheme.colorScheme.surface

    /** Slightly elevated card fill used for tappable / emphasis cards. */
    @Composable
    fun elevatedContainerColor(): Color =
        androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow

    /** Subtle outline keeps cards readable on the cool neutral background. */
    @Composable
    fun outlineColor(): Color =
        androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)

    /** Standard 24 dp content panel. */
    val shape = RoundedCornerShape(CornerRadius.md)

    /** Slightly tighter 18 dp radius for compact list rows so rows still
     *  read as part of the same card family without bulking up the list. */
    val rowShape = RoundedCornerShape(CornerRadius.sm)

    /** Standard card inner content padding — roomier for a calm, breathable feel. */
    val contentPadding = 16.dp

    val borderWidth = 0.5.dp

    /** Studio panels use a flat fill; dividers retain the fine outline token. */
    val border: androidx.compose.foundation.BorderStroke? = null
    val heroShape = RoundedCornerShape(32.dp)
    val dockShape = RoundedCornerShape(28.dp)
    val heroPadding = 24.dp
    val noPadding = 0.dp
    val actionSpacing = 12.dp
    /** All directory banners share one geometry, independent of copy length. */
    val bannerHeight = 144.dp
    val bannerArtworkSize = 84.dp
    val bannerPadding = 20.dp
    val bannerLargeTextGrowth = 120.dp

    /** Padding between sibling cards inside a list / column. */
    val listSpacing = Spacing.xs

    /** Padding applied around the whole carded list from its container edges. */
    val listOuterPadding = Spacing.md
}
