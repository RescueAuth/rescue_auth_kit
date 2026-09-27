package com.rescueauth.v2.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.rescueauth.v2.R
import java.io.IOException
import kotlinx.coroutines.launch

/** One appearance binding for startup and the unlocked shell; no Activity recreation on selection. */
@Composable
fun RescueAuthAppearance(
    preferences: ThemePreferences,
    content: @Composable (ThemeMode, (ThemeMode) -> Unit) -> Unit,
) {
    val color by preferences.themeColor.collectAsState(initial = ThemeColor.DEFAULT)
    val mode by preferences.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val dark = mode.isDark(isSystemInDarkTheme())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val onModeChange: (ThemeMode) -> Unit = remember(preferences, scope, context) {
        { selected ->
            scope.launch {
                try {
                    preferences.setThemeMode(selected)
                } catch (_: IOException) {
                    Toast.makeText(context, R.string.settings_appearance_save_error, Toast.LENGTH_SHORT).show()
                }
            }
            Unit
        }
    }
    val window = LocalView.current.context.findActivity()?.window
    SideEffect {
        window?.let {
            WindowCompat.getInsetsController(it, it.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    RescueAuthTheme(themeColor = color, darkTheme = dark) { content(mode, onModeChange) }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
