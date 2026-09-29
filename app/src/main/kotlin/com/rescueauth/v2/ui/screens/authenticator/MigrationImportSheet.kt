package com.rescueauth.v2.ui.screens.authenticator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import com.rescueauth.v2.ui.components.RescueAuthButton as Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.migration.MigrationEntryStatus
import com.rescueauth.v2.migration.MigrationTotpCandidate
import com.rescueauth.v2.ui.authenticator.MigrationImportUiState
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.ui.components.RescueAuthIconBadge
import com.rescueauth.v2.ui.components.RescueAuthSectionHeader
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.Spacing

/** Safe migration preview sheet: metadata only, with clear counts and action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MigrationImportSheet(
    state: MigrationImportUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                RescueAuthIconBadge(icon = Icons.Filled.QrCodeScanner, size = 40.dp, iconSize = 21.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.migration_preview_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    state.batchProgress?.let { progress ->
                        Text(
                            text = stringResource(R.string.migration_preview_batch_progress, progress.first, progress.second),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            val result = state.result
            if (result != null) {
                RescueAuthCard(containerColor = CardTokens.elevatedContainerColor()) {
                    Text(
                        text = stringResource(R.string.migration_imported_message, result.imported, result.duplicates),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (result.unsupported > 0) {
                        Text(stringResource(R.string.migration_unsupported, result.unsupported), style = MaterialTheme.typography.bodySmall)
                    }
                    if (result.invalid > 0) {
                        Text(stringResource(R.string.migration_invalid, result.invalid), style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.common_close))
                }
            } else {
                val importable = state.candidates.count { it.status == MigrationEntryStatus.IMPORTABLE }
                val unsupported = state.candidates.count { it.status == MigrationEntryStatus.UNSUPPORTED }
                val invalid = state.candidates.count { it.status == MigrationEntryStatus.INVALID }
                val issueSummary = listOfNotNull(
                    unsupported.takeIf { it > 0 }?.let { stringResource(R.string.migration_unsupported, it) },
                    invalid.takeIf { it > 0 }?.let { stringResource(R.string.migration_invalid, it) },
                ).joinToString(" · ")
                RescueAuthCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                    RescueAuthSectionHeader(
                        title = stringResource(R.string.migration_importable, importable),
                        subtitle = issueSummary.ifBlank { null },
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    items(state.candidates) { candidate ->
                        MigrationCandidateRow(candidate)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    TextButton(onClick = onDismiss, enabled = !state.importing,
                        modifier = Modifier.weight(CardTokens.actionSecondaryWeight).fillMaxHeight()) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = !state.importing && importable > 0,
                        modifier = Modifier.weight(CardTokens.actionPrimaryWeight).fillMaxHeight(),
                    ) {
                        Text(stringResource(R.string.migration_confirm_import, importable))
                    }
                }
            }
        }
    }
}

@Composable
private fun MigrationCandidateRow(candidate: MigrationTotpCandidate) {
    RescueAuthCard(
        containerColor = CardTokens.elevatedContainerColor(),
        contentPadding = Spacing.sm,
    ) {
        val issuer = candidate.issuer?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.migration_unknown_issuer)
        val account = candidate.name?.takeIf { it.isNotBlank() } ?: issuer
        Text(
            text = "$issuer · $account",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        when (candidate.status) {
            MigrationEntryStatus.IMPORTABLE -> Text(
                text = stringResource(R.string.migration_algorithm_digits, candidate.algorithm ?: "", candidate.digits ?: 0),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MigrationEntryStatus.UNSUPPORTED -> StatusText(stringResource(R.string.migration_unsupported, 1))
            MigrationEntryStatus.INVALID -> StatusText(stringResource(R.string.migration_invalid, 1))
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
