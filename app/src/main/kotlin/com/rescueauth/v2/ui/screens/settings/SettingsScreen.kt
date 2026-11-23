package com.rescueauth.v2.ui.screens.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MergeType
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.security.SensitiveAction
import com.rescueauth.v2.security.SensitiveActionAccess
import com.rescueauth.v2.security.SensitiveActionRequest
import com.rescueauth.v2.security.SensitiveActionResult
import com.rescueauth.v2.session.VaultSecurityStatus
import com.rescueauth.v2.ui.components.LocalRescueAuthSnackbarHostState
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.ui.components.RescueAuthChevron
import com.rescueauth.v2.ui.components.RescueAuthDivider
import com.rescueauth.v2.ui.components.RescueAuthIconBadge
import com.rescueauth.v2.ui.components.RescueAuthPageScaffold
import com.rescueauth.v2.ui.components.RescueAuthRowCard
import com.rescueauth.v2.ui.components.RescueAuthSectionHeader
import com.rescueauth.v2.ui.components.RescueAuthSheet
import com.rescueauth.v2.ui.components.StudioIconLabel
import com.rescueauth.v2.ui.components.StudioVaultHero
import com.rescueauth.v2.ui.components.showInstantSnackbar
import com.rescueauth.v2.ui.screens.startup.BackupPinManageSheet
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.Spacing
import com.rescueauth.v2.ui.theme.ThemeColor
import com.rescueauth.v2.ui.theme.ThemeMode
import com.rescueauth.v2.ui.theme.colorSchemeFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SettingsTestTags {
    const val ABOUT_ROW = "settings_about_row"
    const val TRANSFER_ROW = "settings_transfer_row"
    const val APPEARANCE_ROW = "settings_appearance_row"
    const val SYSTEM_UNLOCK_ROW = "settings_system_unlock_row"
    const val BACKUP_PIN_ROW = "settings_backup_pin_row"
}

/** Modern settings surface: transfer actions first, security & preferences below. */
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
    themeColor: ThemeColor = ThemeColor.DEFAULT,
    onThemeColorChange: ((ThemeColor) -> Unit)? = null,
    securityStatus: VaultSecurityStatus? = null,
    hasDeviceLock: Boolean = true,
    onSaveBackupPin: (suspend (String) -> Boolean)? = null,
    onRemoveBackupPin: (suspend () -> Boolean)? = null,
    onEnableSystemUnlock: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shellSnackbar = LocalRescueAuthSnackbarHostState.current

    var transferOpen by remember { mutableStateOf(false) }
    var appearanceOpen by remember { mutableStateOf(false) }
    var backupPinSheetOpen by remember { mutableStateOf(false) }
    var backupPinSaving by remember { mutableStateOf(false) }

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
    val colorLabels = ThemeColor.entries.associateWith { it.label() }
    val isDarkPreview = themeMode.isDark(isSystemInDarkTheme())

    val requestManageBackupPin: () -> Unit = {
        val gate = SensitiveActionAccess.gate
        if (gate != null) {
            val req = SensitiveActionRequest(SensitiveAction.MANAGE_BACKUP_PIN)
            gate.authorize(req) { result ->
                if (result is SensitiveActionResult.Success) {
                    gate.executePending(req) {
                        backupPinSheetOpen = true
                    }
                } else if (result is SensitiveActionResult.Unavailable) {
                    scope.launch {
                        shellSnackbar?.showInstantSnackbar(context.getString(R.string.auth_unavailable))
                    }
                }
            }
        } else {
            backupPinSheetOpen = true
        }
    }

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
                RescueAuthIconBadge(
                    Icons.Outlined.FileUpload,
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.65f),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_transfer_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        stringResource(R.string.settings_transfer_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                    )
                }
                RescueAuthChevron()
            }

            if (securityStatus != null) {
                RescueAuthCard(contentPadding = CardTokens.noPadding) {
                    val systemSubtitle = when {
                        securityStatus.hasSystemKeySlot ->
                            stringResource(R.string.settings_system_unlock_active)
                        !hasDeviceLock ->
                            stringResource(R.string.settings_system_unlock_missing_device)
                        securityStatus.isSystemKeyInvalidated ->
                            stringResource(R.string.settings_system_unlock_invalidated)
                        else ->
                            stringResource(R.string.settings_system_unlock_available)
                    }
                    val systemClick: (() -> Unit)? = when {
                        securityStatus.hasSystemKeySlot -> null
                        !hasDeviceLock -> ({
                            context.startActivity(
                                Intent(Settings.ACTION_SECURITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        })
                        else -> onEnableSystemUnlock
                    }
                    SettingsEntryCard(
                        icon = Icons.Outlined.Fingerprint,
                        title = stringResource(R.string.settings_system_unlock_title),
                        subtitle = systemSubtitle,
                        enabled = true,
                        onClick = systemClick,
                        modifier = Modifier.testTag(SettingsTestTags.SYSTEM_UNLOCK_ROW),
                    )
                    RescueAuthDivider(Modifier.padding(start = 64.dp))
                    SettingsEntryCard(
                        icon = Icons.Outlined.Pin,
                        title = stringResource(R.string.settings_backup_pin_title),
                        subtitle = stringResource(
                            if (securityStatus.hasBackupPin) {
                                R.string.settings_backup_pin_active
                            } else {
                                R.string.settings_backup_pin_recommended
                            },
                        ),
                        enabled = onSaveBackupPin != null,
                        onClick = if (onSaveBackupPin != null) requestManageBackupPin else null,
                        modifier = Modifier.testTag(SettingsTestTags.BACKUP_PIN_ROW),
                    )
                }
            }

            RescueAuthCard(contentPadding = CardTokens.noPadding) {
                SettingsEntryCard(
                    icon = themeMode.icon(),
                    title = appearanceTitle,
                    subtitle = modeLabels.getValue(themeMode),
                    enabled = onThemeModeChange != null,
                    onClick = onThemeModeChange?.let { { appearanceOpen = true } },
                    modifier = Modifier.testTag(SettingsTestTags.APPEARANCE_ROW),
                    trailingIndicator = if (onThemeColorChange != null) {
                        {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .border(1.dp, CardTokens.outlineColor(), CircleShape),
                            )
                        }
                    } else null,
                )
                RescueAuthDivider(Modifier.padding(start = 64.dp))
                SettingsEntryCard(
                    Icons.Outlined.Info,
                    stringResource(R.string.settings_about),
                    versionName,
                    onAboutClick != null,
                    onAboutClick,
                    Modifier.testTag(SettingsTestTags.ABOUT_ROW),
                )
            }
        }
    }

    if (appearanceOpen && onThemeModeChange != null) {
        RescueAuthSheet(appearanceTitle, themeMode.icon(), { appearanceOpen = false }) {
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
            if (onThemeColorChange != null) {
                RescueAuthSectionHeader(
                    title = stringResource(R.string.settings_appearance_color_heading),
                    modifier = Modifier.padding(top = Spacing.xs),
                )
                ThemeColor.entries.forEach { preset ->
                    val swatchScheme = colorSchemeFor(preset, darkTheme = isDarkPreview)
                    val isCurrent = preset == themeColor
                    RescueAuthRowCard(
                        onClick = { onThemeColorChange(preset) },
                        containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else CardTokens.containerColor(),
                        modifier = Modifier.testTag("theme_color_${preset.name}").semantics {
                            role = Role.RadioButton
                            selected = isCurrent
                        },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(swatchScheme.primary)
                                .border(1.dp, CardTokens.outlineColor(), CircleShape),
                        )
                        Text(colorLabels.getValue(preset), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        if (isCurrent) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    if (backupPinSheetOpen && onSaveBackupPin != null) {
        BackupPinManageSheet(
            hasExistingPin = securityStatus?.hasBackupPin == true,
            canRemovePin = securityStatus?.hasSystemKeySlot == true && onRemoveBackupPin != null,
            submitting = backupPinSaving,
            onSavePin = { newPin, dismissSheet ->
                if (!backupPinSaving) {
                    backupPinSaving = true
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) { onSaveBackupPin(newPin) }
                        backupPinSaving = false
                        if (ok) {
                            dismissSheet()
                            shellSnackbar?.showInstantSnackbar(context.getString(R.string.backup_pin_saved_message))
                        } else {
                            shellSnackbar?.showInstantSnackbar(context.getString(R.string.common_error_title))
                        }
                    }
                }
            },
            onRemovePin = { dismissSheet ->
                val removeFn = onRemoveBackupPin ?: return@BackupPinManageSheet
                if (!backupPinSaving) {
                    backupPinSaving = true
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) { removeFn() }
                        backupPinSaving = false
                        if (ok) {
                            dismissSheet()
                            shellSnackbar?.showInstantSnackbar(context.getString(R.string.backup_pin_removed_message))
                        } else {
                            shellSnackbar?.showInstantSnackbar(context.getString(R.string.common_error_title))
                        }
                    }
                }
            },
            onDismiss = { if (!backupPinSaving) backupPinSheetOpen = false },
        )
    }
}

private fun ThemeMode.icon() = when (this) {
    ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
    ThemeMode.LIGHT -> Icons.Outlined.LightMode
    ThemeMode.DARK -> Icons.Outlined.DarkMode
}

@Composable
private fun ThemeMode.label(): String = stringResource(
    when (this) {
        ThemeMode.SYSTEM -> R.string.theme_mode_system
        ThemeMode.LIGHT -> R.string.theme_mode_light
        ThemeMode.DARK -> R.string.theme_mode_dark
    },
)

@Composable
private fun ThemeColor.label(): String = stringResource(
    when (this) {
        ThemeColor.SHIYI_ORANGE -> R.string.theme_color_shiyi_orange
        ThemeColor.CYAN_BLUE -> R.string.theme_color_cyan_blue
        ThemeColor.JADE_GREEN -> R.string.theme_color_jade_green
        ThemeColor.INDIGO -> R.string.theme_color_indigo
        ThemeColor.VIOLET -> R.string.theme_color_violet
        ThemeColor.ROSE -> R.string.theme_color_rose
    },
)

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
                Icon(
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    StudioIconLabel(Icons.Outlined.TouchApp, stringResource(R.string.compact_manual_export))
                    StudioIconLabel(Icons.Outlined.Lock, stringResource(R.string.compact_package_pin))
                    StudioIconLabel(Icons.AutoMirrored.Outlined.MergeType, stringResource(R.string.compact_merge_import))
                }
            }
            RescueAuthCard(contentPadding = CardTokens.noPadding) {
                SettingsEntryCard(
                    icon = Icons.Outlined.FileUpload,
                    title = stringResource(R.string.settings_export_vault),
                    subtitle = stringResource(R.string.settings_export_vault_subtitle),
                    enabled = onExportClick != null,
                    onClick = onExportClick,
                )
                RescueAuthDivider(Modifier.padding(start = 64.dp))
                SettingsEntryCard(
                    icon = Icons.Outlined.FileDownload,
                    title = stringResource(R.string.settings_import_native),
                    subtitle = stringResource(R.string.settings_import_native_subtitle),
                    enabled = onImportClick != null,
                    onClick = onImportClick,
                )
                RescueAuthDivider(Modifier.padding(start = 64.dp))
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
    trailingIndicator: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = ScreenTokens.controlMinHeight)
            .padding(CardTokens.contentPadding),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
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
        trailingIndicator?.invoke()
        if (onClick != null && enabled) RescueAuthChevron()
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
