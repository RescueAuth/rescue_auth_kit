package com.rescueauth.v2.ui.screens.authenticator

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.migration.MigrationEntryStatus
import com.rescueauth.v2.migration.MigrationTotpCandidate
import com.rescueauth.v2.ui.authenticator.MigrationImportUiState
import com.rescueauth.v2.ui.components.*
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.Spacing

/** Read-only candidates and the existing import controller use the common sheet/action layout. */
@Composable
fun MigrationImportSheet(state: MigrationImportUiState, onConfirm: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val result = state.result
    val importable = state.candidates.count { it.status == MigrationEntryStatus.IMPORTABLE }
    val unsupported = state.candidates.count { it.status == MigrationEntryStatus.UNSUPPORTED }
    val invalid = state.candidates.count { it.status == MigrationEntryStatus.INVALID }
    val issues = listOfNotNull(unsupported.takeIf { it > 0 }?.let { stringResource(R.string.migration_unsupported, it) },
        invalid.takeIf { it > 0 }?.let { stringResource(R.string.migration_invalid, it) }).joinToString(" · ")
    RescueAuthSheet(stringResource(R.string.migration_preview_title), Icons.Filled.QrCodeScanner, onDismiss, modifier,
        subtitle = state.batchProgress?.let { stringResource(R.string.migration_preview_batch_progress, it.first, it.second) },
        dismissEnabled = !state.importing,
        footer = { dismiss ->
            if (result != null) RescueAuthSingleActionBar(stringResource(R.string.common_close), Icons.Filled.Check, dismiss)
            else RescueAuthActionBar(stringResource(R.string.common_cancel), Icons.Filled.Close, dismiss,
                stringResource(R.string.migration_confirm_import, importable), Icons.Filled.Download, onConfirm,
                secondaryEnabled = !state.importing, primaryEnabled = !state.importing && importable > 0)
        }) {
        if (result != null) RescueAuthCard {
            Text(stringResource(R.string.migration_imported_message, result.imported, result.duplicates), style = MaterialTheme.typography.bodyLarge)
            if (result.unsupported > 0) Text(stringResource(R.string.migration_unsupported, result.unsupported), style = MaterialTheme.typography.bodySmall)
            if (result.invalid > 0) Text(stringResource(R.string.migration_invalid, result.invalid), style = MaterialTheme.typography.bodySmall)
        } else {
            RescueAuthCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                RescueAuthSectionHeader(stringResource(R.string.migration_importable, importable), issues.ifBlank { null })
            }
            LazyColumn(Modifier.fillMaxWidth().height(180.dp), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                items(state.candidates) { MigrationCandidateRow(it) }
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
