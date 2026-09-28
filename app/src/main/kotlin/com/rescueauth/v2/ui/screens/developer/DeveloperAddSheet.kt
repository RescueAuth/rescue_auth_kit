package com.rescueauth.v2.ui.screens.developer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.components.AddSheetAction
import com.rescueauth.v2.ui.components.RescueAuthAddSheet
import com.rescueauth.v2.ui.developer.DeveloperFormType
import com.rescueauth.v2.ui.theme.RescueAuthTheme

@Composable
fun DeveloperAddSheet(
    onDismiss: () -> Unit,
    onSelectType: (DeveloperFormType) -> Unit,
) {
    RescueAuthAddSheet(
        title = stringResource(R.string.developer_add_title),
        subtitle = stringResource(R.string.developer_add_sheet_subtitle),
        modifier = Modifier.testTag("developer_add_sheet"),
        onDismiss = onDismiss,
        actions = listOf(
            DeveloperFormType.API_CREDENTIAL to R.string.developer_type_api_credential,
            DeveloperFormType.SSH_KEY to R.string.developer_type_ssh_key,
            DeveloperFormType.GENERIC_SECRET to R.string.developer_type_generic,
            DeveloperFormType.ANDROID_SIGNING_KEY to R.string.developer_type_signing_key,
            DeveloperFormType.ENVIRONMENT_VARIABLE_SET to R.string.developer_type_env_var,
        ).map { (type, label) ->
            AddSheetAction("add_type_${type.name}", type.icon(), stringResource(label)) { onSelectType(type) }
        },
    )
}

private fun DeveloperFormType.icon(): ImageVector = when (this) {
    DeveloperFormType.API_CREDENTIAL -> Icons.Filled.Key
    DeveloperFormType.SSH_KEY -> Icons.Filled.Lock
    DeveloperFormType.GENERIC_SECRET -> Icons.Filled.Key
    DeveloperFormType.ANDROID_SIGNING_KEY -> Icons.Filled.VpnKey
    DeveloperFormType.ENVIRONMENT_VARIABLE_SET -> Icons.Filled.Build
}

@Preview(showBackground = true)
@Composable
private fun DeveloperAddSheetPreview() {
    RescueAuthTheme { DeveloperAddSheet(onDismiss = {}, onSelectType = {}) }
}
