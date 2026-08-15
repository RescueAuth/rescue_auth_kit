package com.rescueauth.v2.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Hand-tuned Material 3 color schemes for every [ThemeColor] preset, in light
 * and dark variants.
 *
 * Each scheme is a complete, coordinated M3 palette — primary/onPrimary/
 * primaryContainer/onPrimaryContainer plus secondary, tertiary, surface,
 * surfaceVariant, outline and the shared semantic error roles. Shared neutral
 * surfaces (background / surface / outline) are kept identical across presets
 * so switching accent color never destabilizes the overall surface contrast.
 *
 * **Security boundary:** the [error] / [errorContainer] roles are the same
 * Material semantic values for every preset (and every theme), so a user's
 * theme-color choice can never turn a destructive / security action green or
 * otherwise mask its danger.
 */
@Immutable
internal class ThemeColorPreset(
    val light: ColorScheme,
    val dark: ColorScheme,
)

/** Shared neutral surfaces (Material 3 "neutral" tonal roles). */
private object Neutrals {
    val backgroundLight = Color(0xFFFDFBFF)
    val onBackgroundLight = Color(0xFF1D1B20)
    val surfaceLight = Color(0xFFFDFBFF)
    val onSurfaceLight = Color(0xFF1D1B20)
    val surfaceVariantLight = Color(0xFFE7E0EC)
    val onSurfaceVariantLight = Color(0xFF49454F)
    val outlineLight = Color(0xFF79747E)

    val backgroundDark = Color(0xFF141218)
    val onBackgroundDark = Color(0xFFE6E0E9)
    val surfaceDark = Color(0xFF141218)
    val onSurfaceDark = Color(0xFFE6E0E9)
    val surfaceVariantDark = Color(0xFF49454F)
    val onSurfaceVariantDark = Color(0xFFCAC4D0)
    val outlineDark = Color(0xFF938F99)
}

internal object ThemeColorPalettes {

    /** 拾遗橙 / Shiyi Orange — warm, trustworthy brand accent. */
    val SHIYI_ORANGE = ThemeColorPreset(
        light = lightColorScheme(
            primary = Color(0xFF8B5000),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFFDCC2),
            onPrimaryContainer = Color(0xFF2C1600),
            secondary = Color(0xFF745846),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFFFDCC2),
            onSecondaryContainer = Color(0xFF2A1608),
            tertiary = Color(0xFF5D6134),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFE2E6AC),
            onTertiaryContainer = Color(0xFF1A1D00),
            background = Neutrals.backgroundLight,
            onBackground = Neutrals.onBackgroundLight,
            surface = Neutrals.surfaceLight,
            onSurface = Neutrals.onSurfaceLight,
            surfaceVariant = Neutrals.surfaceVariantLight,
            onSurfaceVariant = Neutrals.onSurfaceVariantLight,
            outline = Neutrals.outlineLight,
            error = ThemeColorSchemes.ERROR_LIGHT,
            onError = ThemeColorSchemes.ON_ERROR_LIGHT,
            errorContainer = ThemeColorSchemes.ERROR_CONTAINER_LIGHT,
            onErrorContainer = ThemeColorSchemes.ON_ERROR_CONTAINER_LIGHT,
        ),
        dark = darkColorScheme(
            primary = Color(0xFFFFB77B),
            onPrimary = Color(0xFF4A2800),
            primaryContainer = Color(0xFF6C3D00),
            onPrimaryContainer = Color(0xFFFFDCC2),
            secondary = Color(0xFFE2BFA9),
            onSecondary = Color(0xFF422B1D),
            secondaryContainer = Color(0xFF5A4132),
            onSecondaryContainer = Color(0xFFFFDCC2),
            tertiary = Color(0xFFC6CA92),
            onTertiary = Color(0xFF2F3211),
            tertiaryContainer = Color(0xFF464A25),
            onTertiaryContainer = Color(0xFFE2E6AC),
            background = Neutrals.backgroundDark,
            onBackground = Neutrals.onBackgroundDark,
            surface = Neutrals.surfaceDark,
            onSurface = Neutrals.onSurfaceDark,
            surfaceVariant = Neutrals.surfaceVariantDark,
            onSurfaceVariant = Neutrals.onSurfaceVariantDark,
            outline = Neutrals.outlineDark,
            error = ThemeColorSchemes.ERROR_DARK,
            onError = ThemeColorSchemes.ON_ERROR_DARK,
            errorContainer = ThemeColorSchemes.ERROR_CONTAINER_DARK,
            onErrorContainer = ThemeColorSchemes.ON_ERROR_CONTAINER_DARK,
        ),
    )

    /** 青蓝 / Cyan Blue — calm, tech-forward. */
    val CYAN_BLUE = ThemeColorPreset(
        light = lightColorScheme(
            primary = Color(0xFF00639A),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFCDE5FF),
            onPrimaryContainer = Color(0xFF001E32),
            secondary = Color(0xFF50606F),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFD3E5F6),
            onSecondaryContainer = Color(0xFF0C1D2A),
            tertiary = Color(0xFF665A79),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFECDDFF),
            onTertiaryContainer = Color(0xFF211832),
            background = Neutrals.backgroundLight,
            onBackground = Neutrals.onBackgroundLight,
            surface = Neutrals.surfaceLight,
            onSurface = Neutrals.onSurfaceLight,
            surfaceVariant = Neutrals.surfaceVariantLight,
            onSurfaceVariant = Neutrals.onSurfaceVariantLight,
            outline = Neutrals.outlineLight,
            error = ThemeColorSchemes.ERROR_LIGHT,
            onError = ThemeColorSchemes.ON_ERROR_LIGHT,
            errorContainer = ThemeColorSchemes.ERROR_CONTAINER_LIGHT,
            onErrorContainer = ThemeColorSchemes.ON_ERROR_CONTAINER_LIGHT,
        ),
        dark = darkColorScheme(
            primary = Color(0xFF83CFFF),
            onPrimary = Color(0xFF00344F),
            primaryContainer = Color(0xFF004B71),
            onPrimaryContainer = Color(0xFFCDE5FF),
            secondary = Color(0xFFB7C9DA),
            onSecondary = Color(0xFF213240),
            secondaryContainer = Color(0xFF374957),
            onSecondaryContainer = Color(0xFFD3E5F6),
            tertiary = Color(0xFFD0C0E5),
            onTertiary = Color(0xFF372D48),
            tertiaryContainer = Color(0xFF4E4360),
            onTertiaryContainer = Color(0xFFECDDFF),
            background = Neutrals.backgroundDark,
            onBackground = Neutrals.onBackgroundDark,
            surface = Neutrals.surfaceDark,
            onSurface = Neutrals.onSurfaceDark,
            surfaceVariant = Neutrals.surfaceVariantDark,
            onSurfaceVariant = Neutrals.onSurfaceVariantDark,
            outline = Neutrals.outlineDark,
            error = ThemeColorSchemes.ERROR_DARK,
            onError = ThemeColorSchemes.ON_ERROR_DARK,
            errorContainer = ThemeColorSchemes.ERROR_CONTAINER_DARK,
            onErrorContainer = ThemeColorSchemes.ON_ERROR_CONTAINER_DARK,
        ),
    )

    /** 翠绿 / Jade Green — fresh, trustworthy security green. */
    val JADE_GREEN = ThemeColorPreset(
        light = lightColorScheme(
            primary = Color(0xFF006C4C),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFF9CF2CC),
            onPrimaryContainer = Color(0xFF002113),
            secondary = Color(0xFF4D6357),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFCFE9D9),
            onSecondaryContainer = Color(0xFF0A1F16),
            tertiary = Color(0xFF3E6470),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFC1E9F7),
            onTertiaryContainer = Color(0xFF001F27),
            background = Neutrals.backgroundLight,
            onBackground = Neutrals.onBackgroundLight,
            surface = Neutrals.surfaceLight,
            onSurface = Neutrals.onSurfaceLight,
            surfaceVariant = Neutrals.surfaceVariantLight,
            onSurfaceVariant = Neutrals.onSurfaceVariantLight,
            outline = Neutrals.outlineLight,
            error = ThemeColorSchemes.ERROR_LIGHT,
            onError = ThemeColorSchemes.ON_ERROR_LIGHT,
            errorContainer = ThemeColorSchemes.ERROR_CONTAINER_LIGHT,
            onErrorContainer = ThemeColorSchemes.ON_ERROR_CONTAINER_LIGHT,
        ),
        dark = darkColorScheme(
            primary = Color(0xFF7DD9B0),
            onPrimary = Color(0xFF003828),
            primaryContainer = Color(0xFF00523B),
            onPrimaryContainer = Color(0xFF9CF2CC),
            secondary = Color(0xFFB3CCBE),
            onSecondary = Color(0xFF1F352A),
            secondaryContainer = Color(0xFF354B40),
            onSecondaryContainer = Color(0xFFCFE9D9),
            tertiary = Color(0xFFA5CCDA),
            onTertiary = Color(0xFF073641),
            tertiaryContainer = Color(0xFF264C57),
            onTertiaryContainer = Color(0xFFC1E9F7),
            background = Neutrals.backgroundDark,
            onBackground = Neutrals.onBackgroundDark,
            surface = Neutrals.surfaceDark,
            onSurface = Neutrals.onSurfaceDark,
            surfaceVariant = Neutrals.surfaceVariantDark,
            onSurfaceVariant = Neutrals.onSurfaceVariantDark,
            outline = Neutrals.outlineDark,
            error = ThemeColorSchemes.ERROR_DARK,
            onError = ThemeColorSchemes.ON_ERROR_DARK,
            errorContainer = ThemeColorSchemes.ERROR_CONTAINER_DARK,
            onErrorContainer = ThemeColorSchemes.ON_ERROR_CONTAINER_DARK,
        ),
    )

    /** 靛蓝 / Indigo — focused, professional. */
    val INDIGO = ThemeColorPreset(
        light = lightColorScheme(
            primary = Color(0xFF455CA9),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFDCE1FF),
            onPrimaryContainer = Color(0xFF00174A),
            secondary = Color(0xFF5B5D72),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFE0E1F9),
            onSecondaryContainer = Color(0xFF171B2C),
            tertiary = Color(0xFF77536D),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFFFD7F2),
            onTertiaryContainer = Color(0xFF2E1228),
            background = Neutrals.backgroundLight,
            onBackground = Neutrals.onBackgroundLight,
            surface = Neutrals.surfaceLight,
            onSurface = Neutrals.onSurfaceLight,
            surfaceVariant = Neutrals.surfaceVariantLight,
            onSurfaceVariant = Neutrals.onSurfaceVariantLight,
            outline = Neutrals.outlineLight,
            error = ThemeColorSchemes.ERROR_LIGHT,
            onError = ThemeColorSchemes.ON_ERROR_LIGHT,
            errorContainer = ThemeColorSchemes.ERROR_CONTAINER_LIGHT,
            onErrorContainer = ThemeColorSchemes.ON_ERROR_CONTAINER_LIGHT,
        ),
        dark = darkColorScheme(
            primary = Color(0xFFB7C4FF),
            onPrimary = Color(0xFF002A69),
            primaryContainer = Color(0xFF2C4390),
            onPrimaryContainer = Color(0xFFDCE1FF),
            secondary = Color(0xFFC4C5DD),
            onSecondary = Color(0xFF2D3042),
            secondaryContainer = Color(0xFF434659),
            onSecondaryContainer = Color(0xFFE0E1F9),
            tertiary = Color(0xFFE2BAD3),
            onTertiary = Color(0xFF46273D),
            tertiaryContainer = Color(0xFF5F3D55),
            onTertiaryContainer = Color(0xFFFFD7F2),
            background = Neutrals.backgroundDark,
            onBackground = Neutrals.onBackgroundDark,
            surface = Neutrals.surfaceDark,
            onSurface = Neutrals.onSurfaceDark,
            surfaceVariant = Neutrals.surfaceVariantDark,
            onSurfaceVariant = Neutrals.onSurfaceVariantDark,
            outline = Neutrals.outlineDark,
            error = ThemeColorSchemes.ERROR_DARK,
            onError = ThemeColorSchemes.ON_ERROR_DARK,
            errorContainer = ThemeColorSchemes.ERROR_CONTAINER_DARK,
            onErrorContainer = ThemeColorSchemes.ON_ERROR_CONTAINER_DARK,
        ),
    )

    /** 紫罗兰 / Violet — elegant Material 3 canonical seed. */
    val VIOLET = ThemeColorPreset(
        light = lightColorScheme(
            primary = Color(0xFF6750A4),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFEADDFF),
            onPrimaryContainer = Color(0xFF21005D),
            secondary = Color(0xFF625B71),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFE8DEF8),
            onSecondaryContainer = Color(0xFF1E192B),
            tertiary = Color(0xFF7D5260),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFFFD8E4),
            onTertiaryContainer = Color(0xFF31111D),
            background = Neutrals.backgroundLight,
            onBackground = Neutrals.onBackgroundLight,
            surface = Neutrals.surfaceLight,
            onSurface = Neutrals.onSurfaceLight,
            surfaceVariant = Neutrals.surfaceVariantLight,
            onSurfaceVariant = Neutrals.onSurfaceVariantLight,
            outline = Neutrals.outlineLight,
            error = ThemeColorSchemes.ERROR_LIGHT,
            onError = ThemeColorSchemes.ON_ERROR_LIGHT,
            errorContainer = ThemeColorSchemes.ERROR_CONTAINER_LIGHT,
            onErrorContainer = ThemeColorSchemes.ON_ERROR_CONTAINER_LIGHT,
        ),
        dark = darkColorScheme(
            primary = Color(0xFFCFBCFF),
            onPrimary = Color(0xFF381E72),
            primaryContainer = Color(0xFF4F378B),
            onPrimaryContainer = Color(0xFFEADDFF),
            secondary = Color(0xFFCCC2DC),
            onSecondary = Color(0xFF332D41),
            secondaryContainer = Color(0xFF4A4458),
            onSecondaryContainer = Color(0xFFE8DEF8),
            tertiary = Color(0xFFEFB8C8),
            onTertiary = Color(0xFF492532),
            tertiaryContainer = Color(0xFF633B48),
            onTertiaryContainer = Color(0xFFFFD8E4),
            background = Neutrals.backgroundDark,
            onBackground = Neutrals.onBackgroundDark,
            surface = Neutrals.surfaceDark,
            onSurface = Neutrals.onSurfaceDark,
            surfaceVariant = Neutrals.surfaceVariantDark,
            onSurfaceVariant = Neutrals.onSurfaceVariantDark,
            outline = Neutrals.outlineDark,
            error = ThemeColorSchemes.ERROR_DARK,
            onError = ThemeColorSchemes.ON_ERROR_DARK,
            errorContainer = ThemeColorSchemes.ERROR_CONTAINER_DARK,
            onErrorContainer = ThemeColorSchemes.ON_ERROR_CONTAINER_DARK,
        ),
    )

    /** 玫红 / Rose — warm, distinct, not pure red (kept distinct from error). */
    val ROSE = ThemeColorPreset(
        light = lightColorScheme(
            primary = Color(0xFFB0265A),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFFD9E1),
            onPrimaryContainer = Color(0xFF3F001B),
            secondary = Color(0xFF74565F),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFFFD9E1),
            onSecondaryContainer = Color(0xFF2B141C),
            tertiary = Color(0xFF7C5635),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFFFDCC2),
            onTertiaryContainer = Color(0xFF2A1600),
            background = Neutrals.backgroundLight,
            onBackground = Neutrals.onBackgroundLight,
            surface = Neutrals.surfaceLight,
            onSurface = Neutrals.onSurfaceLight,
            surfaceVariant = Neutrals.surfaceVariantLight,
            onSurfaceVariant = Neutrals.onSurfaceVariantLight,
            outline = Neutrals.outlineLight,
            error = ThemeColorSchemes.ERROR_LIGHT,
            onError = ThemeColorSchemes.ON_ERROR_LIGHT,
            errorContainer = ThemeColorSchemes.ERROR_CONTAINER_LIGHT,
            onErrorContainer = ThemeColorSchemes.ON_ERROR_CONTAINER_LIGHT,
        ),
        dark = darkColorScheme(
            primary = Color(0xFFFFB1C8),
            onPrimary = Color(0xFF5D1132),
            primaryContainer = Color(0xFF8C1D4B),
            onPrimaryContainer = Color(0xFFFFD9E1),
            secondary = Color(0xFFE4BDC6),
            onSecondary = Color(0xFF432931),
            secondaryContainer = Color(0xFF5C3F47),
            onSecondaryContainer = Color(0xFFFFD9E1),
            tertiary = Color(0xFFE5BF9A),
            onTertiary = Color(0xFF422D12),
            tertiaryContainer = Color(0xFF5B4326),
            onTertiaryContainer = Color(0xFFFFDCC2),
            background = Neutrals.backgroundDark,
            onBackground = Neutrals.onBackgroundDark,
            surface = Neutrals.surfaceDark,
            onSurface = Neutrals.onSurfaceDark,
            surfaceVariant = Neutrals.surfaceVariantDark,
            onSurfaceVariant = Neutrals.onSurfaceVariantDark,
            outline = Neutrals.outlineDark,
            error = ThemeColorSchemes.ERROR_DARK,
            onError = ThemeColorSchemes.ON_ERROR_DARK,
            errorContainer = ThemeColorSchemes.ERROR_CONTAINER_DARK,
            onErrorContainer = ThemeColorSchemes.ON_ERROR_CONTAINER_DARK,
        ),
    )

    /** Returns the light/dark schemes for a [ThemeColor]. */
    fun schemesFor(color: ThemeColor): ThemeColorPreset = when (color) {
        ThemeColor.SHIYI_ORANGE -> SHIYI_ORANGE
        ThemeColor.CYAN_BLUE -> CYAN_BLUE
        ThemeColor.JADE_GREEN -> JADE_GREEN
        ThemeColor.INDIGO -> INDIGO
        ThemeColor.VIOLET -> VIOLET
        ThemeColor.ROSE -> ROSE
    }
}
