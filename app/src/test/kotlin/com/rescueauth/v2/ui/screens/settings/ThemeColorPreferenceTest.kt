package com.rescueauth.v2.ui.screens.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.ThemeColor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Appearance → Theme Color settings UI tests.
 *
 * Verifies the presets are shown with their display names, the currently
 * selected preset is indicated, and tapping a preset reports the selection so
 * the caller can persist and live-refresh the theme.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ThemeColorPreferenceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        selected: ThemeColor = ThemeColor.DEFAULT,
        onSelect: (ThemeColor) -> Unit = {},
    ) {
        composeRule.setContent {
            RescueAuthTheme {
                SettingsScreen(
                    themeColor = selected,
                    onThemeColorSelected = onSelect,
                )
            }
        }
    }

    @Test
    fun appearanceSectionAndThemeColorRowAreShown() {
        setContent()
        composeRule.onNodeWithTag(ThemeColorPreferenceTestTags.ROW).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Appearance").assertIsDisplayed()
    }

    @Test
    fun allSixPresetsAreShownInDialog() {
        setContent()
        composeRule.onNodeWithTag(ThemeColorPreferenceTestTags.ROW).performScrollTo().performClick()
        composeRule.onNodeWithTag(ThemeColorPreferenceTestTags.DIALOG).assertIsDisplayed()
        ThemeColor.entries.forEach { color ->
            val name = when (color) {
                ThemeColor.SHIYI_ORANGE -> "Blue Violet"
                ThemeColor.CYAN_BLUE -> "Cobalt Blue"
                ThemeColor.JADE_GREEN -> "Periwinkle"
                ThemeColor.INDIGO -> "Indigo"
                ThemeColor.VIOLET -> "Violet"
                ThemeColor.ROSE -> "Plum Violet"
            }
            val inDialog = hasAnyAncestor(hasTestTag(ThemeColorPreferenceTestTags.DIALOG))
            composeRule.onNode(hasText(name) and inDialog)
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun selectedPresetIsReportedOnClick() {
        var selected: ThemeColor? = null
        setContent(onSelect = { selected = it })
        composeRule.onNodeWithTag(ThemeColorPreferenceTestTags.ROW).performScrollTo().performClick()
        composeRule.onNodeWithText("Violet").performScrollTo().performClick()
        assertEquals(ThemeColor.VIOLET, selected)
    }

    @Test
    fun defaultPresetIsShiyiOrange() {
        setContent()
        // The row shows the current (default) preset name.
        composeRule.onNodeWithTag(ThemeColorPreferenceTestTags.ROW).performScrollTo()
        composeRule.onNodeWithText("Blue Violet").assertIsDisplayed()
    }
}
