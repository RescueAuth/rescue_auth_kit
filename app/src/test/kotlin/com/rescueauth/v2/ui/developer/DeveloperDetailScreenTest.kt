package com.rescueauth.v2.ui.developer

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.model.DeveloperDetailUi
import com.rescueauth.v2.ui.screens.developer.DeveloperDetailScreen
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DeveloperDetailScreenTest {
    @get:Rule val rule = createComposeRule()
    private val generic = DeveloperDetailUi.GenericSecret("generic", "Personal project", listOf("Username", "Password"))

    @Test fun explanationIsOptionalAndNeverRequestsASecret() {
        var reveals = 0
        var copies = 0
        show(mutableStateOf(DeveloperDetailUiState(loading = false, detail = generic)),
            onReveal = { reveals++ }, onCopy = { copies++ })
        rule.onNodeWithTag("developer_protection_info").assertIsDisplayed().performClick()
        rule.onNodeWithTag("developer_protection_explanation").assertIsDisplayed()
        assertEquals(0, reveals)
        assertEquals(0, copies)
        rule.onNodeWithTag("developer_protection_close")
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithTag("developer_protection_explanation").assertDoesNotExist()
        rule.onAllNodesWithContentDescription("Copy").onFirst().performClick()
        assertEquals(1, copies)
        assertEquals(0, reveals)
        // Copy still directly dispatches its existing one-shot authentication path.
        rule.onNodeWithTag("developer_protection_explanation").assertDoesNotExist()
    }

    @Test fun allFiveTypesKeepEditAndDeleteCallbacksInTheSharedFooter() {
        val state = mutableStateOf(DeveloperDetailUiState(loading = false, detail = generic))
        var edits = 0
        var deletes = 0
        show(state, onEdit = { edits++ }, onDelete = { deletes++ })
        val details = listOf(
            generic,
            DeveloperDetailUi.ApiCredential("api", "Demo API", "Service", "Account"),
            DeveloperDetailUi.SshKey("ssh", "Demo SSH", "Key name", false),
            DeveloperDetailUi.AndroidSigningKey("signing", "Demo signing", "Project", "com.example.demo", "demo.jks", "alias"),
            DeveloperDetailUi.EnvironmentVariableSet("env", "Demo variables", "Project", listOf("API_URL")),
        )
        details.forEach { detail ->
            rule.runOnIdle { state.value = DeveloperDetailUiState(loading = false, detail = detail) }
            rule.onNodeWithTag("page_action_bar").assertIsDisplayed()
            rule.onNodeWithTag("developer_detail_delete").assertIsDisplayed().performClick()
            rule.onNodeWithTag("developer_detail_edit").assertIsDisplayed().performClick()
        }
        assertEquals(5, edits)
        assertEquals(5, deletes)
    }

    @Test fun loadingOrRemovedEntriesDoNotExposeStaleActions() {
        val state = mutableStateOf(DeveloperDetailUiState(loading = false, detail = generic))
        show(state)
        rule.onNodeWithTag("developer_detail_edit").assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(loading = true) }
        rule.onNodeWithTag("page_action_bar").assertDoesNotExist()
        rule.runOnIdle { state.value = DeveloperDetailUiState(loading = false) }
        rule.onNodeWithTag("page_action_bar").assertDoesNotExist()
        rule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test fun explanationClosesWhenTheDisplayedEntryChanges() {
        val state = mutableStateOf(DeveloperDetailUiState(loading = false, detail = generic))
        show(state)
        rule.onNodeWithTag("developer_protection_info").performClick()
        rule.onNodeWithTag("developer_protection_explanation").assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(detail = generic.copy(stableId = "other", title = "Other item")) }
        rule.onNodeWithTag("developer_protection_explanation").assertDoesNotExist()
        rule.onNodeWithText("Other item").assertIsDisplayed()
    }

    private fun show(state: State<DeveloperDetailUiState>, onEdit: () -> Unit = {}, onDelete: () -> Unit = {},
        onReveal: () -> Unit = {}, onCopy: () -> Unit = {}) {
        rule.setContent {
            RescueAuthTheme {
                DeveloperDetailScreen(
                    uiState = state.value, onBack = {}, onEdit = onEdit, onDelete = onDelete,
                    onRevealApiKey = onReveal, onRevealApiSecret = onReveal,
                    onCopyApiKey = onCopy, onCopyApiSecret = onCopy,
                    onRevealPrivateKey = onReveal, onCopyPrivateKey = onCopy,
                    onRevealPassphrase = onReveal, onCopyPassphrase = onCopy,
                    onRevealGeneric = { onReveal() }, onCopyGeneric = { onCopy() },
                    getRevealedValue = { null }, isRevealed = { false },
                    onRevealStorePassword = onReveal, onCopyStorePassword = onCopy,
                    onRevealKeyPassword = onReveal, onCopyKeyPassword = onCopy,
                    onExportKeystore = {}, onCopyKeyProperties = {},
                    onRevealEnvVar = { onReveal() }, onCopyEnvVar = { onCopy() },
                )
            }
        }
    }
}
