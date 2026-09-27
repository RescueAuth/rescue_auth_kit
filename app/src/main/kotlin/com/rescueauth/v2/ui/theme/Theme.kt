package com.rescueauth.v2.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * RescueAuth v2 Material 3 theme.
 *
 * This is the single application theme entry point used by the Compose host.
 * It wires [RescueAuthTypography] and the light/dark [MaterialTheme.colorScheme]
 * (chosen from the user-selected [ThemeColor] preset) plus the shared
 * [MaterialTheme.shapes].
 *
 * The theme is a pure function of two orthogonal dimensions:
 *  - [themeColor]: which preset accent palette to use (see [ThemeColorPalettes]).
 *  - [darkTheme]: light or dark mode (resolved from appearance preference and device configuration).
 *
 * Error / destructive semantic colors are identical across every preset, so a
 * user's theme-color choice never weakens security meaning.
 */
@Composable
fun RescueAuthTheme(
    themeColor: ThemeColor = ThemeColor.DEFAULT,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = ThemeColorPalettes.schemesFor(themeColor)
    val colorScheme = if (darkTheme) palette.dark else palette.light
    MaterialTheme(
        colorScheme = colorScheme,
        typography = RescueAuthTypography,
        shapes = Shapes(
            small = RoundedCornerShape(CornerRadius.xs),
            medium = RoundedCornerShape(CornerRadius.sm),
            large = RoundedCornerShape(CornerRadius.md),
            extraLarge = RoundedCornerShape(CornerRadius.md),
        ),
        content = content,
    )
}
