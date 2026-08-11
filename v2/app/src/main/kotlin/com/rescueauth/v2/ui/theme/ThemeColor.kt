package com.rescueauth.v2.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Fixed, stable set of user-selectable theme colors.
 *
 * This is the single source of truth for the theme-color feature. Each preset
 * maps a **stable persistence ID** (used to persist the user's choice) to a
 * display label (handled by string resources) and to a [ColorScheme] built
 * from the Material 3 palettes in [ThemeColorPalettes].
 *
 * The persistence ID is deliberately decoupled from both the display name and
 * any raw color integer, so future renames/recolors never invalidate an
 * existing user preference.
 */
@Immutable
enum class ThemeColor(val storageId: String) {
    /** 拾遗橙 / Shiyi Orange — the default brand accent. */
    SHIYI_ORANGE("shiyi_orange"),
    CYAN_BLUE("cyan_blue"),
    JADE_GREEN("jade_green"),
    INDIGO("indigo"),
    VIOLET("violet"),
    ROSE("rose"),
    ;

    /** The default theme color used when no explicit preference is stored. */
    companion object {
        val DEFAULT: ThemeColor = SHIYI_ORANGE

        /**
         * Resolves a stored [storageId] back to a [ThemeColor], falling back
         * to [DEFAULT] for unknown / corrupted values. This guarantees a safe
         * fallback even if a future build removes a preset or the stored
         * value is tampered with.
         */
        fun fromStorageId(id: String?): ThemeColor =
            entries.firstOrNull { it.storageId == id } ?: DEFAULT
    }
}

/**
 * The Material 3 color scheme for each [ThemeColor] preset, in both light and
 * dark variants.
 *
 * Each scheme is a complete, coordinated M3 palette (primary/onPrimary/
 * primaryContainer/onPrimaryContainer plus secondary/tertiary/surface/error),
 * so selecting a theme color changes the whole design system coherently rather
 * than recoloring a single button. Error / destructive semantic colors stay
 * identical across every preset (see [ThemeColorSchemes.ERROR_LIGHT] and
 * [ThemeColorSchemes.ERROR_DARK]) so user theme choice never weakens security
 * meaning.
 */
object ThemeColorSchemes {
    val ERROR_LIGHT = Color(0xFFBA1A1A)
    val ON_ERROR_LIGHT = Color(0xFFFFFFFF)
    val ERROR_CONTAINER_LIGHT = Color(0xFFFFDAD6)
    val ON_ERROR_CONTAINER_LIGHT = Color(0xFF410002)

    val ERROR_DARK = Color(0xFFFFB4AB)
    val ON_ERROR_DARK = Color(0xFF690005)
    val ERROR_CONTAINER_DARK = Color(0xFF93000A)
    val ON_ERROR_CONTAINER_DARK = Color(0xFFFFDAD6)
}
