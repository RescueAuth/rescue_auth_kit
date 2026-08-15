package com.rescueauth.v2.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.components.BreadcrumbItem
import com.rescueauth.v2.ui.components.BreadcrumbTopBar
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing
import com.rescueauth.v2.ui.theme.ThemeColor

object SettingsTestTags {
    const val ABOUT_ROW = "settings_about_row"
}

/**
 * Settings top-level screen, restyled around the compact Card + icon-badge
 * language used by Rescue Auth v1.
 *
 * Product behaviour is unchanged — each setting is a tappable card with an icon
 * badge, title, optional subtitle and a trailing chevron. Appearance hosts the
 * theme-color picker inside a card; About shows the version info card and the
 * About entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    versionName: String? = null,
    themeColor: ThemeColor = ThemeColor.DEFAULT,
    onThemeColorSelected: ((ThemeColor) -> Unit)? = null,
    onExportClick: (() -> Unit)? = null,
    onImportClick: (() -> Unit)? = null,
    onLegacyImportClick: (() -> Unit)? = null,
    onAboutClick: (() -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    BreadcrumbTopBar(
                        items = listOf(
                            BreadcrumbItem(
                                label = stringResource(R.string.settings_title),
                                isCurrent = true,
                            ),
                        ),
                        onNavigate = {},
                        ellipsisContentDescription = stringResource(R.string.breadcrumb_ellipsis),
                        moreMenuContentDescription = stringResource(R.string.breadcrumb_more_ancestors),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        ) {
            SettingsSectionHeader(
                title = stringResource(R.string.settings_backup_transfer_section),
                subtitle = stringResource(R.string.settings_backup_transfer_notice),
            )
            SettingsEntryCard(
                icon = Icons.Filled.FileUpload,
                title = stringResource(R.string.settings_export_vault),
                subtitle = stringResource(R.string.settings_export_vault_subtitle),
                enabled = onExportClick != null,
                onClick = onExportClick,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            SettingsEntryCard(
                icon = Icons.Filled.FileDownload,
                title = stringResource(R.string.settings_import_native),
                subtitle = stringResource(R.string.settings_import_native_subtitle),
                enabled = onImportClick != null,
                onClick = onImportClick,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            SettingsEntryCard(
                icon = Icons.Filled.History,
                title = stringResource(R.string.settings_import_legacy),
                subtitle = stringResource(R.string.settings_import_legacy_subtitle),
                enabled = onLegacyImportClick != null,
                onClick = onLegacyImportClick,
            )

            SettingsSectionHeader(
                title = stringResource(R.string.settings_appearance_section),
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    SettingsIconBadge(Icons.Filled.Palette)
                    ThemeColorPreference(
                        selected = themeColor,
                        onSelect = { onThemeColorSelected?.invoke(it) },
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingsSectionHeader(
                title = stringResource(R.string.settings_about_section),
            )
            SettingsInfoCard(
                icon = Icons.Filled.Verified,
                title = stringResource(
                    R.string.settings_version,
                    versionName ?: stringResource(R.string.common_unknown),
                ),
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            SettingsEntryCard(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.settings_about),
                subtitle = stringResource(R.string.settings_about_subtitle),
                enabled = onAboutClick != null,
                onClick = onAboutClick,
                modifier = Modifier.testTag(SettingsTestTags.ABOUT_ROW),
            )
            Spacer(modifier = Modifier.height(Spacing.md))
        }
    }
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    subtitle: String? = null,
) {
    Column(
        modifier = Modifier.padding(
            start = Spacing.xxs,
            top = Spacing.sm,
            end = Spacing.xxs,
            bottom = Spacing.xs,
        ),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
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

@Composable
private fun SettingsEntryCard(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (onClick != null) {
        Card(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.fillMaxWidth(),
        ) {
            SettingsEntryContent(
                icon = icon,
                title = title,
                subtitle = subtitle,
                showChevron = true,
            )
        }
    } else {
        Card(modifier = modifier.fillMaxWidth()) {
            SettingsEntryContent(
                icon = icon,
                title = title,
                subtitle = subtitle,
                showChevron = false,
            )
        }
    }
}

@Composable
private fun SettingsInfoCard(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        SettingsEntryContent(
            icon = icon,
            title = title,
            subtitle = null,
            showChevron = false,
        )
    }
}

@Composable
private fun SettingsEntryContent(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    showChevron: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        SettingsIconBadge(icon)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
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
        if (showChevron) {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsIconBadge(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(22.dp),
        )
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
