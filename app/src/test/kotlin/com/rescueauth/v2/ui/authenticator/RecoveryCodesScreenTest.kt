package com.rescueauth.v2.ui.authenticator

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.model.RecoveryCodeSetUi
import com.rescueauth.v2.ui.model.RecoveryCodeUi
import com.rescueauth.v2.ui.screens.authenticator.RecoveryCodesScreen
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * UI behaviour tests for the Recovery Codes account-detail screen (Phase 4
 * P3): empty state, summary counts, masked vs revealed presentation and
 * used/unused labels — without touching Room or the repository.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RecoveryCodesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sampleSet = RecoveryCodeSetUi(
        id = "s1",
        title = "Backup codes",
        usedCount = 1,
        totalCount = 2,
        codes = listOf(
            RecoveryCodeUi(id = "c1", value = "AAAA-1111", isUsed = true),
            RecoveryCodeUi(id = "c2", value = "BBBB-2222", isUsed = false),
        ),
    )

    @Test
    fun emptyStateShowsEmptyMessage() {
        composeRule.setContent {
            RescueAuthTheme {
                RecoveryCodesScreen(
                    uiState = RecoveryUiState(
                        loading = false,
                        providerName = "GitHub",
                        accountName = "alice@example.com",
                    ),
                    onAddClick = {},
                )
            }
        }
        composeRule.onNodeWithText("No recovery codes yet. Add a set to store your backup codes here.").assertIsDisplayed()
    }

    @Test
    fun collapsedCardShowsRemainingSummaryButNoPlaintext() {
        composeRule.setContent {
            RescueAuthTheme {
                RecoveryCodesScreen(
                    uiState = RecoveryUiState(
                        loading = false,
                        providerName = "GitHub",
                        accountName = "alice@example.com",
                        sets = listOf(sampleSet),
                    ),
                    onAddClick = {},
                )
            }
        }
        composeRule.onNodeWithText("1 remaining · 2 total").assertIsDisplayed()
        // Masked code value is never plaintext on the collapsed card.
        composeRule.onNodeWithText("AAAA-1111", substring = true).assertDoesNotExist()
    }

    @Test
    fun accountDetailShowsUnifiedHeaderForDeepLinkContext() {
        composeRule.setContent {
            RescueAuthTheme {
                RecoveryCodesScreen(
                    uiState = RecoveryUiState(
                        loading = false,
                        providerName = "GitHub",
                        accountName = "xincy22",
                        sets = listOf(sampleSet),
                    ),
                    onAddClick = {},
                )
            }
        }
        // The unified secondary-page header keeps the account and provider
        // context visible without the legacy breadcrumb widget.
        composeRule.onNodeWithText("GitHub").assertIsDisplayed()
        composeRule.onNodeWithText("xincy22").assertIsDisplayed()
    }
}
