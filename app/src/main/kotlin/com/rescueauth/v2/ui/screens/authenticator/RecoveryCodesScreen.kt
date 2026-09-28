package com.rescueauth.v2.ui.screens.authenticator

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.rescueauth.v2.ui.components.RescueAuthPageScaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.authenticator.RecoveryUiState
import com.rescueauth.v2.ui.components.FloatingAddPosition
import com.rescueauth.v2.ui.components.rememberFloatingAddPosition
import com.rescueauth.v2.ui.components.RescueAuthFloatingAddOverlay
import com.rescueauth.v2.ui.theme.AddActionTokens
import com.rescueauth.v2.ui.components.RescueAuthBackButton
import com.rescueauth.v2.ui.components.EmptyState
import com.rescueauth.v2.ui.components.ErrorState
import com.rescueauth.v2.ui.components.LoadingState
import com.rescueauth.v2.ui.components.RecoveryCodeSetCard
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.ui.components.RescueAuthIconBadge
import com.rescueauth.v2.ui.components.RescueAuthSectionHeader
import com.rescueauth.v2.ui.model.RecoveryCodeSetUi
import com.rescueauth.v2.ui.model.RecoveryCodeUi
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.ScreenTokens
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
    onMove: ((String) -> Unit)? = null,
    onNavigate: ((String) -> Unit)? = null,
    floatingAddPosition: FloatingAddPosition = rememberFloatingAddPosition(),
) {
    val hasAdd = onAddClick != null && !uiState.loading && uiState.error == null
    val clearance = if (hasAdd) AddActionTokens.contentClearance else 0.dp
    Box(modifier.fillMaxSize()) {
        RescueAuthPageScaffold(
            modifier = Modifier.fillMaxSize(),
            title = uiState.accountName.ifBlank { stringResource(R.string.recovery_codes_title) },
            subtitle = uiState.providerName.ifBlank { stringResource(R.string.authenticator_title) },
            navigationIcon = onBack?.let { callback -> { RescueAuthBackButton(callback) } },
            snackbarHost = { snackbarHostState?.let { SnackbarHost(it, Modifier.padding(bottom = clearance)) } },
        ) { padding ->
            when {
                uiState.loading -> {
                    LoadingState(
                        modifier = Modifier
                            .fillMaxSize()
                        .padding(padding),
                    )
                }
                uiState.error != null -> {
                    ErrorState(
                        title = stringResource(R.string.common_error_title),
                        message = uiState.error,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )
                }
                uiState.isEmpty -> {
                    EmptyState(
                        title = stringResource(R.string.recovery_codes_title),
                        body = stringResource(R.string.recovery_codes_empty_state),
                        actionLabel = onAddClick?.let { stringResource(R.string.recovery_codes_add_title) },
                        onAction = onAddClick,
                        modifier = Modifier.padding(padding),
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentPadding = PaddingValues(
                            start = ScreenTokens.horizontalPadding,
                            end = ScreenTokens.horizontalPadding,
                            top = Spacing.md,
                            bottom = Spacing.xxl + clearance,
                        ),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        item(key = "account-summary") {
                            RescueAuthCard(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentPadding = Spacing.md,
                            ) {
                                RescueAuthIconBadge(icon = Icons.Filled.Key, size = 40.dp, iconSize = 20.dp)
                                RescueAuthSectionHeader(
                                    title = stringResource(R.string.recovery_codes_title),
                                    subtitle = stringResource(
                                        R.string.recovery_codes_detail_subtitle,
                                        uiState.sets.sumOf { it.remainingCount },
                                        uiState.sets.sumOf { it.totalCount },
                                    ),
                                    modifier = Modifier.padding(top = Spacing.xs),
                                )
                            }
                        }
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
                                onMove = onMove?.let { { it(set.id) } },
                            )
                        }
                    }
                }
            }
        }
        if (hasAdd) RescueAuthFloatingAddOverlay(
            visible = true, enabled = true, position = floatingAddPosition,
            onClick = { onAddClick?.invoke() }, modifier = Modifier.navigationBarsPadding())
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
