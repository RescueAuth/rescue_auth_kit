package com.rescueauth.v2.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.ui.components.RescueAuthChevron
import com.rescueauth.v2.ui.components.RescueAuthIconBadge
import com.rescueauth.v2.ui.components.RescueAuthPageHeader
import com.rescueauth.v2.ui.components.RescueAuthSectionHeader
import com.rescueauth.v2.ui.components.*
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.Spacing

object SettingsTestTags {
    const val ABOUT_ROW = "settings_about_row"
    const val TRANSFER_ROW = "settings_transfer_row"
}

/** Modern settings surface: transfer actions first, preferences below. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    versionName: String? = null,
    onExportClick: (() -> Unit)? = null,
    onImportClick: (() -> Unit)? = null,
    onLegacyImportClick: (() -> Unit)? = null,
    onAboutClick: (() -> Unit)? = null,
    onTransferClick: (() -> Unit)? = null,
) {
    var transferOpen by remember { mutableStateOf(false) }
    if (transferOpen) {
        SettingsTransferScreen(
            onBack = { transferOpen = false },
            onExportClick = onExportClick,
            onImportClick = onImportClick,
            onLegacyImportClick = onLegacyImportClick,
        )
        return
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            RescueAuthPageHeader(
                title = stringResource(R.string.settings_title),
                subtitle = null,
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenTokens.horizontalPadding, vertical = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(CardTokens.actionSpacing),
        ) {
            StudioEntrance {
                StudioVaultHero(
                    stringResource(R.string.studio_settings_label),
                    stringResource(R.string.studio_settings_title),
                    stringResource(R.string.studio_settings_body),
                )
            }
            RescueAuthSectionHeader(stringResource(R.string.settings_backup_transfer_section), modifier = Modifier.padding(top = Spacing.sm))
            RescueAuthCard(
                onClick = onTransferClick ?: { transferOpen = true },
                modifier = Modifier.testTag(SettingsTestTags.TRANSFER_ROW),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentPadding = CardTokens.heroPadding,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(Icons.Outlined.FileUpload, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.weight(1f))
                    RescueAuthChevron()
                }
                Text(stringResource(R.string.settings_transfer_title), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = Spacing.lg), color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(stringResource(R.string.studio_transfer_description), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(top = Spacing.xxs))
            }
            RescueAuthCard(contentPadding = CardTokens.noPadding) {
                SettingsEntryCard(Icons.Outlined.Info, stringResource(R.string.settings_about),
                    stringResource(R.string.studio_about_description), onAboutClick != null, onAboutClick,
                    Modifier.testTag(SettingsTestTags.ABOUT_ROW))
            }
            RescueAuthRowCard(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_security_body), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = Spacing.lg), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.startup_brand_name), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_version, versionName ?: stringResource(R.string.common_unknown)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SettingsTransferScreen(
    onBack: () -> Unit,
    onExportClick: (() -> Unit)?,
    onImportClick: (() -> Unit)?,
    onLegacyImportClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            RescueAuthPageHeader(
                title = stringResource(R.string.settings_transfer_title),
                subtitle = stringResource(R.string.settings_transfer_subtitle),
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.a11y_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenTokens.horizontalPadding, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            RescueAuthSectionHeader(
                title = stringResource(R.string.settings_backup_transfer_section),
                subtitle = stringResource(R.string.settings_backup_transfer_notice),
            )
            RescueAuthCard(contentPadding = 0.dp) {
                SettingsEntryCard(
                    icon = Icons.Outlined.FileUpload,
                    title = stringResource(R.string.settings_export_vault),
                    subtitle = stringResource(R.string.settings_export_vault_subtitle),
                    enabled = onExportClick != null,
                    onClick = onExportClick,
                )
                com.rescueauth.v2.ui.components.RescueAuthDivider(Modifier.padding(start = 64.dp))
                SettingsEntryCard(
                    icon = Icons.Outlined.FileDownload,
                    title = stringResource(R.string.settings_import_native),
                    subtitle = stringResource(R.string.settings_import_native_subtitle),
                    enabled = onImportClick != null,
                    onClick = onImportClick,
                )
                com.rescueauth.v2.ui.components.RescueAuthDivider(Modifier.padding(start = 64.dp))
                SettingsEntryCard(
                    icon = Icons.Outlined.History,
                    title = stringResource(R.string.settings_import_legacy),
                    subtitle = stringResource(R.string.settings_import_legacy_subtitle),
                    enabled = onLegacyImportClick != null,
                    onClick = onLegacyImportClick,
                )
            }
        }
    }
}

@Composable
private fun SettingsEntryCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = ScreenTokens.controlMinHeight)
            .padding(CardTokens.contentPadding),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        RescueAuthIconBadge(icon = icon, size = 32.dp, iconSize = 18.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = if (enabled || onClick == null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onClick != null) RescueAuthChevron()
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    RescueAuthTheme {
        SettingsScreen(
            versionName = "1.0.0",
            onExportClick = {},
            onImportClick = {},
            onLegacyImportClick = {},
            onAboutClick = {},
        )
    }
}
