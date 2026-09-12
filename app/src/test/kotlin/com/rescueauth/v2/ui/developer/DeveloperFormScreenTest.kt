package com.rescueauth.v2.ui.developer

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.screens.developer.DeveloperFormScreen
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        composeRule.setContent {
            RescueAuthTheme {
        DeveloperFormScreen(
            form = DeveloperFormState(type = DeveloperFormType.API_CREDENTIAL, title = "Deployment"),
            onBack = {},
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
            onSubmit = { saved = true },
        )
            }
        }
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
}
