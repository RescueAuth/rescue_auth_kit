package com.rescueauth.v2.ui.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Local, non-sensitive preference storage for the theme color and appearance mode.
 *
 * Uses [androidx.datastore.preferences] (DataStore Preferences) — a deliberately
 * small abstraction over [ThemeColor] and [ThemeMode]. It is **not** stored in
 * the Vault database, SQLCipher data, Android Keystore, or any secret store:
 * theme color is a pure UI preference that must be readable before the Vault is
 * unlocked (locked screen / unlock UI) and must survive process recreation.
 *
 * The stored value is the stable [ThemeColor.storageId]; unknown/corrupted
 * values safely fall back to [ThemeColor.DEFAULT].
 */
private val Context.themeDataStore by preferencesDataStore(name = "appearance_prefs")

class ThemePreferences internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.themeDataStore)

    private val key = stringPreferencesKey("theme_color")
    private val modeKey = stringPreferencesKey("theme_mode")

    /** Emits the currently selected [ThemeColor] (default on first run). */
    val themeColor: Flow<ThemeColor> = store.data.map { prefs ->
        ThemeColor.fromStorageId(prefs[key])
    }

    /** Persists the selected [ThemeColor]. Applies live via the returned Flow. */
    suspend fun setThemeColor(color: ThemeColor) {
        store.edit { prefs ->
            prefs[key] = color.storageId
        }
    }

    val themeMode: Flow<ThemeMode> = store.data.map { prefs -> ThemeMode.fromStorageId(prefs[modeKey]) }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { prefs -> prefs[modeKey] = mode.storageId }
    }
}
