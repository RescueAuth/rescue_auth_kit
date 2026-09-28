package com.rescueauth.v2.ui.screens.authenticator

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.components.AddSheetAction
import com.rescueauth.v2.ui.components.RescueAuthAddSheet

@Composable
fun AuthenticatorAddSheet(
    onDismiss: () -> Unit,
    onAddCredential: (() -> Unit)?,
    onAddProvider: (() -> Unit)? = null,
    onAddAccount: (() -> Unit)? = null,
    onAddRecovery: (() -> Unit)? = null,
    contextName: String? = null,
) {
    RescueAuthAddSheet(
        title = stringResource(R.string.authenticator_add_sheet_title),
        modifier = Modifier.testTag("authenticator_add_sheet"),
        onDismiss = onDismiss,
        subtitle = contextName,
        actions = buildList {
            if (onAddAccount != null) add(AddSheetAction("add_account", Icons.Outlined.PersonAdd,
                stringResource(R.string.provider_add_account), onAddAccount))
            if (onAddCredential != null) add(AddSheetAction("add_authenticator", Icons.Outlined.Timer,
                stringResource(R.string.studio_add_credential), onAddCredential))
            if (onAddRecovery != null) add(AddSheetAction("add_recovery", Icons.Outlined.Shield,
                stringResource(R.string.recovery_codes_add_title), onAddRecovery))
            if (onAddProvider != null) add(AddSheetAction("add_provider", Icons.Outlined.GridView,
                stringResource(R.string.studio_new_service), onAddProvider))
        },
    )
}
