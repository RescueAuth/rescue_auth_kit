package com.rescueauth.v2.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.components.RescueAuthActionBar
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Use actual Android font metrics for the shared footer's large-text and RTL contract. */
@RunWith(AndroidJUnit4::class)
class UnifiedActionBarTest {
    @get:Rule val rule = createComposeRule()
    private var previous = 0
    private var submitted = 0

    private fun show(width: Dp = 412.dp, fontScale: Float = 1f, rtl: Boolean = false, enabled: Boolean = true) {
        rule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                RescueAuthTheme {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(width).testTag("action_bar_test_viewport")) {
                        RescueAuthActionBar(
                            secondaryLabel = "Cancel", secondaryIcon = Icons.AutoMirrored.Filled.ArrowBack,
                            onSecondaryClick = { previous++ }, primaryLabel = "Continue", primaryIcon = Icons.Filled.Check,
                            onPrimaryClick = { submitted++ }, secondaryEnabled = enabled, primaryEnabled = enabled,
                        )
                        }
                    }
                }
            }
        }
    }

    @Test fun regularBarKeepsBothCallbacksAndGivesPrimaryMoreSpace() {
        show()
        val secondary = rule.onNodeWithTag("page_action_secondary")
        val primary = rule.onNodeWithTag("page_action_primary")
        secondary.assertHeightIsAtLeast(52.dp).performClick()
        primary.assertHeightIsAtLeast(52.dp).performClick()
        assertEquals(1, previous)
        assertEquals(1, submitted)
        val left = secondary.getUnclippedBoundsInRoot()
        val right = primary.getUnclippedBoundsInRoot()
        assertTrue(left.right <= right.left)
        assertTrue(right.right - right.left > left.right - left.left)
        assertEquals(left.bottom - left.top, right.bottom - right.top)
    }

    @Test fun largeEnglishTextFitsANarrowViewport() {
        show(width = 320.dp, fontScale = 1.5f)
        val viewport = rule.onNodeWithTag("action_bar_test_viewport").getUnclippedBoundsInRoot()
        assertEquals(320.dp, viewport.right - viewport.left)
        listOf("Cancel", "Continue").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            val layout = layouts.single()
            assertFalse("Clipped label: $label; size=${layout.size}; paragraph=${layout.multiParagraph.width}; constraints=${layout.layoutInput.constraints}; fontScale=${layout.layoutInput.density.fontScale}", layout.hasVisualOverflow)
        }
        val secondary = rule.onNodeWithTag("page_action_secondary").assertHeightIsAtLeast(52.dp).getUnclippedBoundsInRoot()
        val primary = rule.onNodeWithTag("page_action_primary").assertHeightIsAtLeast(52.dp).getUnclippedBoundsInRoot()
        assertEquals(secondary.bottom - secondary.top, primary.bottom - primary.top)
        assertTrue(secondary.right <= primary.left)
        assertTrue(primary.right <= viewport.right)
    }

    @Test fun rtlMirrorsActionOrderWithoutChangingCallbacks() {
        show(rtl = true)
        val secondary = rule.onNodeWithTag("page_action_secondary").getUnclippedBoundsInRoot()
        val primary = rule.onNodeWithTag("page_action_primary").getUnclippedBoundsInRoot()
        assertTrue(primary.right <= secondary.left)
        rule.onNodeWithTag("page_action_primary").performClick()
        assertEquals(1, submitted)
        assertEquals(0, previous)
    }

    @Test fun submittingCannotDispatchEitherAction() {
        show(enabled = false)
        rule.onNodeWithTag("page_action_secondary").assertIsNotEnabled().performClick()
        rule.onNodeWithTag("page_action_primary").assertIsNotEnabled().performClick()
        assertEquals(0, previous)
        assertEquals(0, submitted)
    }
}
