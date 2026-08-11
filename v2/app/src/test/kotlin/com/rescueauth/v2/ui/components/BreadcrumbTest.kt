package com.rescueauth.v2.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Focused tests for the unified top-level breadcrumb navigation (Issue #62).
 *
 * Covers the max-3 visual-segment rule, the `…` collapse + hidden-ancestor
 * menu, ancestor vs current click semantics, and long-name safety. Runs on
 * Robolectric as a JVM unit test (no pixels — state/logic assertions only).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BreadcrumbTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        items: List<BreadcrumbItem>,
        onNavigate: (String) -> Unit = {},
        ellipsisContentDescription: String? = null,
        moreMenuContentDescription: String? = null,
    ) {
        composeRule.setContent {
            RescueAuthTheme {
                BreadcrumbTopBar(
                    items = items,
                    onNavigate = onNavigate,
                    ellipsisContentDescription = ellipsisContentDescription,
                    moreMenuContentDescription = moreMenuContentDescription,
                )
            }
        }
    }

    // --- 1. single level --------------------------------------------------
    @Test
    fun singleLevelShowsOnlyCurrentPage() {
        render(items = listOf(BreadcrumbItem("Settings", isCurrent = true)))
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        // No ancestor separator → only one segment rendered.
        composeRule.onAllNodesWithText("›").assertCountEquals(0)
    }

    // --- 2. two levels ----------------------------------------------------
    @Test
    fun twoLevelsShowBoth() {
        render(
            items = listOf(
                BreadcrumbItem("Settings", "settings"),
                BreadcrumbItem("Appearance", "appearance", isCurrent = true),
            ),
        )
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Appearance").assertIsDisplayed()
    }

    // --- 3. three levels --------------------------------------------------
    @Test
    fun threeLevelsShowAll() {
        render(
            items = listOf(
                BreadcrumbItem("Settings", "settings"),
                BreadcrumbItem("Data", "data"),
                BreadcrumbItem("Import", null, isCurrent = true),
            ),
        )
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Data").assertIsDisplayed()
        composeRule.onNodeWithText("Import").assertIsDisplayed()
        // Exactly three visible segments → no ellipsis.
        composeRule.onNodeWithTag("breadcrumb-ellipsis").assertDoesNotExist()
    }

    // --- 4. four levels collapse to `…` + last two ------------------------
    @Test
    fun fourLevelsCollapseEarliestAncestors() {
        render(
            items = listOf(
                BreadcrumbItem("Authenticator", "authenticator"),
                BreadcrumbItem("GitHub", "github"),
                BreadcrumbItem("xincy22", "xincy22"),
                BreadcrumbItem("Primary TOTP", null, isCurrent = true),
            ),
        )
        // The two earliest ancestors are hidden behind the ellipsis.
        composeRule.onNodeWithText("Authenticator").assertDoesNotExist()
        composeRule.onNodeWithText("GitHub").assertDoesNotExist()
        // The last parent + current remain visible.
        composeRule.onNodeWithText("xincy22").assertIsDisplayed()
        composeRule.onNodeWithText("Primary TOTP").assertIsDisplayed()
        // Ellipsis present.
        composeRule.onNodeWithTag("breadcrumb-ellipsis").assertIsDisplayed()
    }

    // --- 5. five+ levels still max three visual segments ------------------
    @Test
    fun fiveOrMoreLevelsStillRenderAtMostThreeSegments() {
        render(
            items = listOf(
                BreadcrumbItem("A", "a"),
                BreadcrumbItem("B", "b"),
                BreadcrumbItem("C", "c"),
                BreadcrumbItem("D", "d"),
                BreadcrumbItem("E", "e"),
                BreadcrumbItem("F", "f", isCurrent = true),
            ),
        )
        // Only the last parent (E) and current (F) are visible text segments.
        composeRule.onNodeWithText("E").assertIsDisplayed()
        composeRule.onNodeWithText("F").assertIsDisplayed()
        composeRule.onNodeWithText("A").assertDoesNotExist()
        composeRule.onNodeWithText("B").assertDoesNotExist()
        composeRule.onNodeWithText("C").assertDoesNotExist()
        composeRule.onNodeWithText("D").assertDoesNotExist()
        // One ellipsis = still a single collapsed segment (max 3 visual total).
        composeRule.onNodeWithTag("breadcrumb-ellipsis").assertIsDisplayed()
    }

    // --- 6. clicking an ancestor navigates to its destination -------------
    @Test
    fun clickingAncestorNavigatesToItsDestination() {
        val navigated = mutableListOf<String>()
        render(
            items = listOf(
                BreadcrumbItem("Settings", "settings"),
                BreadcrumbItem("Appearance", "appearance", isCurrent = true),
            ),
            onNavigate = { navigated.add(it) },
        )
        composeRule.onNodeWithText("Settings").performClick()
        // The ancestor tap resolved to its destination.
        assert(navigated == listOf("settings"))
    }

    // --- 7. clicking the current page does not navigate -------------------
    @Test
    fun clickingCurrentPageDoesNotNavigate() {
        val navigated = mutableListOf<String>()
        render(
            items = listOf(
                BreadcrumbItem("Settings", "settings"),
                BreadcrumbItem("Appearance", "appearance", isCurrent = true),
            ),
            onNavigate = { navigated.add(it) },
        )
        composeRule.onNodeWithText("Appearance").performClick()
        assert(navigated.isEmpty())
    }

    // --- 8/9. ellipsis lists + selects hidden ancestors --------------------
    @Test
    fun ellipsisShowsHiddenAncestorsAndSelectionNavigates() {
        val navigated = mutableListOf<String>()
        render(
            items = listOf(
                BreadcrumbItem("Authenticator", "authenticator"),
                BreadcrumbItem("GitHub", "github"),
                BreadcrumbItem("xincy22", "xincy22"),
                BreadcrumbItem("Primary TOTP", null, isCurrent = true),
            ),
            onNavigate = { navigated.add(it) },
            ellipsisContentDescription = "Show more ancestors",
            moreMenuContentDescription = "More ancestors",
        )
        composeRule.onNodeWithTag("breadcrumb-ellipsis").performClick()
        // Hidden ancestors now appear in the dropdown.
        composeRule.onNodeWithText("Authenticator").assertIsDisplayed()
        composeRule.onNodeWithText("GitHub").assertIsDisplayed()
        // Select a hidden ancestor → navigate to its destination.
        composeRule.onNodeWithText("GitHub").performClick()
        assert(navigated == listOf("github"))
    }

    // --- 10. long name does not overflow (renders, no crash) ---------------
    @Test
    fun longNameStillRendersWithoutOverflow() {
        val veryLongAccount = "very-long-account-name@example.com-" + "x".repeat(80)
        render(
            items = listOf(
                BreadcrumbItem("Authenticator", "authenticator"),
                BreadcrumbItem("GitHub", "github"),
                BreadcrumbItem(veryLongAccount, null, isCurrent = true),
            ),
        )
        // The long current segment renders (truncated via ellipsis internally).
        composeRule.onNodeWithText(veryLongAccount).assertExists()
    }

    // --- 12. static labels / bilingual resolved via string resources -------
    // The English strings are exercised through the screens; here we just
    // confirm the ellipsis accessibility description is applied so TalkBack
    // never reads the chevron.
    @Test
    fun ellipsisCarriesContentDescriptionForAccessibility() {
        render(
            items = listOf(
                BreadcrumbItem("A", "a"),
                BreadcrumbItem("B", "b"),
                BreadcrumbItem("C", "c"),
                BreadcrumbItem("D", "d", isCurrent = true),
            ),
            ellipsisContentDescription = "Show more ancestors",
        )
        composeRule.onNodeWithContentDescription("Show more ancestors").assertIsDisplayed()
    }
}
