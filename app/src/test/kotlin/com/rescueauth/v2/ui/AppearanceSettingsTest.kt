package com.rescueauth.v2.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.theme.ThemePreferences
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Exercise the real preference → root → shell → settings path, including live recomposition. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppearanceSettingsTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val directory = Files.createTempDirectory("rescueauth-appearance-test").toFile()

    @After fun cleanup() {
        runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        directory.deleteRecursively()
    }

    @Test fun modePickerAppliesAndKeepsTheCurrentTab() {
        val prefs = ThemePreferences(PreferenceDataStoreFactory.create(scope = scope) {
            File(directory, "appearance.preferences_pb")
        })
        rule.setContent { RescueAuthRoot(themePreferences = prefs, versionName = "1.0.0") }
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).performClick()
        rule.onNodeWithTag("settings_appearance_row").performScrollTo().performClick()
        rule.onNodeWithTag("theme_mode_SYSTEM").assertIsSelected()
        rule.onNodeWithTag("theme_mode_LIGHT").assertExists()
        // Robolectric does not reliably place the separate bottom-sheet window for touch coordinates.
        // Real touch selection is also exercised by StudioVisualReviewTest on the emulator.
        rule.onNodeWithTag("theme_mode_DARK").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.waitUntil { rule.onAllNodes(hasTestTag("settings_appearance_row") and hasText("Dark")).fetchSemanticsNodes().size == 1 }
        rule.onNodeWithTag("theme_mode_DARK").assertDoesNotExist()
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).assertIsSelected()
        rule.onNodeWithTag("settings_appearance_row").performScrollTo().performClick()
        rule.onNodeWithTag("theme_mode_SYSTEM").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.waitUntil { rule.onAllNodes(hasTestTag("settings_appearance_row") and hasText("Follow system")).fetchSemanticsNodes().size == 1 }
        rule.onNodeWithTag(RescueAuthTestTags.NAV_AUTHENTICATOR).performClick()
        rule.onNodeWithTag(RescueAuthTestTags.NAV_AUTHENTICATOR).assertIsSelected()
    }
}
