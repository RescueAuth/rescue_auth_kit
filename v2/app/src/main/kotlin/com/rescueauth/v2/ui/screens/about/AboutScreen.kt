package com.rescueauth.v2.ui.screens.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing
import com.rescueauth.v2.update.Severity
import com.rescueauth.v2.update.UpdateUiState

object AboutTestTags {
    const val SCREEN = "screen_about"
    const val CHECK_BUTTON = "about_check_button"
    const val OPEN_RELEASE_PAGE = "about_open_release_page"
}

/**
 * About screen (Issue #20 Phase 6 L2).
 *
 * Shows the runtime app version (from build metadata), a short product
 * description, a manual "Check for Updates" action, the update status/result,
 * latest-version info, release notes summary (when supported) and an external
 * "Open Release Page" action (only for a verified update).
 *
 * ## Version source
 *
 * Version is always read from the current build/package metadata (BuildConfig)
 * and injected into [AboutViewModel]; it is never hard-coded in strings.xml and
 * never a fixed "1.0.0" (Issue #20 §1). Tests inject a fake version provider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    versionName: String,
    versionCode: Long,
    state: UpdateUiState,
    onCheckForUpdates: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
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
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.about_product_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            AboutInfoRow(
                label = stringResource(R.string.settings_version_label),
                value = stringResource(R.string.about_version_format, versionName, versionCode),
            )

            Spacer(modifier = Modifier.height(Spacing.sm))
            Button(
                onClick = onCheckForUpdates,
                enabled = state !is UpdateUiState.Checking,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AboutTestTags.CHECK_BUTTON),
            ) {
                Text(stringResource(R.string.about_check_for_updates))
            }

            Spacer(modifier = Modifier.height(Spacing.xs))
            UpdateStatusBody(
                state = state,
                onOpenReleasePage = onOpenReleasePage,
            )
        }
    }
}

@Composable
private fun AboutInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun UpdateStatusBody(
    state: UpdateUiState,
    onOpenReleasePage: () -> Unit,
) {
    when (state) {
        UpdateUiState.Idle -> {
            Text(
                text = stringResource(R.string.about_update_idle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        UpdateUiState.Checking -> {
            Text(
                text = stringResource(R.string.about_checking),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        is UpdateUiState.UpToDate -> {
            Text(
                text = stringResource(R.string.about_up_to_date, state.current.versionName),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        is UpdateUiState.UpdateAvailable -> {
            val security = state.severity == Severity.SECURITY
            if (security) {
                Text(
                    text = stringResource(R.string.about_security_update_available),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    text = stringResource(R.string.about_update_available),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (state.minSupportedExceeded) {
                Text(
                    text = stringResource(R.string.about_unsupported_current_version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = stringResource(
                    R.string.about_latest_version,
                    state.latest.versionName,
                    state.latest.versionCode,
                ),
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
            if (state.type == UpdateUiState.ErrorType.NETWORK ||
                state.type == UpdateUiState.ErrorType.TIMEOUT
            ) {
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
