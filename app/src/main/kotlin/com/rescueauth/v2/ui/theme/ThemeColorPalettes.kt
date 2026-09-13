package com.rescueauth.v2.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The app uses one visual family: warm neutral surfaces with ink and
 * lavender accents. The six persisted theme IDs are kept for compatibility, but each
 * preset is now a coordinated accent variation instead of an unrelated hue.
 */
@Immutable
internal class ThemeColorPreset(
    val light: ColorScheme,
    val dark: ColorScheme,
)

/** Shared neutral surfaces. Accent color is intentionally isolated to roles. */
private object Neutrals {
    val backgroundLight = Color(0xFFF6F5F1)
    val onBackgroundLight = Color(0xFF242A38)
    val surfaceLight = Color(0xFFFFFFFF)
    val onSurfaceLight = Color(0xFF242A38)
    val surfaceVariantLight = Color(0xFFEEECE7)
    val onSurfaceVariantLight = Color(0xFF676B73)
    val outlineLight = Color(0xFF85878D)

    val backgroundDark = Color(0xFF11151C)
    val onBackgroundDark = Color(0xFFF2F2F3)
    val surfaceDark = Color(0xFF1F2530)
    val onSurfaceDark = Color(0xFFF2F2F3)
    val surfaceVariantDark = Color(0xFF363638)
    val onSurfaceVariantDark = Color(0xFFB9BBC1)
    val outlineDark = Color(0xFF898B92)

    /**
     * Explicit tonal surface-container ramp (light + dark) so cards and sheets
     * keep the Studio panels coordinated in both themes instead of falling
     * back to automatically derived container colors. The ramp is shared by
     * every accent preset; only the accent (primary) differs per preset.
     */
    val surfaceContainerLowestLight = Color(0xFFFFFFFF)
    val surfaceContainerLowLight = Color(0xFFFCFBF8)
    val surfaceContainerLight = Color(0xFFFFFFFF)
    val surfaceContainerHighLight = Color(0xFFF6F5F1)
    val surfaceContainerHighestLight = Color(0xFFE8E8ED)
    val outlineVariantLight = Color(0xFFD7D7DC)

    val surfaceContainerLowestDark = Color(0xFF171719)
    val surfaceContainerLowDark = Color(0xFF242426)
    val surfaceContainerDark = Color(0xFF2C2C2E)
    val surfaceContainerHighDark = Color(0xFF333336)
    val surfaceContainerHighestDark = Color(0xFF3D3D40)
    val outlineVariantDark = Color(0xFF48484C)
}

private object SemanticColors {
    val errorLight = Color(0xFFBA1A1A)
    val onErrorLight = Color(0xFFFFFFFF)
    val errorContainerLight = Color(0xFFFFDAD6)
    val onErrorContainerLight = Color(0xFF410002)

    val errorDark = Color(0xFFFFB4AB)
    val onErrorDark = Color(0xFF690005)
    val errorContainerDark = Color(0xFF93000A)
    val onErrorContainerDark = Color(0xFFFFDAD6)
}

/** Builds one coherent accent light/dark pair. */
private fun accentPreset(
    lightPrimary: Color,
    lightPrimaryContainer: Color,
    lightOnPrimaryContainer: Color,
    darkPrimary: Color,
    darkPrimaryContainer: Color,
    darkOnPrimaryContainer: Color,
    lightSecondary: Color,
    lightSecondaryContainer: Color,
    lightOnSecondaryContainer: Color,
    darkSecondary: Color,
    darkSecondaryContainer: Color,
    darkOnSecondaryContainer: Color,
): ThemeColorPreset {
    val lightTertiary = Color(0xFF625B74)
    val lightTertiaryContainer = Color(0xFFE5E3F3)
    val lightOnTertiaryContainer = Color(0xFF34324D)
    val darkTertiary = Color(0xFFC7BDF6)
    val darkTertiaryContainer = Color(0xFF38354F)
    val darkOnTertiaryContainer = Color(0xFFE5E3F3)

    return ThemeColorPreset(
        light = lightColorScheme(
            primary = lightPrimary,
            onPrimary = Color.White,
            primaryContainer = lightPrimaryContainer,
            onPrimaryContainer = lightOnPrimaryContainer,
            secondary = lightSecondary,
            onSecondary = Color.White,
            secondaryContainer = lightSecondaryContainer,
            onSecondaryContainer = lightOnSecondaryContainer,
            tertiary = lightTertiary,
            onTertiary = Color.White,
            tertiaryContainer = lightTertiaryContainer,
            onTertiaryContainer = lightOnTertiaryContainer,
            background = Neutrals.backgroundLight,
            onBackground = Neutrals.onBackgroundLight,
            surface = Neutrals.surfaceLight,
            onSurface = Neutrals.onSurfaceLight,
            surfaceVariant = Neutrals.surfaceVariantLight,
            onSurfaceVariant = Neutrals.onSurfaceVariantLight,
            surfaceContainerLowest = Neutrals.surfaceContainerLowestLight,
            surfaceContainerLow = Neutrals.surfaceContainerLowLight,
            surfaceContainer = Neutrals.surfaceContainerLight,
            surfaceContainerHigh = Neutrals.surfaceContainerHighLight,
            surfaceContainerHighest = Neutrals.surfaceContainerHighestLight,
            outline = Neutrals.outlineLight,
            outlineVariant = Neutrals.outlineVariantLight,
            error = SemanticColors.errorLight,
            onError = SemanticColors.onErrorLight,
            errorContainer = SemanticColors.errorContainerLight,
            onErrorContainer = SemanticColors.onErrorContainerLight,
        ),
        dark = darkColorScheme(
            primary = darkPrimary,
            onPrimary = Color(0xFF092E54),
            primaryContainer = darkPrimaryContainer,
            onPrimaryContainer = darkOnPrimaryContainer,
            secondary = darkSecondary,
            onSecondary = Color(0xFF272B35),
            secondaryContainer = darkSecondaryContainer,
            onSecondaryContainer = darkOnSecondaryContainer,
            tertiary = darkTertiary,
            onTertiary = Color(0xFF1C3446),
            tertiaryContainer = darkTertiaryContainer,
            onTertiaryContainer = darkOnTertiaryContainer,
            background = Neutrals.backgroundDark,
            onBackground = Neutrals.onBackgroundDark,
            surface = Neutrals.surfaceDark,
            onSurface = Neutrals.onSurfaceDark,
            surfaceVariant = Neutrals.surfaceVariantDark,
            onSurfaceVariant = Neutrals.onSurfaceVariantDark,
            surfaceContainerLowest = Neutrals.surfaceContainerLowestDark,
            surfaceContainerLow = Neutrals.surfaceContainerLowDark,
            surfaceContainer = Neutrals.surfaceContainerDark,
            surfaceContainerHigh = Neutrals.surfaceContainerHighDark,
            surfaceContainerHighest = Neutrals.surfaceContainerHighestDark,
            outline = Neutrals.outlineDark,
            outlineVariant = Neutrals.outlineVariantDark,
            error = SemanticColors.errorDark,
            onError = SemanticColors.onErrorDark,
            errorContainer = SemanticColors.errorContainerDark,
            onErrorContainer = SemanticColors.onErrorContainerDark,
        ),
    )
}

internal object ThemeColorPalettes {
    // Historical names map to accent tonal variants so stored preferences
    // remain valid while the brand family stays consistent.
    val SHIYI_ORANGE = accentPreset(
        lightPrimary = Color(0xFF242A38),
        lightPrimaryContainer = Color(0xFFE5E3F3),
        lightOnPrimaryContainer = Color(0xFF34324D),
        darkPrimary = Color(0xFFC7BDF6),
        darkPrimaryContainer = Color(0xFF38354F),
        darkOnPrimaryContainer = Color(0xFFE5E3F3),
        lightSecondary = Color(0xFF625B74),
        lightSecondaryContainer = Color(0xFFE5E3F3),
        lightOnSecondaryContainer = Color(0xFF303842),
        darkSecondary = Color(0xFFC3BED3),
        darkSecondaryContainer = Color(0xFF38434E),
        darkOnSecondaryContainer = Color(0xFFE5E3F3),
    )

    val CYAN_BLUE = accentPreset(
        lightPrimary = Color(0xFF076BBA),
        lightPrimaryContainer = Color(0xFFE9F3FB),
        lightOnPrimaryContainer = Color(0xFF34324D),
        darkPrimary = Color(0xFF82C3FC),
        darkPrimaryContainer = Color(0xFF173D5D),
        darkOnPrimaryContainer = Color(0xFFE9F3FB),
        lightSecondary = Color(0xFF5D607D),
        lightSecondaryContainer = Color(0xFFE5E5F8),
        lightOnSecondaryContainer = Color(0xFF191A31),
        darkSecondary = Color(0xFFC6C6E6),
        darkSecondaryContainer = Color(0xFF454661),
        darkOnSecondaryContainer = Color(0xFFE5E5F8),
    )

    val JADE_GREEN = accentPreset(
        lightPrimary = Color(0xFF23689B),
        lightPrimaryContainer = Color(0xFFEAF2F8),
        lightOnPrimaryContainer = Color(0xFF23445F),
        darkPrimary = Color(0xFF85C3EF),
        darkPrimaryContainer = Color(0xFF1D3C54),
        darkOnPrimaryContainer = Color(0xFFEAF2F8),
        lightSecondary = Color(0xFF60607E),
        lightSecondaryContainer = Color(0xFFE8E6FA),
        lightOnSecondaryContainer = Color(0xFF1C1B32),
        darkSecondary = Color(0xFFC9C7E9),
        darkSecondaryContainer = Color(0xFF484761),
        darkOnSecondaryContainer = Color(0xFFE8E6FA),
    )

    val INDIGO = accentPreset(
        lightPrimary = Color(0xFF3958AD),
        lightPrimaryContainer = Color(0xFFEEF1FC),
        lightOnPrimaryContainer = Color(0xFF23366A),
        darkPrimary = Color(0xFFA1B8FA),
        darkPrimaryContainer = Color(0xFF283862),
        darkOnPrimaryContainer = Color(0xFFEEF1FC),
        lightSecondary = Color(0xFF5B5D72),
        lightSecondaryContainer = Color(0xFFE0E1F9),
        lightOnSecondaryContainer = Color(0xFF171B2C),
        darkSecondary = Color(0xFFC4C5DD),
        darkSecondaryContainer = Color(0xFF434659),
        darkOnSecondaryContainer = Color(0xFFE0E1F9),
    )

    val VIOLET = accentPreset(
        lightPrimary = Color(0xFF6750A4),
        lightPrimaryContainer = Color(0xFFEADDFF),
        lightOnPrimaryContainer = Color(0xFF21005D),
        darkPrimary = Color(0xFFCFBCFF),
        darkPrimaryContainer = Color(0xFF4F378B),
        darkOnPrimaryContainer = Color(0xFFEADDFF),
        lightSecondary = Color(0xFF626570),
        lightSecondaryContainer = Color(0xFFF0EFF5),
        lightOnSecondaryContainer = Color(0xFF33313D),
        darkSecondary = Color(0xFFCCC2DC),
        darkSecondaryContainer = Color(0xFF4A4458),
        darkOnSecondaryContainer = Color(0xFFF0EFF5),
    )

    val ROSE = accentPreset(
        lightPrimary = Color(0xFF6550A8),
        lightPrimaryContainer = Color(0xFFE8E2FF),
        lightOnPrimaryContainer = Color(0xFF20144C),
        darkPrimary = Color(0xFFC7BDF6),
        darkPrimaryContainer = Color(0xFF49368E),
        darkOnPrimaryContainer = Color(0xFFE8E2FF),
        lightSecondary = Color(0xFF625A7C),
        lightSecondaryContainer = Color(0xFFE9E3F7),
        lightOnSecondaryContainer = Color(0xFF303842),
        darkSecondary = Color(0xFFCEC4E7),
        darkSecondaryContainer = Color(0xFF38434E),
        darkOnSecondaryContainer = Color(0xFFE9E3F7),
    )

    fun schemesFor(color: ThemeColor): ThemeColorPreset = when (color) {
        ThemeColor.SHIYI_ORANGE -> SHIYI_ORANGE
        ThemeColor.CYAN_BLUE -> CYAN_BLUE
        ThemeColor.JADE_GREEN -> JADE_GREEN
        ThemeColor.INDIGO -> INDIGO
        ThemeColor.VIOLET -> VIOLET
        ThemeColor.ROSE -> ROSE
    }
}
