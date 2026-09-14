package com.rescueauth.v2.ui.developer

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.screens.developer.DeveloperFormScreen
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DeveloperFormScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun creationSeparatesDetailsFromCredentialsAndOnlyFinalStepSaves() {
        var saved = false
        showForm(mutableStateOf(DeveloperFormState(title = "Deployment")), onSubmit = { saved = true })
        composeRule.onNodeWithText("Title").assertExists()
        composeRule.onNodeWithText("API Key").assertDoesNotExist()
        composeRule.onNodeWithTag("developer_form_next").performClick()
        assertFalse(saved)
        composeRule.onNodeWithText("Title").assertDoesNotExist()
        composeRule.onNodeWithText("API Key").assertExists()
        composeRule.onNodeWithTag("developer_form_previous").performClick()
        composeRule.onNodeWithText("Title").assertExists()
        composeRule.onNodeWithTag("developer_form_next").performClick()
        composeRule.onNodeWithTag("developer_form_save").performClick()
        assertTrue(saved)
    }

    @Test
    fun submittingDisablesSaveAndKeepsThePendingFormInPlace() {
        val state = mutableStateOf(DeveloperFormState(title = "Deployment"))
        var submissions = 0
        var exits = 0
        showForm(state, onBack = { exits++ }, onSubmit = {
            submissions++
            state.value = state.value.copy(submitting = true)
        })
        composeRule.onNodeWithTag("developer_form_next").performClick()
        composeRule.onNodeWithTag("developer_form_save").performClick()
        composeRule.onNodeWithTag("developer_form_save").assertIsNotEnabled()
        composeRule.onNodeWithTag("developer_form_previous").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("API Key").assertExists()
        assertEquals(0, exits)
        assertEquals(1, submissions)
        composeRule.runOnIdle { state.value = state.value.copy(submitting = false) }
        composeRule.onNodeWithTag("developer_form_save").assertIsEnabled()
        composeRule.onNodeWithTag("developer_form_previous").assertIsEnabled()
    }

    @Test
    fun emptyTitleCannotAdvanceAndDoesNotSubmit() {
        val state = mutableStateOf(DeveloperFormState())
        var submitted = false
        showForm(state, onSubmit = { submitted = true })
        composeRule.onNodeWithTag("developer_form_next").assertIsNotEnabled()
        composeRule.runOnIdle { state.value = state.value.copy(title = "Deployment") }
        composeRule.onNodeWithTag("developer_form_next").assertIsEnabled()
        assertFalse(submitted)
    }

    private fun showForm(state: State<DeveloperFormState>, onBack: () -> Unit = {}, onSubmit: () -> Unit) {
        composeRule.setContent {
            RescueAuthTheme {
                DeveloperFormScreen(
                    form = state.value,
                    onBack = onBack,
                    onTitleChange = {},
                    onNotesChange = {},
                    onServiceNameChange = {},
                    onAccountNameChange = {},
                    onApiKeyChange = {},
                    onApiSecretChange = {},
                    onKeyNameChange = {},
                    onPublicKeyChange = {},
                    onPrivateKeyChange = {},
                    onPassphraseChange = {},
                    onFieldLabelChange = { _, _ -> },
                    onFieldValueChange = { _, _ -> },
                    onAddField = {},
                    onRemoveField = {},
                    onProjectNameChange = {},
                    onPackageNameChange = {},
                    onStorePasswordChange = {},
                    onKeyAliasChange = {},
                    onKeyPasswordChange = {},
                    onKeystoreSelected = { _, _ -> },
                    onClearKeystore = {},
                    onVariableNameChange = { _, _ -> },
                    onVariableValueChange = { _, _ -> },
                    onAddVariable = {},
                    onRemoveVariable = {},
                    onSubmit = onSubmit,
                )
            }
        }
    }
}
