package com.rescueauth.v2.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.rescueauth.v2.ui.components.RescueAuthRowCard
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.Spacing

object SettingsTestTags {
    const val ABOUT_ROW = "settings_about_row"
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
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            RescueAuthPageHeader(
                title = stringResource(R.string.settings_title),
                subtitle = stringResource(R.string.settings_subtitle),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // Keep the high-frequency transfer actions near the first viewport.
            RescueAuthSectionHeader(
                title = stringResource(R.string.settings_backup_transfer_section),
                modifier = Modifier.padding(
                    start = ScreenTokens.horizontalPadding,
                    end = ScreenTokens.horizontalPadding,
                    top = Spacing.md,
                ),
            )
            SettingsEntryCard(
                icon = Icons.Filled.FileUpload,
                title = stringResource(R.string.settings_export_vault),
                subtitle = stringResource(R.string.settings_export_vault_subtitle),
                enabled = onExportClick != null,
                onClick = onExportClick,
                modifier = Modifier.padding(horizontal = ScreenTokens.horizontalPadding),
            )
            SettingsEntryCard(
                icon = Icons.Filled.FileDownload,
                title = stringResource(R.string.settings_import_native),
                subtitle = stringResource(R.string.settings_import_native_subtitle),
                enabled = onImportClick != null,
                onClick = onImportClick,
                modifier = Modifier.padding(horizontal = ScreenTokens.horizontalPadding),
            )
            SettingsEntryCard(
                icon = Icons.Filled.History,
                title = stringResource(R.string.settings_import_legacy),
                subtitle = stringResource(R.string.settings_import_legacy_subtitle),
                enabled = onLegacyImportClick != null,
                onClick = onLegacyImportClick,
                modifier = Modifier.padding(horizontal = ScreenTokens.horizontalPadding),
            )
            Text(
                text = stringResource(R.string.settings_backup_transfer_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = ScreenTokens.horizontalPadding),
            )

            RescueAuthSectionHeader(
                title = stringResource(R.string.settings_about_section),
                modifier = Modifier.padding(
                    start = ScreenTokens.horizontalPadding,
                    end = ScreenTokens.horizontalPadding,
                    top = Spacing.lg,
                ),
            )
            SettingsInfoCard(
                icon = Icons.Filled.Verified,
                title = stringResource(
                    R.string.settings_version,
                    versionName ?: stringResource(R.string.common_unknown),
                ),
                modifier = Modifier.padding(horizontal = ScreenTokens.horizontalPadding),
            )
            SettingsEntryCard(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.settings_about),
                subtitle = stringResource(R.string.settings_about_subtitle),
                enabled = onAboutClick != null,
                onClick = onAboutClick,
                modifier = Modifier
                    .padding(horizontal = ScreenTokens.horizontalPadding)
                    .testTag(SettingsTestTags.ABOUT_ROW),
            )
            Text(
                text = stringResource(R.string.settings_security_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = ScreenTokens.horizontalPadding,
                    vertical = Spacing.md,
                ),
            )
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
    RescueAuthRowCard(
        modifier = modifier,
        onClick = onClick,
        containerColor = CardTokens.elevatedContainerColor(),
        verticalPadding = Spacing.xs,
    ) {
        RescueAuthIconBadge(icon = icon, size = 36.dp, iconSize = 18.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
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

@Composable
private fun SettingsInfoCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    modifier: Modifier = Modifier,
) {
    RescueAuthCard(
        modifier = modifier,
        containerColor = CardTokens.containerColor(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            RescueAuthIconBadge(icon = icon)
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
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
