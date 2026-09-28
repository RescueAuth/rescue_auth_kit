package com.rescueauth.v2.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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

    /** Joined account rows stay lazy while sharing one continuous card outline. */
    val groupTopShape = RoundedCornerShape(topStart = CornerRadius.md, topEnd = CornerRadius.md)
    val groupMiddleShape = RoundedCornerShape(0.dp)
    val groupBottomShape = RoundedCornerShape(bottomStart = CornerRadius.md, bottomEnd = CornerRadius.md)
    val accountRowPadding = 20.dp
    val accountRowMinHeight = 80.dp
    val accountAvatarSize = 36.dp
    val credentialPadding = 20.dp
    val credentialCountdownSize = 36.dp
    val credentialCountdownStroke = 2.dp

    /** Standard card inner content padding — roomier for a calm, breathable feel. */
    val contentPadding = 16.dp
    /** Icon and explanatory text form one horizontal card header. */
    val headerBadgeSize = 52.dp
    val headerIconSize = 26.dp
    val headerGap = Spacing.md

    val borderWidth = 0.5.dp

    /** Studio panels use a flat fill; dividers retain the fine outline token. */
    val border: androidx.compose.foundation.BorderStroke? = null
    val heroShape = RoundedCornerShape(32.dp)
    val dockShape = RoundedCornerShape(40.dp)
    val heroPadding = 24.dp
    val noPadding = 0.dp
    val actionSpacing = 12.dp
    /** Joined page actions: secondary at start, primary at end, without elevation. */
    val actionBarShape = RoundedCornerShape(21.dp)
    val actionButtonShape = RoundedCornerShape(15.dp)
    val actionBarPadding = 5.dp
    val actionBarGap = 6.dp
    val actionBarBorderWidth = 1.dp
    val actionButtonHeight = 52.dp
    val actionIconSize = 20.dp
    val actionBarBalancedWidth = 320.dp
    val brandArtworkSize = 180.dp
    val welcomeLogoSize = 160.dp
    val welcomeWordmarkHeight = 40.dp
    val bannerWordmarkHeight = 28.dp
    val inputFocusBorderWidth = 2.dp
    val inputRestBorderWidth = 1.dp
    val inputMinHeight = 56.dp
    val formFieldSpacing = Spacing.md

    @Composable
    fun inputOutlineColor(): Color =
        androidx.compose.material3.MaterialTheme.colorScheme.outline.copy(alpha = 0.8f)

    /** Shared search-style surface; form labels float into the outlined border. */
    @Composable
    fun inputContainerColor(): Color = containerColor()

    @Composable
    fun actionPrimaryContainerColor(): Color =
        if (containerColor().luminance() < 0.5f) Color(0xFF364564) else Color(0xFFE0E8FA)

    @Composable
    fun actionPrimaryContentColor(): Color =
        if (containerColor().luminance() < 0.5f) Color(0xFFD9E5FF) else Color(0xFF354C7F)

    @Composable
    fun protectedHeaderColor(): Color =
        if (containerColor().luminance() < 0.5f) Color(0xFF303540) else Color(0xFFF5F6F9)
    /** Shared minimum geometry; large text may grow the brand card without clipping. */
    val bannerHeight = 88.dp
    val bannerPadding = 12.dp
    val bannerHorizontalPadding = 24.dp
    val bannerMotifSize = 200.dp
    val bannerMotifEndOffset = 44.dp
    val bannerMotifTopOffset = (-28).dp
    val bannerLargeTextGrowth = 64.dp
    const val bannerEnterDurationMillis = 180

    @Composable
    fun bannerMotifAlpha(): Float =
        if (androidx.compose.material3.MaterialTheme.colorScheme.surface.luminance() < 0.5f) 0.18f else 0.065f

    @Composable
    fun bannerWashColor(): Color = Color(0xFF2A67A6).copy(alpha =
        if (androidx.compose.material3.MaterialTheme.colorScheme.surface.luminance() < 0.5f) 0.10f else 0.045f)

    /** Padding between sibling cards inside a list / column. */
    val listSpacing = Spacing.xs

    /** Padding applied around the whole carded list from its container edges. */
    val listOuterPadding = Spacing.md
}

/** Geometry and one-shot motion of the shell's global add action. */
object AddActionTokens {
    val size = 56.dp
    val iconSize = 28.dp
    val elevation = 4.dp
    // The overlay sits 16 dp above the dock; another 16 dp clears the last row/snackbar.
    val contentClearance = size + Spacing.md * 2
    const val exitMillis = 180
    const val scaleDamping = 0.58f
    const val rotationDamping = 0.42f
    const val stiffness = 450f
    const val hiddenRotation = -12f
    const val sheetMinFraction = 0.40f
    const val sheetMaxFraction = 0.78f
}

/** Floating navigation material; all fills resolve from the active light/dark scheme. */
object DockTokens {
    val selectionShape = RoundedCornerShape(50)
    val shadowElevation = 4.dp
    val borderWidth = 0.5.dp
    val focusBorderWidth = 1.dp
    val contentPadding = Spacing.xxs
    val itemSpacing = Spacing.xxs
    val itemVerticalPadding = Spacing.xxs
    val labelSpacing = 2.dp
    val iconSize = 20.dp
    const val slideDurationMillis = 220
    const val selectedIconScale = 1.08f

    @Composable
    fun containerColor(): Color = androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)

    @Composable
    fun selectionColor(): Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)

    @Composable
    fun borderColor(): Color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
}
