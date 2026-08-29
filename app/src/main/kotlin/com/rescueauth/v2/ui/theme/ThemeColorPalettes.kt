package com.rescueauth.v2.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The app uses one visual family: cool neutral surfaces with blue-violet
 * accents. The six persisted theme IDs are kept for compatibility, but each
 * preset is now a blue-violet variation instead of an unrelated hue.
 */
@Immutable
internal class ThemeColorPreset(
    val light: ColorScheme,
    val dark: ColorScheme,
)

/** Shared neutral surfaces. Accent color is intentionally isolated to roles. */
private object Neutrals {
    val backgroundLight = Color(0xFFF6F7FB)
    val onBackgroundLight = Color(0xFF1A1B22)
    val surfaceLight = Color(0xFFFBFCFF)
    val onSurfaceLight = Color(0xFF1A1B22)
    val surfaceVariantLight = Color(0xFFE5E6F0)
    val onSurfaceVariantLight = Color(0xFF5B5C68)
    val outlineLight = Color(0xFF777985)

    val backgroundDark = Color(0xFF111218)
    val onBackgroundDark = Color(0xFFE5E1EE)
    val surfaceDark = Color(0xFF171820)
    val onSurfaceDark = Color(0xFFE5E1EE)
    val surfaceVariantDark = Color(0xFF454652)
    val onSurfaceVariantDark = Color(0xFFC7C5D1)
    val outlineDark = Color(0xFF90909D)
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

/** Builds one coherent blue-violet light/dark pair. */
private fun blueVioletPreset(
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
    val lightTertiary = Color(0xFF48657D)
    val lightTertiaryContainer = Color(0xFFD5E6F7)
    val lightOnTertiaryContainer = Color(0xFF081D2D)
    val darkTertiary = Color(0xFFB7D0E6)
    val darkTertiaryContainer = Color(0xFF304B60)
    val darkOnTertiaryContainer = Color(0xFFD5E6F7)

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
            outline = Neutrals.outlineLight,
            error = SemanticColors.errorLight,
            onError = SemanticColors.onErrorLight,
            errorContainer = SemanticColors.errorContainerLight,
            onErrorContainer = SemanticColors.onErrorContainerLight,
        ),
        dark = darkColorScheme(
            primary = darkPrimary,
            onPrimary = Color(0xFF2F2168),
            primaryContainer = darkPrimaryContainer,
            onPrimaryContainer = darkOnPrimaryContainer,
            secondary = darkSecondary,
            onSecondary = Color(0xFF302A42),
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
            outline = Neutrals.outlineDark,
            error = SemanticColors.errorDark,
            onError = SemanticColors.onErrorDark,
            errorContainer = SemanticColors.errorContainerDark,
            onErrorContainer = SemanticColors.onErrorContainerDark,
        ),
    )
}

internal object ThemeColorPalettes {
    // Historical names map to blue-violet tonal variants so stored preferences
    // remain valid while the brand family stays consistent.
    val SHIYI_ORANGE = blueVioletPreset(
        lightPrimary = Color(0xFF5B4DB1),
        lightPrimaryContainer = Color(0xFFE8E1FF),
        lightOnPrimaryContainer = Color(0xFF1D124E),
        darkPrimary = Color(0xFFC9BEFF),
        darkPrimaryContainer = Color(0xFF46368D),
        darkOnPrimaryContainer = Color(0xFFE8E1FF),
        lightSecondary = Color(0xFF625A7D),
        lightSecondaryContainer = Color(0xFFE9E2F6),
        lightOnSecondaryContainer = Color(0xFF201A31),
        darkSecondary = Color(0xFFCEC4E8),
        darkSecondaryContainer = Color(0xFF4B4160),
        darkOnSecondaryContainer = Color(0xFFE9E2F6),
    )

    val CYAN_BLUE = blueVioletPreset(
        lightPrimary = Color(0xFF4F56B5),
        lightPrimaryContainer = Color(0xFFE0E4FF),
        lightOnPrimaryContainer = Color(0xFF11164B),
        darkPrimary = Color(0xFFC1C6FF),
        darkPrimaryContainer = Color(0xFF383F92),
        darkOnPrimaryContainer = Color(0xFFE0E4FF),
        lightSecondary = Color(0xFF5D607D),
        lightSecondaryContainer = Color(0xFFE5E5F8),
        lightOnSecondaryContainer = Color(0xFF191A31),
        darkSecondary = Color(0xFFC6C6E6),
        darkSecondaryContainer = Color(0xFF454661),
        darkOnSecondaryContainer = Color(0xFFE5E5F8),
    )

    val JADE_GREEN = blueVioletPreset(
        lightPrimary = Color(0xFF5050A7),
        lightPrimaryContainer = Color(0xFFE4E2FF),
        lightOnPrimaryContainer = Color(0xFF171447),
        darkPrimary = Color(0xFFC5C1FF),
        darkPrimaryContainer = Color(0xFF39378B),
        darkOnPrimaryContainer = Color(0xFFE4E2FF),
        lightSecondary = Color(0xFF60607E),
        lightSecondaryContainer = Color(0xFFE8E6FA),
        lightOnSecondaryContainer = Color(0xFF1C1B32),
        darkSecondary = Color(0xFFC9C7E9),
        darkSecondaryContainer = Color(0xFF484761),
        darkOnSecondaryContainer = Color(0xFFE8E6FA),
    )

    val INDIGO = blueVioletPreset(
        lightPrimary = Color(0xFF455CA9),
        lightPrimaryContainer = Color(0xFFDCE1FF),
        lightOnPrimaryContainer = Color(0xFF00174A),
        darkPrimary = Color(0xFFB7C4FF),
        darkPrimaryContainer = Color(0xFF2C4390),
        darkOnPrimaryContainer = Color(0xFFDCE1FF),
        lightSecondary = Color(0xFF5B5D72),
        lightSecondaryContainer = Color(0xFFE0E1F9),
        lightOnSecondaryContainer = Color(0xFF171B2C),
        darkSecondary = Color(0xFFC4C5DD),
        darkSecondaryContainer = Color(0xFF434659),
        darkOnSecondaryContainer = Color(0xFFE0E1F9),
    )

    val VIOLET = blueVioletPreset(
        lightPrimary = Color(0xFF6750A4),
        lightPrimaryContainer = Color(0xFFEADDFF),
        lightOnPrimaryContainer = Color(0xFF21005D),
        darkPrimary = Color(0xFFCFBCFF),
        darkPrimaryContainer = Color(0xFF4F378B),
        darkOnPrimaryContainer = Color(0xFFEADDFF),
        lightSecondary = Color(0xFF625B71),
        lightSecondaryContainer = Color(0xFFE8DEF8),
        lightOnSecondaryContainer = Color(0xFF1E192B),
        darkSecondary = Color(0xFFCCC2DC),
        darkSecondaryContainer = Color(0xFF4A4458),
        darkOnSecondaryContainer = Color(0xFFE8DEF8),
    )

    val ROSE = blueVioletPreset(
        lightPrimary = Color(0xFF6550A8),
        lightPrimaryContainer = Color(0xFFE8E2FF),
        lightOnPrimaryContainer = Color(0xFF20144C),
        darkPrimary = Color(0xFFC9BEFF),
        darkPrimaryContainer = Color(0xFF49368E),
        darkOnPrimaryContainer = Color(0xFFE8E2FF),
        lightSecondary = Color(0xFF625A7C),
        lightSecondaryContainer = Color(0xFFE9E3F7),
        lightOnSecondaryContainer = Color(0xFF201A31),
        darkSecondary = Color(0xFFCEC4E7),
        darkSecondaryContainer = Color(0xFF4B4160),
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
