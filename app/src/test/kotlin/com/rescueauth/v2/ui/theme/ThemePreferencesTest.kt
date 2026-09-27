package com.rescueauth.v2.ui.theme

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A separate real DataStore file per test; reopening proves disk persistence, not an in-memory cache. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ThemePreferencesTest {
    private lateinit var directory: File
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>

    @Before fun setUp() {
        directory = Files.createTempDirectory("rescueauth-theme-test").toFile()
        openStore()
    }

    private fun openStore() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "appearance.preferences_pb") }
    }

    private suspend fun reopen(): ThemePreferences {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        openStore()
        return ThemePreferences(store)
    }

    @After fun tearDown() {
        runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
        directory.deleteRecursively()
    }

    @Test fun defaultIsShiyiOrange() = runTest {
        assertEquals(ThemeColor.DEFAULT, ThemePreferences(store).themeColor.first())
    }

    @Test fun selectedValuePersistsAcrossRecreation() = runTest {
        ThemePreferences(store).setThemeColor(ThemeColor.VIOLET)
        assertEquals(ThemeColor.VIOLET, reopen().themeColor.first())
    }

    @Test fun switchingBackToDefaultPersists() = runTest {
        val prefs = ThemePreferences(store)
        prefs.setThemeColor(ThemeColor.VIOLET)
        prefs.setThemeColor(ThemeColor.SHIYI_ORANGE)
        assertEquals(ThemeColor.SHIYI_ORANGE, reopen().themeColor.first())
    }

    @Test fun everyPresetRoundTrips() = runTest {
        for (color in ThemeColor.entries) {
            ThemePreferences(store).setThemeColor(color)
            assertEquals(color, reopen().themeColor.first())
        }
    }

    @Test fun unknownStoredValueFallsBackToDefault() = runTest {
        store.edit { it[stringPreferencesKey("theme_color")] = "unknown-color" }
        assertEquals(ThemeColor.DEFAULT, reopen().themeColor.first())
    }

    @Test fun newAndExistingColorPreferencesDefaultToSystemMode() = runTest {
        val prefs = ThemePreferences(store)
        assertEquals(ThemeMode.SYSTEM, prefs.themeMode.first())
        prefs.setThemeColor(ThemeColor.VIOLET)
        assertEquals(ThemeMode.SYSTEM, reopen().themeMode.first())
    }

    @Test fun modePersistsAcrossRecreationWithoutChangingTheAccent() = runTest {
        ThemePreferences(store).setThemeColor(ThemeColor.VIOLET)
        ThemeMode.entries.forEach { mode ->
            ThemePreferences(store).setThemeMode(mode)
            val recreated = reopen()
            assertEquals(mode, recreated.themeMode.first())
            assertEquals(ThemeColor.VIOLET, recreated.themeColor.first())
        }
    }

    @Test fun unknownModeFallsBackToSystemWithoutResettingTheAccent() = runTest {
        ThemePreferences(store).setThemeColor(ThemeColor.VIOLET)
        store.edit { it[stringPreferencesKey("theme_mode")] = "unknown-mode" }
        val prefs = reopen()
        assertEquals(ThemeMode.SYSTEM, prefs.themeMode.first())
        assertEquals(ThemeColor.VIOLET, prefs.themeColor.first())
    }
}
