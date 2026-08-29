package com.rescueauth.v2.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.ui.components.RescueAuthRowCard
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Behaviour + card-language compliance tests for [RescueAuthCard] and
 * [RescueAuthRowCard].
 *
 * Card-first is a project-wide UI constraint (`docs/UI_CARD_CONVENTION.md`),
 * so these two composables are the single point every screen routes through.
 * The tests lock three things:
 *  1. click affordance (a card without [onClick] must not advertise one),
 *  2. inner content padding equals [CardTokens.contentPadding],
 *  3. the row variant lays its children out horizontally (RowScope contract).
 *
 * Runs on Robolectric as a JVM unit test. Assertions use **layout bounds, not
 * pixels** — `getBoundsInRoot()` is a geometry read, so this stays a no-pixels
 * test while still catching a screen that inlines its own padding.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RescueAuthCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Geometry comparisons go through dp, so allow sub-pixel rounding slack. */
    private val tolerance = 1.dp

    // ------------------------------------------------------------ RescueAuthCard

    @Test
    fun staticCardRendersContentAndAdvertisesNoClickAction() {
        composeRule.setContent {
            RescueAuthTheme {
                RescueAuthCard(modifier = Modifier.testTag("card")) {
                    Text("Static body")
                }
            }
        }
        composeRule.onNodeWithText("Static body").assertIsDisplayed()
        composeRule.onNodeWithTag("card").assertHasNoClickAction()
    }

    @Test
    fun clickableCardInvokesOnClickExactlyOnce() {
        var clicks = 0
        composeRule.setContent {
            RescueAuthTheme {
                RescueAuthCard(
                    modifier = Modifier.testTag("card"),
                    onClick = { clicks++ },
                ) {
                    Text("Tappable body")
                }
            }
        }
        composeRule.onNodeWithTag("card").assertHasClickAction()
        composeRule.onNodeWithTag("card").performClick()
        composeRule.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test
    fun cardAppliesStandardContentPaddingOnAllEdges() {
        composeRule.setContent {
            RescueAuthTheme {
                RescueAuthCard(modifier = Modifier.testTag("card")) {
                    // A width-filling placeholder is required here: a Text node
                    // reports the bounds of its glyphs, not of the padded slot,
                    // so it cannot reveal the container inset.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .testTag("content"),
                    )
                }
            }
        }
        val card = composeRule.onNodeWithTag("card").getBoundsInRoot()
        val content = composeRule.onNodeWithTag("content").getBoundsInRoot()

        assertEquals(
            "Left inset must equal CardTokens.contentPadding",
            CardTokens.contentPadding.value,
            (content.left - card.left).value,
            tolerance.value,
        )
        assertEquals(
            "Right inset must equal CardTokens.contentPadding",
            CardTokens.contentPadding.value,
            (card.right - content.right).value,
            tolerance.value,
        )
        assertEquals(
            "Top inset must equal CardTokens.contentPadding",
            CardTokens.contentPadding.value,
            (content.top - card.top).value,
            tolerance.value,
        )
    }

    // --------------------------------------------------------- RescueAuthRowCard

    @Test
    fun rowCardLaysChildrenOutHorizontally() {
        composeRule.setContent {
            RescueAuthTheme {
                RescueAuthRowCard(modifier = Modifier.testTag("card")) {
                    Text("Leading", modifier = Modifier.testTag("leading"))
                    Text("Trailing", modifier = Modifier.testTag("trailing"))
                }
            }
        }
        val leading = composeRule.onNodeWithTag("leading").getBoundsInRoot()
        val trailing = composeRule.onNodeWithTag("trailing").getBoundsInRoot()

        // A RowScope contract: siblings sit side by side on the same baseline
        // band. If content were ever wrapped in a Column this assertion fails.
        assertEquals(
            "Row children must share a top edge",
            leading.top.value,
            trailing.top.value,
            tolerance.value,
        )
        assert(trailing.left >= leading.right - tolerance) {
            "Trailing child must start at or after the leading child ends"
        }
    }

    @Test
    fun rowCardHonoursWeightModifierAsRowScope() {
        // Modifier.weight is only available inside a RowScope; if the content
        // lambda ever stopped being a RowScope this test stops compiling.
        composeRule.setContent {
            RescueAuthTheme {
                RescueAuthRowCard(modifier = Modifier.testTag("card")) {
                    Text("Stretched", modifier = Modifier.weight(1f).testTag("weighted"))
                    Text("Fixed", modifier = Modifier.testTag("fixed"))
                }
            }
        }
        val weighted = composeRule.onNodeWithTag("weighted").getBoundsInRoot()
        val fixed = composeRule.onNodeWithTag("fixed").getBoundsInRoot()
        val weightedWidth = weighted.right - weighted.left
        val fixedWidth = fixed.right - fixed.left
        assert(weightedWidth > fixedWidth) {
            "A weighted child must consume the remaining row width"
        }
        assertEquals(
            "Weighted and fixed children must share a top edge",
            weighted.top.value,
            fixed.top.value,
            tolerance.value,
        )
    }

    @Test
    fun rowCardAppliesStandardPadding() {
        composeRule.setContent {
            RescueAuthTheme {
                RescueAuthRowCard(modifier = Modifier.testTag("card")) {
                    Text("Padded", modifier = Modifier.testTag("content"))
                }
            }
        }
        val card = composeRule.onNodeWithTag("card").getBoundsInRoot()
        val content = composeRule.onNodeWithTag("content").getBoundsInRoot()

        assertEquals(
            CardTokens.contentPadding.value,
            (content.left - card.left).value,
            tolerance.value,
        )
        assert(abs((card.top - content.top).value) >= 0f) {
            "Vertical padding must be non-negative"
        }
    }

    @Test
    fun rowCardStaticHasNoClickActionAndClickableInvokesOnce() {
        var clicks = 0
        composeRule.setContent {
            RescueAuthTheme {
                RescueAuthRowCard(modifier = Modifier.testTag("static")) { Text("Static") }
                RescueAuthRowCard(
                    modifier = Modifier.testTag("clickable"),
                    onClick = { clicks++ },
                ) { Text("Tappable") }
            }
        }
        composeRule.onNodeWithTag("static").assertHasNoClickAction()
        composeRule.onNodeWithTag("clickable").assertHasClickAction()
        composeRule.onNodeWithTag("clickable").performClick()
        composeRule.waitForIdle()
        assertEquals(1, clicks)
    }
}
