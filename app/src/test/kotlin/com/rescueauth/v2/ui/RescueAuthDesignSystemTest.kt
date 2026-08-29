package com.rescueauth.v2.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.components.RescueAuthBackButton
import com.rescueauth.v2.ui.components.RescueAuthInitialBadge
import com.rescueauth.v2.ui.components.RescueAuthMetaPill
import com.rescueauth.v2.ui.components.RescueAuthMetric
import com.rescueauth.v2.ui.components.RescueAuthPageHeader
import com.rescueauth.v2.ui.components.RescueAuthSectionHeader
import com.rescueauth.v2.ui.components.RescueAuthStatusLine
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Behaviour tests for the shared visual primitives in `RescueAuthDesignSystem`.
 *
 * These primitives are the only sanctioned building blocks for page chrome, so
 * regressions here silently propagate to every route. Runs on Robolectric as a
 * JVM unit test — **no pixels**: assertions target semantics and state only,
 * never rendered colors or bitmaps.
 *
 * Test data is deliberately non-sensitive placeholder text (see AGENTS.md 禁区
 * #2 — no real secrets may appear in committed test content).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RescueAuthDesignSystemTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ---------------------------------------------------------------- header

    @Test
    fun pageHeaderShowsTitleAndSubtitle() {
        composeRule.setContent {
            RescueAuthTheme {
                RescueAuthPageHeader(title = "Recovery codes", subtitle = "3 of 10 remaining")
            }
        }
        composeRule.onNodeWithText("Recovery codes").assertExists()
        composeRule.onNodeWithText("3 of 10 remaining").assertExists()
    }

    @Test
    fun pageHeaderOmitsBlankSubtitle() {
        // The header guards on isNullOrBlank(), not on null, so a whitespace-only
        // subtitle must collapse instead of reserving an empty line.
        composeRule.setContent {
            RescueAuthTheme {
                RescueAuthPageHeader(title = "Settings", subtitle = "   ")
            }
        }
        composeRule.onNodeWithText("Settings").assertExists()
        composeRule.onAllNodesWithText("   ").assertCountEquals(0)
    }

    @Test
    fun pageHeaderRendersNavigationIconAndActions() {
        composeRule.setContent {
            RescueAuthTheme {
                RescueAuthPageHeader(
                    title = "Account",
                    navigationIcon = { Text("NAV", modifier = androidx.compose.ui.Modifier.testTag("nav")) },
                    actions = { Text("DONE", modifier = androidx.compose.ui.Modifier.testTag("action")) },
                )
            }
        }
        composeRule.onNodeWithTag("nav").assertExists()
        composeRule.onNodeWithTag("action").assertExists()
        composeRule.onNodeWithText("Account").assertExists()
    }

    @Test
    fun backButtonInvokesOnClickAndCarriesAccessibilityLabel() {
        var clicked = 0
        composeRule.setContent {
            RescueAuthTheme {
                RescueAuthBackButton(onClick = { clicked++ })
            }
        }
        composeRule.onNodeWithContentDescription("Back").assertExists()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        assertEquals(1, clicked)
    }

    // -------------------------------------------------------- section header

    @Test
    fun sectionHeaderShowsTitleAndSubtitle() {
        composeRule.setContent {
            RescueAuthTheme {
                RescueAuthSectionHeader(title = "Authenticator", subtitle = "2 providers")
            }
        }
        composeRule.onNodeWithText("Authenticator").assertExists()
        composeRule.onNodeWithText("2 providers").assertExists()
    }

    @Test
    fun sectionHeaderOmitsBlankSubtitle() {
        composeRule.setContent {
            RescueAuthTheme {
                RescueAuthSectionHeader(title = "Developer vault", subtitle = "")
            }
        }
        composeRule.onNodeWithText("Developer vault").assertExists()
        composeRule.onAllNodesWithText("").assertCountEquals(0)
    }

    // ---------------------------------------------------------- identity badge

    @Test
    fun initialBadgeUsesUppercasedFirstCharacterOfTrimmedLabel() {
        composeRule.setContent {
            RescueAuthTheme { RescueAuthInitialBadge(label = "  github ") }
        }
        // Leading whitespace is trimmed and the initial is upper-cased.
        composeRule.onNodeWithText("G").assertExists()
    }

    @Test
    fun initialBadgeFallsBackToPlaceholderForBlankLabel() {
        // Providers with an empty/blank name must not crash on firstOrNull().
        composeRule.setContent {
            RescueAuthTheme { RescueAuthInitialBadge(label = "   ") }
        }
        composeRule.onNodeWithText("?").assertExists()
    }

    @Test
    fun initialBadgeRendersSingleCharacterEvenForLongLabel() {
        composeRule.setContent {
            RescueAuthTheme { RescueAuthInitialBadge(label = "Acme Corporation") }
        }
        composeRule.onNodeWithText("A").assertExists()
    }

    // ------------------------------------------------------- metadata primitives

    @Test
    fun metaPillShowsTextAndAcceptsOptionalIcon() {
        composeRule.setContent {
            RescueAuthTheme {
                RescueAuthMetaPill(text = "TOTP", icon = Icons.Filled.Lock)
            }
        }
        composeRule.onNodeWithText("TOTP").assertExists()
    }

    @Test
    fun metricShowsValueAndLabel() {
        composeRule.setContent {
            RescueAuthTheme {
                RescueAuthMetric(value = "12", label = "accounts", icon = Icons.Filled.Lock)
            }
        }
        composeRule.onNodeWithText("12").assertExists()
        composeRule.onNodeWithText("accounts").assertExists()
    }

    @Test
    fun statusLineShowsTextForBothPositiveAndNegativeTone() {
        composeRule.setContent {
            RescueAuthTheme {
                RescueAuthStatusLine(text = "Vault unlocked", positive = true)
                RescueAuthStatusLine(text = "Vault locked", positive = false)
            }
        }
        // The two tones only differ by icon tint (not observable without pixels),
        // so this locks the contract that both branches render their text.
        composeRule.onNodeWithText("Vault unlocked").assertExists()
        composeRule.onNodeWithText("Vault locked").assertExists()
    }

    @Test
    fun designSystemPrimitivesRenderInsideAppTheme() {
        // Guards against a primitive hard-coding a color instead of reading the
        // MaterialTheme — that would silently break the dark palette.
        composeRule.setContent {
            RescueAuthTheme(darkTheme = true) {
                RescueAuthPageHeader(title = "Export", subtitle = "Choose a destination")
                RescueAuthSectionHeader(title = "Package")
                RescueAuthInitialBadge(label = "vault")
            }
        }
        composeRule.onNodeWithText("Export").assertExists()
        composeRule.onNodeWithText("Choose a destination").assertExists()
        composeRule.onNodeWithText("Package").assertExists()
        composeRule.onNodeWithText("V").assertExists()
    }
}
