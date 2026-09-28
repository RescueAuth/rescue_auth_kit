package com.rescueauth.v2.ui.screens.authenticator

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.components.AddSheetAction
import com.rescueauth.v2.ui.components.RescueAuthAddSheet
import com.rescueauth.v2.ui.model.AccountUi

/** Only safe account labels are presented; selection is scoped to the current provider. */
@Composable
internal fun AccountAddTargetSheet(
    providerName: String,
    accounts: List<AccountUi>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onCreateAccount: (() -> Unit)? = null,
) {
    RescueAuthAddSheet(
        title = stringResource(R.string.add_select_account), subtitle = providerName,
        modifier = Modifier.testTag("add_target_sheet"), onDismiss = onDismiss,
        actions = buildList {
            accounts.forEach { account ->
                add(AddSheetAction("add_target_${account.id}", Icons.Outlined.Person, account.accountName) { onSelect(account.id) })
            }
            if (onCreateAccount != null) add(AddSheetAction("add_target_create_account", Icons.Outlined.PersonAdd,
                stringResource(R.string.provider_add_account), onCreateAccount))
        },
    )
}
