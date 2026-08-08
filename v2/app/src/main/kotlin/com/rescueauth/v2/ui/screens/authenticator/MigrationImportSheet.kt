package com.rescueauth.v2.ui.screens.authenticator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.migration.MigrationEntryStatus
import com.rescueauth.v2.migration.MigrationTotpCandidate
import com.rescueauth.v2.ui.authenticator.MigrationImportUiState
import com.rescueauth.v2.ui.theme.Spacing

/**
 * Migration import preview / result sheet (Phase 4 P2).
 *
 * Shown **after** scanning leaves the camera preview — a normal Compose
 * confirmation state. Lists every collected candidate (issuer / account /
 * algorithm / digits — **never** the secret), reports importable /
 * unsupported / invalid counts, and confirms the batch import. Once imported,
 * the same sheet shows the result counts (imported / duplicates / unsupported /
 * invalid).
 */
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
            Text(
                text = stringResource(R.string.migration_preview_title),
                style = MaterialTheme.typography.titleLarge,
            )

            val result = state.result
            if (result != null) {
                // Post-import result state.
                Text(
                    text = stringResource(R.string.migration_imported_message, result.imported, result.duplicates),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (result.unsupported > 0) {
                    Text(
                        text = stringResource(R.string.migration_unsupported, result.unsupported),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (result.invalid > 0) {
                    Text(
                        text = stringResource(R.string.migration_invalid, result.invalid),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = onDismiss) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            } else {
                // Pre-import preview state.
                val importable = state.candidates.count { it.status == MigrationEntryStatus.IMPORTABLE }
                val unsupported = state.candidates.count { it.status == MigrationEntryStatus.UNSUPPORTED }
                val invalid = state.candidates.count { it.status == MigrationEntryStatus.INVALID }

                Text(
                    text = stringResource(R.string.migration_importable, importable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (unsupported > 0) {
                    Text(
                        text = stringResource(R.string.migration_unsupported, unsupported),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (invalid > 0) {
                    Text(
                        text = stringResource(R.string.migration_invalid, invalid),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    items(state.candidates) { candidate ->
                        MigrationCandidateRow(candidate)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss, enabled = !state.importing) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    Button(
                        onClick = onConfirm,
                        enabled = !state.importing && importable > 0,
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            val issuer = candidate.issuer?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.migration_unknown_issuer)
            val account = candidate.name?.takeIf { it.isNotBlank() } ?: issuer
            Text(
                text = "$issuer · $account",
                style = MaterialTheme.typography.bodyLarge,
            )
            when (candidate.status) {
                MigrationEntryStatus.IMPORTABLE -> {
                    Text(
                        text = stringResource(
                            R.string.migration_algorithm_digits,
                            candidate.algorithm ?: "",
                            candidate.digits ?: 0,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                MigrationEntryStatus.UNSUPPORTED -> {
                    Text(
                        text = stringResource(R.string.migration_unsupported, 1),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                MigrationEntryStatus.INVALID -> {
                    Text(
                        text = stringResource(R.string.migration_invalid, 1),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
