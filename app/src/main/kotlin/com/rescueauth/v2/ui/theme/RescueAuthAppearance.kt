package com.rescueauth.v2.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import java.io.IOException
import kotlinx.coroutines.launch

/** One appearance binding for startup and the unlocked shell; no Activity recreation on selection. */
@Composable
fun RescueAuthAppearance(
    preferences: ThemePreferences,
    onSaveError: (() -> Unit)? = null,
    content: @Composable (ThemeMode, (ThemeMode) -> Unit, ThemeColor, (ThemeColor) -> Unit) -> Unit,
) {
    val color by preferences.themeColor.collectAsState(initial = ThemeColor.DEFAULT)
    val mode by preferences.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val dark = mode.isDark(isSystemInDarkTheme())
    val scope = rememberCoroutineScope()
    val onModeChange: (ThemeMode) -> Unit = remember(preferences, scope, onSaveError) {
        { selected ->
            scope.launch {
                try {
                    preferences.setThemeMode(selected)
                } catch (_: IOException) {
                    onSaveError?.invoke()
                }
            }
            Unit
        }
    }
    val onColorChange: (ThemeColor) -> Unit = remember(preferences, scope, onSaveError) {
        { selected ->
            scope.launch {
                try {
                    preferences.setThemeColor(selected)
                } catch (_: IOException) {
                    onSaveError?.invoke()
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
    RescueAuthTheme(themeColor = color, darkTheme = dark) {
        content(mode, onModeChange, color, onColorChange)
    }
}

/** Two-parameter compatibility overload for callers that only bind [ThemeMode]. */
@Composable
fun RescueAuthAppearance(
    preferences: ThemePreferences,
    content: @Composable (ThemeMode, (ThemeMode) -> Unit) -> Unit,
) {
    RescueAuthAppearance(preferences = preferences, onSaveError = null) { mode, onModeChange, _, _ ->
        content(mode, onModeChange)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
