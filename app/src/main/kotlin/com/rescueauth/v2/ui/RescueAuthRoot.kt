package com.rescueauth.v2.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.rescueauth.v2.ui.theme.RescueAuthAppearance
import com.rescueauth.v2.ui.theme.ThemePreferences

/**
 * Wires the same appearance binding as the production Activity into the app shell.
 * A null preference source resolves to the application's non-sensitive local DataStore.
 */
@Composable
fun RescueAuthRoot(
    themePreferences: ThemePreferences?,
    versionName: String?,
) {
    val context = LocalContext.current
    val prefs = remember(themePreferences, context) {
        themePreferences ?: ThemePreferences(context)
    }
    RescueAuthAppearance(prefs) { themeMode, onThemeModeChange ->
        RescueAuthApp(
            versionName = versionName,
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
        )
    }
}
