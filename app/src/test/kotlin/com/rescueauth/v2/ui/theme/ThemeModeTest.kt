package com.rescueauth.v2.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ThemeModeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun missingOrUnknownModeUsesSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageId(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorageId("future-mode"))
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromStorageId(it.storageId)) }
    }

    @Test fun manualModesOverrideBothSystemStates() {
        listOf(false, true).forEach { systemDark ->
            assertFalse(ThemeMode.LIGHT.isDark(systemDark))
            assertTrue(ThemeMode.DARK.isDark(systemDark))
            assertEquals(systemDark, ThemeMode.SYSTEM.isDark(systemDark))
        }
    }

    @Test fun systemConfigurationChangesUpdateThePaletteAndDockLive() {
        val night = mutableStateOf(false)
        val mode = mutableStateOf(ThemeMode.SYSTEM)
        var background = Color.Unspecified
        var dock = Color.Unspecified
        rule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night.value) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                RescueAuthTheme(darkTheme = mode.value.isDark(isSystemInDarkTheme())) {
                    background = MaterialTheme.colorScheme.background
                    dock = DockTokens.containerColor()
                }
            }
        }
        rule.runOnIdle { assertTrue(background.luminance() > 0.8f); assertTrue(dock.luminance() > 0.8f); night.value = true }
        rule.runOnIdle { assertTrue(background.luminance() < 0.1f); assertTrue(dock.luminance() < 0.1f); mode.value = ThemeMode.LIGHT }
        rule.runOnIdle { assertTrue(background.luminance() > 0.8f); mode.value = ThemeMode.DARK; night.value = false }
        rule.runOnIdle { assertTrue(background.luminance() < 0.1f); mode.value = ThemeMode.SYSTEM }
        rule.runOnIdle { assertTrue(background.luminance() > 0.8f); assertTrue(dock.luminance() > 0.8f) }
    }
}
