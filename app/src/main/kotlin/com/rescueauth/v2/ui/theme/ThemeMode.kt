package com.rescueauth.v2.ui.theme

/** Stable UI-only preference IDs; absence keeps the existing system-following behaviour. */
enum class ThemeMode(val storageId: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromStorageId(id: String?): ThemeMode = entries.firstOrNull { it.storageId == id } ?: SYSTEM
    }
}
