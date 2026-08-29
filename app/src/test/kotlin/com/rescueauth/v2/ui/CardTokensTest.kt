package com.rescueauth.v2.ui

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.CornerRadius
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Contract tests for [CardTokens] — the single source of truth for the
 * card language.
 *
 * `docs/UI_CARD_CONVENTION.md` and AGENTS.md 禁区 #8 require every screen to
 * route through these tokens instead of hard-coding surface colors, corner
 * radii, or card padding. These tests pin the token *values* and, more
 * importantly, the two invariants that keep the two card variants visually
 * distinguishable and theme-driven:
 *
 *  - the standard and elevated fills must differ, otherwise `RescueAuthCard`
 *    and `RescueAuthRowCard` collapse into the same surface;
 *  - the fills must follow the active color scheme, otherwise a future palette
 *    change (or dark mode) silently stops reaching cards.
 *
 * Runs on Robolectric as a JVM unit test — no pixels; tokens are read as values.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CardTokensTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun geometryIsDerivedFromTheSharedScale() {
        assertEquals(16.dp, CardTokens.contentPadding)
        assertEquals(Spacing.md, CardTokens.contentPadding)
        assertEquals(Spacing.sm, CardTokens.listSpacing)
        assertEquals(Spacing.md, CardTokens.listOuterPadding)
    }

    @Test
    fun shapeUsesTheStandardCardCornerRadius() {
        // Compared corner-by-corner because CornerBasedShape equality is not
        // part of the public contract.
        val expected = CornerSize(CornerRadius.xs)
        assertEquals(expected, CardTokens.shape.topStart)
        assertEquals(expected, CardTokens.shape.topEnd)
        assertEquals(expected, CardTokens.shape.bottomStart)
        assertEquals(expected, CardTokens.shape.bottomEnd)
    }

    @Test
    fun standardAndElevatedFillsAreDistinct() {
        val fills = captureFills(darkTheme = false)
        assertNotEquals(
            "RescueAuthCard and RescueAuthRowCard must not collapse onto the same fill",
            fills.first,
            fills.second,
        )
    }

    @Test
    fun outlineColorCarriesTheDocumentedAlpha() {
        var outline: Color? = null
        composeRule.setContent {
            RescueAuthTheme { outline = CardTokens.outlineColor() }
        }
        composeRule.waitForIdle()
        assertEquals(0.55f, outline!!.alpha, 0.001f)
    }

    @Test
    fun cardFillsFollowTheActiveColorScheme() {
        // Catches a hard-coded card color: a fixed constant would survive both
        // themes and make this assertion fail. Both schemes are captured in a
        // single setContent — Robolectric allows only one per test.
        val lightStandard = mutableStateOf<Color?>(null)
        val lightElevated = mutableStateOf<Color?>(null)
        val darkStandard = mutableStateOf<Color?>(null)
        val darkElevated = mutableStateOf<Color?>(null)

        composeRule.setContent {
            RescueAuthTheme(darkTheme = false) {
                lightStandard.value = CardTokens.containerColor()
                lightElevated.value = CardTokens.elevatedContainerColor()
                RescueAuthTheme(darkTheme = true) {
                    darkStandard.value = CardTokens.containerColor()
                    darkElevated.value = CardTokens.elevatedContainerColor()
                }
            }
        }
        composeRule.waitForIdle()

        assertNotEquals(lightStandard.value, darkStandard.value)
        assertNotEquals(lightElevated.value, darkElevated.value)
    }

    /** Resolves both card fills inside a themed composition. */
    private fun captureFills(darkTheme: Boolean): Pair<Color, Color> {
        val standard = mutableStateOf<Color?>(null)
        val elevated = mutableStateOf<Color?>(null)
        composeRule.setContent {
            RescueAuthTheme(darkTheme = darkTheme) {
                standard.value = CardTokens.containerColor()
                elevated.value = CardTokens.elevatedContainerColor()
            }
        }
        composeRule.waitForIdle()
        return standard.value!! to elevated.value!!
    }
}
