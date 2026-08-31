package com.rescueauth.v2.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.ThemeColor
import com.rescueauth.v2.ui.theme.ThemePreferences
import kotlinx.coroutines.launch

/**
 * Root composable that wires the local theme-color preference into the theme
 * and the app shell.
 *
 * The preference is collected as Compose state as early as possible in the
 * composition, so the correct [ThemeColor] is applied on the very first frame —
 * avoiding a flash of the default blue-violet before switching to a saved color.
 * It is read via DataStore (async, off the main thread) and does not depend on
 * the Vault being unlocked, so the locked screen and unlock UI always render
 * with the correct theme.
 *
 * @param themePreferences nullable to keep tests simple (a null here uses the
 *   default color and a no-op setter, exactly like the pre-feature behaviour).
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
    val themeColor by prefs.themeColor.collectAsState(initial = ThemeColor.DEFAULT)

    RescueAuthTheme(themeColor = themeColor) {
        RescueAuthApp(
            versionName = versionName,
        )
    }
}
