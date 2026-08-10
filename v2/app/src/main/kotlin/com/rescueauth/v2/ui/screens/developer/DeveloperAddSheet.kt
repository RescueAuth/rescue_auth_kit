package com.rescueauth.v2.ui.screens.developer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.developer.DeveloperFormType
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing

/**
 * "Add" sheet for the Developer Vault (Phase 4 P4).
 *
 * Offers all five implemented types (API Credential / SSH Key / Generic
 * Secret / Android Signing Key / Environment Variable Set). Phase 4 P6 makes
 * the final two types clickable (Issue #20 P6).
 */
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
            Text(
                text = stringResource(R.string.developer_add),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            AddTypeRow(
                label = stringResource(R.string.developer_type_api_credential),
                onClick = { onSelectType(DeveloperFormType.API_CREDENTIAL) },
            )
            AddTypeRow(
                label = stringResource(R.string.developer_type_ssh_key),
                onClick = { onSelectType(DeveloperFormType.SSH_KEY) },
            )
            AddTypeRow(
                label = stringResource(R.string.developer_type_generic),
                onClick = { onSelectType(DeveloperFormType.GENERIC_SECRET) },
            )
            AddTypeRow(
                label = stringResource(R.string.developer_type_signing_key),
                onClick = { onSelectType(DeveloperFormType.ANDROID_SIGNING_KEY) },
            )
            AddTypeRow(
                label = stringResource(R.string.developer_type_env_var),
                onClick = { onSelectType(DeveloperFormType.ENVIRONMENT_VARIABLE_SET) },
            )
        }
    }
}

@Composable
private fun AddTypeRow(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.md, horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DeveloperAddSheetPreview() {
    RescueAuthTheme {
        DeveloperAddSheet(onDismiss = {}, onSelectType = {})
    }
}
