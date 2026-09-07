package com.rescueauth.v2.ui.screens.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import com.rescueauth.v2.ui.components.RescueAuthButton as Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.rescueauth.v2.ui.components.RescueAuthOutlinedButton as OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.ui.components.RescueAuthIconBadge
import com.rescueauth.v2.ui.components.RescueAuthPageHeader
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.Spacing
import com.rescueauth.v2.update.Severity
import com.rescueauth.v2.update.UpdateUiState

object AboutTestTags {
    const val SCREEN = "screen_about"
    const val CHECK_BUTTON = "about_check_button"
    const val OPEN_RELEASE_PAGE = "about_open_release_page"
}

/** Version and verified update information, presented as a quiet utility page. */
@Composable
fun AboutScreen(
    versionName: String,
    versionCode: Long,
    state: UpdateUiState,
    onCheckForUpdates: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit = {},
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag(AboutTestTags.SCREEN),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            RescueAuthPageHeader(
                title = stringResource(R.string.about_title),
                subtitle = stringResource(R.string.about_subtitle),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
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
                .padding(
                    horizontal = ScreenTokens.horizontalPadding,
                    vertical = Spacing.md,
                ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            RescueAuthCard(
                containerColor = com.rescueauth.v2.ui.theme.CardTokens.containerColor(),
                contentPadding = Spacing.md,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    RescueAuthIconBadge(
                        icon = Icons.Filled.Info,
                        size = 42.dp,
                        iconSize = 22.dp,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.about_product_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                }
            }

            RescueAuthCard(containerColor = CardTokens.elevatedContainerColor()) {
                VersionRow(
                    label = stringResource(R.string.settings_version_label),
                    value = stringResource(R.string.about_version_format, versionName, versionCode),
                )
            }

            Button(
                onClick = onCheckForUpdates,
                enabled = state !is UpdateUiState.Checking,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AboutTestTags.CHECK_BUTTON),
            ) {
                if (state is UpdateUiState.Checking) {
                    CircularProgressIndicator(
                        progress = { 0.64f },
                        modifier = Modifier
                            .padding(end = Spacing.sm)
                            .size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                }
                Text(stringResource(R.string.about_check_for_updates))
            }

            RescueAuthCard(containerColor = CardTokens.containerColor()) {
                UpdateStatusBody(
                    state = state,
                    onOpenReleasePage = onOpenReleasePage,
                )
            }
        }
    }
}

@Composable
private fun VersionRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun UpdateStatusBody(
    state: UpdateUiState,
    onOpenReleasePage: () -> Unit,
) {
    when (state) {
        UpdateUiState.Idle -> {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.about_update_idle), style = MaterialTheme.typography.bodyMedium)
            }
        }
        UpdateUiState.Checking -> {
            Text(stringResource(R.string.about_checking), style = MaterialTheme.typography.bodyMedium)
        }
        is UpdateUiState.UpToDate -> {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.about_up_to_date, state.current.versionName), style = MaterialTheme.typography.bodyMedium)
            }
        }
        is UpdateUiState.UpdateAvailable -> {
            val security = state.severity == Severity.SECURITY
            Text(
                text = stringResource(if (security) R.string.about_security_update_available else R.string.about_update_available),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (security) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            if (state.minSupportedExceeded) {
                Text(
                    text = stringResource(R.string.about_unsupported_current_version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = stringResource(R.string.about_latest_version, state.latest.versionName, state.latest.versionCode),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.about_published_at, state.latest.publishedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onOpenReleasePage,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AboutTestTags.OPEN_RELEASE_PAGE),
            ) {
                Text(stringResource(R.string.about_open_release_page))
            }
        }
        is UpdateUiState.Error -> {
            Text(
                text = errorMessage(state.type),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
            )
            if (state.type == UpdateUiState.ErrorType.NETWORK || state.type == UpdateUiState.ErrorType.TIMEOUT) {
                Text(
                    text = stringResource(R.string.about_offline_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun errorMessage(type: UpdateUiState.ErrorType): String = when (type) {
    UpdateUiState.ErrorType.NETWORK -> stringResource(R.string.about_error_network)
    UpdateUiState.ErrorType.TIMEOUT -> stringResource(R.string.about_error_timeout)
    UpdateUiState.ErrorType.INVALID_SIGNATURE -> stringResource(R.string.about_error_invalid_signature)
    UpdateUiState.ErrorType.INVALID_MANIFEST -> stringResource(R.string.about_error_invalid_manifest)
    UpdateUiState.ErrorType.UNSUPPORTED_SCHEMA -> stringResource(R.string.about_error_unsupported_schema)
    UpdateUiState.ErrorType.NOT_CONFIGURED -> stringResource(R.string.about_error_not_configured)
}

@Preview(showBackground = true)
@Composable
private fun AboutScreenPreview() {
    RescueAuthTheme {
        AboutScreen(
            versionName = "1.0.0",
            versionCode = 10000,
            state = UpdateUiState.Idle,
            onCheckForUpdates = {},
            onOpenReleasePage = {},
            onBack = {},
        )
    }
}
