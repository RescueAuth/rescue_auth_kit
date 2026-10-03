package com.rescueauth.v2.ui.screens.authenticator

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.authenticator.*
import com.rescueauth.v2.ui.components.*
import com.rescueauth.v2.ui.model.AccountUi
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.Spacing

private enum class AccountEditorPage { FORM, PROVIDER, ACCOUNT, ADVANCED, RECOVERY_NAME }

/** One editor for the vault home, a service and an account. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountAddSheet(
    form: AccountAddFormState, accounts: List<AccountUi>, onDismiss: () -> Unit,
    onAccountSelected: (AccountUi?) -> Unit, onAccountNameChange: (String) -> Unit,
    onKindChange: (AccountAddContentKind) -> Unit, onTotpChange: ((AddTotpFormState) -> AddTotpFormState) -> Unit,
    onRecoveryTitleChange: (String) -> Unit, onRecoveryValuesChange: (String) -> Unit,
    onStartScan: () -> Unit, onSubmit: () -> Unit, allowAccountSelection: Boolean = true,
    onProviderChange: (String) -> Unit = {},
) {
    var page by remember { mutableStateOf(AccountEditorPage.FORM) }
    var query by remember(page) { mutableStateOf("") }
    val back = { page = AccountEditorPage.FORM }
    val child = page != AccountEditorPage.FORM
    val root = form.scope == AccountAddScope.VAULT
    val providers = accounts.map { it.providerName }.distinct().sorted()
    val targets = accounts.filter { it.providerName == form.provider.trim() }
    RescueAuthSheet(
        title = if (child) stringResource(when (page) {
            AccountEditorPage.PROVIDER -> R.string.sheet_choose_service
            AccountEditorPage.ACCOUNT -> R.string.add_select_account
            AccountEditorPage.ADVANCED -> R.string.add_totp_advanced
            else -> R.string.recovery_codes_custom_name
        }) else stringResource(if (root) R.string.account_add_home_title else if (form.selectedAccountId == null) R.string.account_add_title else R.string.account_add_content_title),
        icon = Icons.Filled.PersonAdd, onDismiss = onDismiss, modifier = Modifier.testTag("account_add_sheet"),
        onBack = if (child) back else null,
        subtitle = if (root || child) null else if (allowAccountSelection) form.provider else form.accountName,
        dismissEnabled = !form.submitting,
        footer = { dismiss -> if (child) RescueAuthSheetPageActions(back) else RescueAuthActionBar(stringResource(R.string.common_cancel), Icons.Filled.Close, dismiss,
            stringResource(R.string.recovery_codes_save), Icons.Filled.Check, onSubmit,
            secondaryEnabled = !form.submitting, primaryEnabled = !form.submitting && form.accountName.isNotBlank() && form.provider.isNotBlank(),
            secondaryTestTag = "account_add_cancel", primaryTestTag = "account_add_submit") },
    ) {
        if (page == AccountEditorPage.PROVIDER || page == AccountEditorPage.ACCOUNT) {
            RescueAuthTextField(query, { query = it }, label = { Text(stringResource(R.string.sheet_search_choices)) },
                modifier = Modifier.fillMaxWidth().testTag("account_add_search"), singleLine = true)
            val choices = if (page == AccountEditorPage.PROVIDER) buildList {
                add(InlinePickerChoice("account_add_service_new", stringResource(R.string.account_add_provider_new), Icons.Filled.Add,
                    alwaysVisible = true) { onProviderChange(""); back() })
                providers.forEach { provider -> add(InlinePickerChoice("account_add_service_$provider", provider, Icons.Filled.GridView,
                    selected = form.provider.trim() == provider) { onProviderChange(provider); back() }) }
            } else buildList {
                add(InlinePickerChoice("account_add_target_new", stringResource(R.string.account_add_new), Icons.Filled.PersonAdd,
                    alwaysVisible = true) { onAccountSelected(null); back() })
                targets.forEach { account -> add(InlinePickerChoice("account_add_target_${account.id}", account.accountName, Icons.Filled.Person,
                    selected = form.selectedAccountId == account.id) { onAccountSelected(account); back() }) }
            }
            RescueAuthChoiceList(choices, if (page == AccountEditorPage.PROVIDER) "account_add_service_choices" else "account_add_choices", query)
        } else if (page == AccountEditorPage.ADVANCED) RescueAuthCard {
            TotpAdvancedFields(form.totp,
                { value -> onTotpChange { it.copy(algorithm = value) } }, { value -> onTotpChange { it.copy(digits = value) } },
                { value -> onTotpChange { it.copy(periodSeconds = value) } }, enabled = !form.submitting)
        } else if (page == AccountEditorPage.RECOVERY_NAME) RescueAuthCard {
            RecoveryNameField(form.recovery, onRecoveryTitleChange, !form.submitting)
        } else {
        RescueAuthCard(containerColor = CardTokens.containerColor()) {
            Column(verticalArrangement = Arrangement.spacedBy(CardTokens.formFieldSpacing)) {
                if (root) RescueAuthPickerField(form.provider, onProviderChange, stringResource(R.string.provider_name_label),
                    onOpenChoices = { page = AccountEditorPage.PROVIDER }, fieldTag = "account_add_provider", toggleTag = "account_add_service_picker", enabled = !form.submitting)
                if (allowAccountSelection) RescueAuthPickerField(form.accountName, onAccountNameChange, stringResource(R.string.account_name_label),
                    onOpenChoices = { page = AccountEditorPage.ACCOUNT }, fieldTag = "account_add_name", toggleTag = "account_add_recipient", enabled = !form.submitting)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    AccountAddContentKind.entries.filter { form.selectedAccountId == null || it != AccountAddContentKind.NONE }.forEach { kind ->
                        FilterChip(selected = form.kind == kind, enabled = !form.submitting, onClick = { onKindChange(kind) },
                            modifier = Modifier.testTag("account_add_kind_${kind.name}"), label = { Text(stringResource(when (kind) {
                                AccountAddContentKind.NONE -> R.string.account_add_empty_label
                                AccountAddContentKind.TOTP -> R.string.account_add_code_label
                                AccountAddContentKind.RECOVERY -> R.string.recovery_codes_title
                            })) })
                    }
                }
                when (form.kind) {
                    AccountAddContentKind.NONE -> Unit
                    AccountAddContentKind.RECOVERY -> RecoveryCodeFields(form.recovery, onRecoveryTitleChange, onRecoveryValuesChange, enabled = !form.submitting, onEditName = { page = AccountEditorPage.RECOVERY_NAME })
                    AccountAddContentKind.TOTP -> TotpInputFields(form.totp,
                        onModeChange = { value -> onTotpChange { it.copy(mode = value) } }, onStartScan = onStartScan,
                        onUriChange = { value -> onTotpChange { it.copy(uri = value) } }, onSecretChange = { value -> onTotpChange { it.copy(secret = value) } },
                        onAlgorithmChange = { value -> onTotpChange { it.copy(algorithm = value) } }, onDigitsChange = { value -> onTotpChange { it.copy(digits = value) } },
                        onPeriodChange = { value -> onTotpChange { it.copy(periodSeconds = value) } }, enabled = !form.submitting, onAdvanced = { page = AccountEditorPage.ADVANCED })
                }
            }
        }
        form.error?.let { code -> Text(stringResource(when (code) {
            "provider_required" -> R.string.account_add_provider_error
            "account_required" -> R.string.account_add_name_error
            "duplicate_account" -> R.string.account_add_duplicate_error
            "target_unavailable" -> R.string.account_add_target_error
            "invalid_secret" -> R.string.account_add_secret_error
            "invalid_uri" -> R.string.account_add_uri_error
            "empty_codes" -> R.string.recovery_codes_empty_error
            "duplicate_codes" -> R.string.account_add_duplicate_codes_error
            else -> R.string.account_add_save_error
        }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("account_add_error")) }
        }
    }
}
