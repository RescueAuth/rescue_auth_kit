package com.rescueauth.v2.ui.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * DataStore-backed theme-color preference tests (Robolectric).
 *
 * Verifies the persistence contract required by the theme-color feature:
 * default value, write → read round-trip (survives a re-created
 * [ThemePreferences] instance), and safe fallback to default for
 * unknown/corrupted stored values.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ThemePreferencesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        // Isolate the DataStore file per test to avoid cross-test state.
        context = object : android.content.ContextWrapper(app) {
            override fun getFilesDir(): File {
                val dir = File(super.getFilesDir(), "theme_prefs_test_${System.nanoTime()}")
                dir.mkdirs()
                return dir
            }
        }
    }

    @After
    fun tearDown() {
        context.filesDir.deleteRecursively()
    }

    @Test
    fun defaultIsShiyiOrange() = runTest {
        val prefs = ThemePreferences(context)
        assertEquals(ThemeColor.DEFAULT, prefs.themeColor.first())
    }

    @Test
    fun selectedValuePersistsAcrossRecreation() = runTest {
        val first = ThemePreferences(context)
        first.setThemeColor(ThemeColor.VIOLET)

        // A brand-new instance reading the same DataStore file must see VIOLET.
        val second = ThemePreferences(context)
        assertEquals(ThemeColor.VIOLET, second.themeColor.first())
    }

    @Test
    fun switchingBackToDefaultPersists() = runTest {
        val first = ThemePreferences(context)
        first.setThemeColor(ThemeColor.VIOLET)
        first.setThemeColor(ThemeColor.SHIYI_ORANGE)

        val second = ThemePreferences(context)
        assertEquals(ThemeColor.SHIYI_ORANGE, second.themeColor.first())
    }

    @Test
    fun everyPresetRoundTrips() = runTest {
        for (color in ThemeColor.entries) {
            val prefs = ThemePreferences(context)
            prefs.setThemeColor(color)
            val fresh = ThemePreferences(context)
            assertEquals(color, fresh.themeColor.first())
        }
    }

    @Test
    fun unknownStoredValueFallsBackToDefault() = runTest {
        val prefs = ThemePreferences(context)
        prefs.setThemeColor(ThemeColor.DEFAULT)

        // Corrupt the stored value directly and confirm the safe fallback.
        val dataStoreFile = File(context.filesDir, "datastore/appearance_prefs.preferences_pb")
        dataStoreFile.parentFile?.mkdirs()
        dataStoreFile.writeText("garbage-not-a-valid-preferences-pb")

        val reRead = ThemePreferences(context)
        // DataStore treats an unreadable file as corrupt and recovers to default.
        assertEquals(ThemeColor.DEFAULT, reRead.themeColor.first())
    }
}
