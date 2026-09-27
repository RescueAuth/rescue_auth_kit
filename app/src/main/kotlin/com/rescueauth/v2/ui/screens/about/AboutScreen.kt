package com.rescueauth.v2.ui.screens.about

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.rescueauth.v2.ui.components.RescueAuthPageScaffold
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
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.components.RescueAuthButton as Button
import com.rescueauth.v2.ui.components.RescueAuthOutlinedButton as OutlinedButton
import com.rescueauth.v2.ui.components.RescueAuthBackButton
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.ui.components.StudioBrandArtwork
import com.rescueauth.v2.ui.components.RescueAuthMetaPill
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.Spacing
import com.rescueauth.v2.update.Severity
import com.rescueauth.v2.update.UpdateUiState

object AboutTestTags {
    const val SCREEN = "screen_about"
    const val CONTENT = "about_scroll_content"
    const val FOOTER = "about_bottom_actions"
    const val CHECK_BUTTON = "about_check_button"
    const val OPEN_RELEASE_PAGE = "about_open_release_page"
}

/** Brand and runtime version above a persistent, manually invoked update action. */
@OptIn(ExperimentalLayoutApi::class)
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
    RescueAuthPageScaffold(
        modifier = modifier.fillMaxSize().testTag(AboutTestTags.SCREEN),
        title = stringResource(R.string.about_title),
        navigationIcon = { RescueAuthBackButton(onBack) },
        bottomBar = { AboutUpdateAction(state is UpdateUiState.Checking, onCheckForUpdates) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).testTag(AboutTestTags.CONTENT)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenTokens.horizontalPadding, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(CardTokens.listSpacing),
        ) {
            StudioBrandArtwork()
            RescueAuthCard(containerColor = MaterialTheme.colorScheme.background,
                contentPadding = CardTokens.noPadding) {
                Column(Modifier.fillMaxWidth().padding(vertical = Spacing.md)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.about_product_description),
                        Modifier.fillMaxWidth().padding(top = Spacing.xs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(Modifier.fillMaxWidth().padding(top = Spacing.md),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        RescueAuthMetaPill(stringResource(R.string.compact_codes), icon = Icons.Outlined.Timer)
                        RescueAuthMetaPill(stringResource(R.string.compact_keys), icon = Icons.Outlined.Key)
                        RescueAuthMetaPill(stringResource(R.string.recovery_codes_title), icon = Icons.Outlined.Shield)
                    }
                }
            }
            RescueAuthCard(containerColor = CardTokens.elevatedContainerColor()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text(stringResource(R.string.settings_version_label), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(2f), horizontalAlignment = Alignment.End) {
                        Text(versionName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.End)
                        Text(stringResource(R.string.about_build_format, versionCode),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End)
                    }
                }
            }
            // Idle needs no instruction card. Checking is described by the disabled footer.
            if (state != UpdateUiState.Idle && state != UpdateUiState.Checking) {
                RescueAuthCard { UpdateStatusBody(state, onOpenReleasePage) }
            }
        }
    }
}

@Composable
private fun AboutUpdateAction(checking: Boolean, onCheck: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
        .imePadding().navigationBarsPadding()
        .padding(horizontal = ScreenTokens.horizontalPadding, vertical = Spacing.xs)
        .testTag(AboutTestTags.FOOTER)) {
        RescueAuthCard(shape = CardTokens.actionBarShape, contentPadding = CardTokens.actionBarPadding,
            modifier = Modifier.border(CardTokens.actionBarBorderWidth, CardTokens.outlineColor(), CardTokens.actionBarShape)) {
            Button(onClick = onCheck, enabled = !checking,
                modifier = Modifier.fillMaxWidth().heightIn(min = CardTokens.actionButtonHeight).testTag(AboutTestTags.CHECK_BUTTON),
                shape = CardTokens.actionButtonShape,
                colors = ButtonDefaults.buttonColors(containerColor = CardTokens.actionPrimaryContainerColor(),
                    contentColor = CardTokens.actionPrimaryContentColor())) {
                if (checking) CircularProgressIndicator(Modifier.size(CardTokens.actionIconSize),
                    color = CardTokens.actionPrimaryContentColor(), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Refresh, null, Modifier.size(CardTokens.actionIconSize))
                Text(stringResource(if (checking) R.string.about_checking else R.string.about_check_for_updates),
                    Modifier.padding(start = Spacing.xs).weight(1f, fill = false), textAlign = TextAlign.Center)
            }
        }
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
