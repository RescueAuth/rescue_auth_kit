package com.rescueauth.v2.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.automirrored.outlined.MergeType
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.rescueauth.v2.ui.components.RescueAuthPageScaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.ui.components.RescueAuthChevron
import com.rescueauth.v2.ui.components.RescueAuthIconBadge
import com.rescueauth.v2.ui.components.RescueAuthSectionHeader
import com.rescueauth.v2.ui.components.*
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.ThemeMode
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.Spacing

object SettingsTestTags {
    const val ABOUT_ROW = "settings_about_row"
    const val TRANSFER_ROW = "settings_transfer_row"
    const val APPEARANCE_ROW = "settings_appearance_row"
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
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: ((ThemeMode) -> Unit)? = null,
) {
    var transferOpen by remember { mutableStateOf(false) }
    var appearanceOpen by remember { mutableStateOf(false) }
    if (transferOpen) {
        SettingsTransferScreen(
            onBack = { transferOpen = false },
            onExportClick = onExportClick,
            onImportClick = onImportClick,
            onLegacyImportClick = onLegacyImportClick,
        )
        return
    }
    // Resolve labels in the screen's locale before the sheet creates its own window context.
    val appearanceTitle = stringResource(R.string.settings_appearance)
    val modeLabels = ThemeMode.entries.associateWith { it.label() }
    RescueAuthPageScaffold(
        modifier = modifier.fillMaxSize(),
        title = stringResource(R.string.settings_title),
        subtitle = null,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenTokens.horizontalPadding, vertical = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(CardTokens.actionSpacing),
        ) {
            StudioVaultHero()
            RescueAuthRowCard(
                onClick = onTransferClick ?: { transferOpen = true },
                modifier = Modifier.testTag(SettingsTestTags.TRANSFER_ROW),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                horizontalPadding = CardTokens.heroPadding,
                verticalPadding = CardTokens.heroPadding,
            ) {
                RescueAuthIconBadge(Icons.Outlined.FileUpload,
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.65f))
                Text(stringResource(R.string.settings_transfer_title), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onPrimaryContainer)
                RescueAuthChevron()
            }
            RescueAuthCard(contentPadding = CardTokens.noPadding) {
                SettingsEntryCard(themeMode.icon(), appearanceTitle,
                    modeLabels.getValue(themeMode), onThemeModeChange != null,
                    onThemeModeChange?.let { { appearanceOpen = true } },
                    Modifier.testTag(SettingsTestTags.APPEARANCE_ROW))
                RescueAuthDivider(Modifier.padding(start = 64.dp))
                SettingsEntryCard(Icons.Outlined.Info, stringResource(R.string.settings_about),
                    versionName, onAboutClick != null, onAboutClick,
                    Modifier.testTag(SettingsTestTags.ABOUT_ROW))
            }
        }
    }
    if (appearanceOpen && onThemeModeChange != null) {
        ModalBottomSheet(onDismissRequest = { appearanceOpen = false }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenTokens.horizontalPadding, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(CardTokens.actionSpacing)) {
                Text(appearanceTitle, style = MaterialTheme.typography.titleLarge)
                ThemeMode.entries.forEach { mode ->
                    RescueAuthRowCard(
                        onClick = { onThemeModeChange(mode); appearanceOpen = false },
                        containerColor = if (mode == themeMode) MaterialTheme.colorScheme.primaryContainer else CardTokens.containerColor(),
                        modifier = Modifier.testTag("theme_mode_${mode.name}").semantics {
                            role = Role.RadioButton
                            selected = mode == themeMode
                        },
                    ) {
                        RescueAuthIconBadge(mode.icon())
                        Text(modeLabels.getValue(mode), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        if (mode == themeMode) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

private fun ThemeMode.icon() = when (this) {
    ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
    ThemeMode.LIGHT -> Icons.Outlined.LightMode
    ThemeMode.DARK -> Icons.Outlined.DarkMode
}

@Composable
private fun ThemeMode.label(): String = stringResource(when (this) {
    ThemeMode.SYSTEM -> R.string.theme_mode_system
    ThemeMode.LIGHT -> R.string.theme_mode_light
    ThemeMode.DARK -> R.string.theme_mode_dark
})

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsTransferScreen(
    onBack: () -> Unit,
    onExportClick: (() -> Unit)?,
    onImportClick: (() -> Unit)?,
    onLegacyImportClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    RescueAuthPageScaffold(
        modifier = modifier.fillMaxSize(),
        title = stringResource(R.string.settings_transfer_title),
        navigationIcon = {
            androidx.compose.material3.IconButton(onClick = onBack) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.a11y_back),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenTokens.horizontalPadding, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(CardTokens.actionSpacing),
        ) {
            RescueAuthCard {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    StudioIconLabel(Icons.Outlined.TouchApp, stringResource(R.string.compact_manual_export))
                    StudioIconLabel(Icons.Outlined.Lock, stringResource(R.string.compact_package_pin))
                    StudioIconLabel(Icons.AutoMirrored.Outlined.MergeType, stringResource(R.string.compact_merge_import))
                }
            }
            RescueAuthCard(contentPadding = CardTokens.noPadding) {
                SettingsEntryCard(
                    icon = Icons.Outlined.FileUpload,
                    title = stringResource(R.string.settings_export_vault),
                    subtitle = ".rakpkg",
                    enabled = onExportClick != null,
                    onClick = onExportClick,
                )
                com.rescueauth.v2.ui.components.RescueAuthDivider(Modifier.padding(start = 64.dp))
                SettingsEntryCard(
                    icon = Icons.Outlined.FileDownload,
                    title = stringResource(R.string.settings_import_native),
                    subtitle = ".rakpkg",
                    enabled = onImportClick != null,
                    onClick = onImportClick,
                )
                com.rescueauth.v2.ui.components.RescueAuthDivider(Modifier.padding(start = 64.dp))
                SettingsEntryCard(
                    icon = Icons.Outlined.History,
                    title = stringResource(R.string.settings_import_legacy),
                    subtitle = ".rakvault",
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
