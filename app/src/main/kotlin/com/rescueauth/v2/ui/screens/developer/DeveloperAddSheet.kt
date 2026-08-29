package com.rescueauth.v2.ui.screens.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.components.RescueAuthIconBadge
import com.rescueauth.v2.ui.components.RescueAuthRowCard
import com.rescueauth.v2.ui.components.RescueAuthChevron
import com.rescueauth.v2.ui.developer.DeveloperFormType
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperAddSheet(
    onDismiss: () -> Unit,
    onSelectType: (DeveloperFormType) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                RescueAuthIconBadge(icon = Icons.Filled.Add, size = 40.dp, iconSize = 20.dp)
                Column {
                    Text(
                        text = stringResource(R.string.developer_add_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.developer_add_sheet_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            listOf(
                DeveloperFormType.API_CREDENTIAL to R.string.developer_type_api_credential,
                DeveloperFormType.SSH_KEY to R.string.developer_type_ssh_key,
                DeveloperFormType.GENERIC_SECRET to R.string.developer_type_generic,
                DeveloperFormType.ANDROID_SIGNING_KEY to R.string.developer_type_signing_key,
                DeveloperFormType.ENVIRONMENT_VARIABLE_SET to R.string.developer_type_env_var,
            ).forEach { (type, labelRes) ->
                AddTypeRow(
                    icon = type.icon(),
                    label = stringResource(labelRes),
                    onClick = { onSelectType(type) },
                )
            }
        }
    }
}

@Composable
private fun AddTypeRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    RescueAuthRowCard(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.surface,
        verticalPadding = Spacing.sm,
    ) {
        RescueAuthIconBadge(icon = icon, size = 40.dp, iconSize = 20.dp)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        RescueAuthChevron()
    }
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
    RescueAuthTheme {
        DeveloperAddSheet(onDismiss = {}, onSelectType = {})
    }
}
