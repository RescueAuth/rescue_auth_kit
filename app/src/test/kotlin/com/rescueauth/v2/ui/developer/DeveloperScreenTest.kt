package com.rescueauth.v2.ui.developer

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.components.DeveloperEntryCard
import com.rescueauth.v2.ui.model.DeveloperEntryType
import com.rescueauth.v2.ui.model.DeveloperEntryUi
import com.rescueauth.v2.ui.model.SensitiveFieldUi
import com.rescueauth.v2.ui.screens.developer.DeveloperAddSheet
import com.rescueauth.v2.ui.screens.developer.DeveloperScreen
import com.rescueauth.v2.ui.developer.DeveloperListUiState
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Developer Vault UI / accessibility contract (Issue #20 §30):
 *
 * - the Add sheet exposes exactly the three P4 types;
 * - sensitive values are not shown by default in the list;
 * - reveal/copy buttons carry non-secret content descriptions (a plaintext
 *   secret must never be used as a contentDescription).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DeveloperScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val apiEntry = DeveloperEntryUi(
        id = "a1",
        stableId = "a1",
        type = DeveloperEntryType.API_CREDENTIAL,
        title = "Stripe",
        subtitle = "stripe · alice",
        sensitiveFields = listOf(
            SensitiveFieldUi(label = "apiKey", value = "sk_live_SUPERSECRET"),
            SensitiveFieldUi(label = "apiSecret", value = "SUPERSECRET"),
        ),
    )

    @Test
    fun addSheetExposesAllFiveP6Types() {
        composeRule.setContent {
            RescueAuthTheme {
                DeveloperAddSheet(onDismiss = {}, onSelectType = {})
            }
        }
        // ModalBottomSheet renders in its own window; use the unmerged tree so
        // the sheet's content nodes are discoverable.
        composeRule.onNodeWithText("API Credential", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("SSH Key", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Generic Secret", useUnmergedTree = true).assertExists()
        // Phase 4 P6 makes the final two types first-class clickable entries.
        composeRule.onNodeWithText("Android Signing Key", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Environment Variable Set", useUnmergedTree = true).assertExists()
    }

    @Test
    fun listShowsMetadataButNeverSecretPlaintext() {
        composeRule.setContent {
            RescueAuthTheme {
                DeveloperScreen(
                    uiState = DeveloperListUiState(
                        loading = false,
                        entries = listOf(apiEntry),
                    ),
                )
            }
        }
        // Metadata visible.
        composeRule.onNodeWithText("Stripe").assertIsDisplayed()
        // Secret value never rendered by default.
        composeRule.onNodeWithText("sk_live_SUPERSECRET").assertDoesNotExist()
        composeRule.onNodeWithText("SUPERSECRET").assertDoesNotExist()
    }

    @Test
    fun revealButtonContentDescriptionNeverContainsSecret() {
        composeRule.setContent {
            RescueAuthTheme {
                DeveloperEntryCard(entry = apiEntry) {
                    com.rescueauth.v2.ui.components.SensitiveValueRow(
                        label = "apiKey",
                        value = apiEntry.sensitiveFields[0].value,
                    )
                }
            }
        }
        // The reveal button's a11y description is the generic "Reveal" — never
        // the plaintext secret (Issue #20 §30).
        composeRule.onNodeWithContentDescription("Reveal").assertExists()
        composeRule.onNodeWithText("sk_live_SUPERSECRET").assertDoesNotExist()
    }
}
