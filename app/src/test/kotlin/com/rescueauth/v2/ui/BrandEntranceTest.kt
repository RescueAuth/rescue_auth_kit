package com.rescueauth.v2.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.components.rememberBrandEntrance
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BrandEntranceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun restoredPageStartsWithFinishedArtworkInsteadOfReplayingItsEntrance() {
        val firstValues = mutableListOf<Float>()
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            val alpha = rememberBrandEntrance()
            DisposableEffect(Unit) { firstValues.add(alpha.value); onDispose {} }
        }
        rule.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(listOf(0f, 1f), firstValues)
    }
}
