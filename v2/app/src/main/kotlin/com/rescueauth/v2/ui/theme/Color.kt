package com.rescueauth.v2.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * RescueAuth v2 color helpers.
 *
 * The full Material 3 palettes live in [ThemeColorPalettes] (one complete
 * light/dark scheme per [ThemeColor] preset). This file only carries the small
 * set of non-palette colors shared by UI components — notably the preview
 * swatch used in the Appearance / Theme Color settings UI.
 */

/**
 * A representative brand color used to draw a small color-swatch preview for a
 * [ThemeColor] preset in the Settings UI. It matches each preset's light-mode
 * primary so the swatch reads as the accent the user is about to select.
 */
fun ThemeColor.swatchColor(): Color = when (this) {
    ThemeColor.SHIYI_ORANGE -> ThemeColorPalettes.SHIYI_ORANGE.light.primary
    ThemeColor.CYAN_BLUE -> ThemeColorPalettes.CYAN_BLUE.light.primary
    ThemeColor.JADE_GREEN -> ThemeColorPalettes.JADE_GREEN.light.primary
    ThemeColor.INDIGO -> ThemeColorPalettes.INDIGO.light.primary
    ThemeColor.VIOLET -> ThemeColorPalettes.VIOLET.light.primary
    ThemeColor.ROSE -> ThemeColorPalettes.ROSE.light.primary
}
