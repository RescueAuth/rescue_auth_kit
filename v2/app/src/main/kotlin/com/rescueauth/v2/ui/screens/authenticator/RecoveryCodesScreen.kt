package com.rescueauth.v2.ui.screens.authenticator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.authenticator.RecoveryUiState
import com.rescueauth.v2.ui.components.EmptyState
import com.rescueauth.v2.ui.components.LoadingState
import com.rescueauth.v2.ui.components.RecoveryCodeSetCard
import com.rescueauth.v2.ui.model.RecoveryCodeSetUi
import com.rescueauth.v2.ui.model.RecoveryCodeUi
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing

/**
 * Account detail screen — Recovery Codes (Phase 4 P3).
 *
 * Renders the real recovery sets of one account with expand/collapse, reveal/
 * hide per code, per-code copy, mark used/unused, Copy All / Copy Remaining,
 * Edit and Delete (Undo). A FAB opens the Add sheet. Composable only consumes
 * UI models and callbacks — it never touches Room or the repository.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryCodesScreen(
    uiState: RecoveryUiState,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    revealedIds: Set<String> = emptySet(),
    onBack: (() -> Unit)? = null,
    onAddClick: (() -> Unit)? = null,
    onRevealToggle: ((String) -> Unit)? = null,
    onCopyCode: ((String) -> Unit)? = null,
    onCopyAll: ((String) -> Unit)? = null,
    onCopyRemaining: ((String) -> Unit)? = null,
    onMarkUsed: ((String) -> Unit)? = null,
    onMarkUnused: ((String) -> Unit)? = null,
    onEdit: ((String) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.recovery_codes_title))
                        Text(
                            text = "${uiState.providerName} · ${uiState.accountName}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.a11y_back),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
        floatingActionButton = {
            if (onAddClick != null) {
                FloatingActionButton(onClick = onAddClick) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.recovery_codes_add_title),
                    )
                }
            }
        },
    ) { padding ->
        when {
            uiState.loading -> {
                LoadingState(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }
            uiState.isEmpty -> {
                EmptyState(
                    title = stringResource(R.string.recovery_codes_title),
                    body = stringResource(R.string.recovery_codes_empty_state),
                    modifier = Modifier.padding(padding),
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(
                        count = uiState.sets.size,
                        key = { index -> uiState.sets[index].id },
                    ) { index ->
                        val set = uiState.sets[index]
                        RecoveryCodeSetCard(
                            set = set,
                            revealedIds = revealedIds,
                            onRevealToggle = onRevealToggle,
                            onCopyCode = onCopyCode,
                            onCopyAll = onCopyAll?.let { { it(set.id) } },
                            onCopyRemaining = onCopyRemaining?.let { { it(set.id) } },
                            onMarkUsed = onMarkUsed,
                            onMarkUnused = onMarkUnused,
                            onEdit = onEdit?.let { { it(set.id) } },
                            onDelete = onDelete?.let { { it(set.id) } },
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RecoveryCodesScreenEmptyPreview() {
    RescueAuthTheme {
        RecoveryCodesScreen(
            uiState = RecoveryUiState(loading = false, providerName = "GitHub", accountName = "alice@example.com"),
            onAddClick = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RecoveryCodesScreenWithDataPreview() {
    RescueAuthTheme {
        RecoveryCodesScreen(
            uiState = RecoveryUiState(
                loading = false,
                providerName = "GitHub",
                accountName = "alice@example.com",
                sets = listOf(
                    RecoveryCodeSetUi(
                        id = "s1",
                        title = "Backup codes",
                        usedCount = 1,
                        totalCount = 3,
                        codes = listOf(
                            RecoveryCodeUi("c1", "ABCD-EFGH-1", isUsed = true),
                            RecoveryCodeUi("c2", "IJKL-MNOP-2", isUsed = false),
                            RecoveryCodeUi("c3", "QRST-UVWX-3", isUsed = false),
                        ),
                    ),
                ),
            ),
            revealedIds = emptySet(),
            onAddClick = {},
            onRevealToggle = {},
            onCopyCode = {},
            onCopyAll = {},
            onCopyRemaining = {},
            onMarkUsed = {},
            onMarkUnused = {},
            onEdit = {},
            onDelete = {},
            onBack = {},
        )
    }
}
