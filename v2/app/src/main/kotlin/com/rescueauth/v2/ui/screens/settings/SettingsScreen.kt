package com.rescueauth.v2.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing

/**
 * Settings top-level screen.
 *
 * Security section hosts placeholders for Export/Import Package (later
 * vertical slices, Phase 3D). The Developer Vault is **not** an entry here —
 * it is a first-class top-level destination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    versionName: String? = null,
    onExportClick: (() -> Unit)? = null,
    onImportClick: (() -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.settings_title)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            SettingsSectionLabel(stringResource(R.string.settings_security_section))
            SettingsRow(
                title = stringResource(R.string.settings_export_package),
                enabled = onExportClick != null,
                onClick = onExportClick,
            )
            SettingsRow(
                title = stringResource(R.string.settings_import_package),
                enabled = onImportClick != null,
                onClick = onImportClick,
            )
            Text(
                text = stringResource(R.string.settings_export_import_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.sm),
            )
            HorizontalDivider()
            SettingsSectionLabel(stringResource(R.string.settings_about_section))
            SettingsRow(
                title = stringResource(
                    R.string.settings_version,
                    versionName ?: stringResource(R.string.common_unknown),
                ),
                enabled = false,
                onClick = null,
            )
        }
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xxs),
    )
}

@Composable
private fun SettingsRow(
    title: String,
    enabled: Boolean,
    onClick: (() -> Unit)?,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        color = contentColor,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
    )
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    RescueAuthTheme {
        SettingsScreen(versionName = "1.0.0")
    }
}
