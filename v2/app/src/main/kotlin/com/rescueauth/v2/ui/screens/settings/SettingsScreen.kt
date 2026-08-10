package com.rescueauth.v2.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing

object SettingsTestTags {
    const val ABOUT_ROW = "settings_about_row"
}

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
    onLegacyImportClick: (() -> Unit)? = null,
    onAboutClick: (() -> Unit)? = null,
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
                .padding(horizontal = Spacing.md)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            SettingsSectionLabel(stringResource(R.string.settings_backup_transfer_section))
            SettingsRow(
                title = stringResource(R.string.settings_export_vault),
                subtitle = stringResource(R.string.settings_export_vault_subtitle),
                enabled = onExportClick != null,
                onClick = onExportClick,
            )
            SettingsRow(
                title = stringResource(R.string.settings_import_native),
                subtitle = stringResource(R.string.settings_import_native_subtitle),
                enabled = onImportClick != null,
                onClick = onImportClick,
            )
            SettingsRow(
                title = stringResource(R.string.settings_import_legacy),
                subtitle = stringResource(R.string.settings_import_legacy_subtitle),
                enabled = onLegacyImportClick != null,
                onClick = onLegacyImportClick,
            )
            Text(
                text = stringResource(R.string.settings_backup_transfer_notice),
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
            SettingsRow(
                title = stringResource(R.string.settings_about),
                subtitle = stringResource(R.string.settings_about_subtitle),
                enabled = onAboutClick != null,
                onClick = onAboutClick,
                modifier = Modifier.testTag(SettingsTestTags.ABOUT_ROW),
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
    subtitle: String? = null,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = { onClick?.invoke() })
            .padding(vertical = Spacing.sm),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(Spacing.xxs))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    RescueAuthTheme {
        SettingsScreen(versionName = "1.0.0")
    }
}
