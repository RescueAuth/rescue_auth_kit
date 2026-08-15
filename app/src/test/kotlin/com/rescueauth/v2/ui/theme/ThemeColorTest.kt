package com.rescueauth.v2.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the [ThemeColor] model — stable persistence IDs, default
 * fallback and corrupted-value resilience.
 */
class ThemeColorTest {

    @Test
    fun defaultIsShiyiOrange() {
        assertEquals(ThemeColor.SHIYI_ORANGE, ThemeColor.DEFAULT)
    }

    @Test
    fun storageIdsAreStableAndDistinct() {
        val ids = ThemeColor.entries.map { it.storageId }
        assertEquals(ThemeColor.entries.size, ids.distinct().size)
        assertEquals("shiyi_orange", ThemeColor.SHIYI_ORANGE.storageId)
        assertEquals("cyan_blue", ThemeColor.CYAN_BLUE.storageId)
        assertEquals("jade_green", ThemeColor.JADE_GREEN.storageId)
        assertEquals("indigo", ThemeColor.INDIGO.storageId)
        assertEquals("violet", ThemeColor.VIOLET.storageId)
        assertEquals("rose", ThemeColor.ROSE.storageId)
    }

    @Test
    fun fromStorageIdResolvesKnownIds() {
        assertEquals(ThemeColor.VIOLET, ThemeColor.fromStorageId("violet"))
        assertEquals(ThemeColor.ROSE, ThemeColor.fromStorageId("rose"))
        assertEquals(ThemeColor.SHIYI_ORANGE, ThemeColor.fromStorageId("shiyi_orange"))
    }

    @Test
    fun fromStorageIdFallsBackToDefaultOnUnknown() {
        assertEquals(ThemeColor.DEFAULT, ThemeColor.fromStorageId("not_a_color"))
    }

    @Test
    fun fromStorageIdFallsBackToDefaultOnNull() {
        assertEquals(ThemeColor.DEFAULT, ThemeColor.fromStorageId(null))
    }

    @Test
    fun fromStorageIdFallsBackToDefaultOnBlank() {
        assertEquals(ThemeColor.DEFAULT, ThemeColor.fromStorageId(""))
    }
}
