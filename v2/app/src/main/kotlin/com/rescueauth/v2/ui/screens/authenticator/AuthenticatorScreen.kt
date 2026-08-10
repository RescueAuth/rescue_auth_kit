package com.rescueauth.v2.ui.screens.authenticator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.authenticator.AuthenticatorUiState
import com.rescueauth.v2.ui.authenticator.TotpCardUi
import com.rescueauth.v2.ui.components.EmptyState
import com.rescueauth.v2.ui.components.LoadingState
import com.rescueauth.v2.ui.components.ProviderAccountListItem
import com.rescueauth.v2.ui.components.TotpCard
import com.rescueauth.v2.ui.model.AccountUi
import com.rescueauth.v2.ui.model.RecoveryCodeSetUi
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing

/**
 * Authenticator top-level screen — production data path (Phase 4 P1/P3).
 *
 * Renders the real Provider/Account list ([uiState.accounts]) with a recovery
 * summary line (counts only, never secret values), plus the live TOTP list
 * with codes + countdown, an EmptyState for an empty vault and a FAB that
 * opens the Add TOTP flow (owned by the caller/Route). Tapping an account row
 * opens its detail destination (Recovery Codes). Composable never touches
 * Room entities; it only consumes UI models and callbacks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthenticatorScreen(
    modifier: Modifier = Modifier,
    uiState: AuthenticatorUiState = AuthenticatorUiState(),
    snackbarHostState: SnackbarHostState? = null,
    onAddClick: (() -> Unit)? = null,
    onCopyClick: ((TotpCardUi) -> Unit)? = null,
    onDeleteClick: ((TotpCardUi) -> Unit)? = null,
    onOpenAccount: ((String) -> Unit)? = null,
    onOpenSearch: (() -> Unit)? = null,
    onTogglePin: ((AccountUi) -> Unit)? = null,
    onAddProviderClick: (() -> Unit)? = null,
    onRenameProvider: ((String) -> Unit)? = null,
    onDeleteProvider: ((String) -> Unit)? = null,
    onAddAccount: ((String) -> Unit)? = null,
    onRenameAccount: ((AccountUi) -> Unit)? = null,
    onMoveAccount: ((AccountUi) -> Unit)? = null,
    onMergeAccount: ((AccountUi) -> Unit)? = null,
    onDeleteAccount: ((AccountUi) -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.authenticator_title)) },
                actions = {
                    if (onOpenSearch != null) {
                        IconButton(onClick = onOpenSearch) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = stringResource(R.string.search_title),
                            )
                        }
                    }
                    if (onAddProviderClick != null) {
                        IconButton(onClick = onAddProviderClick) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = stringResource(R.string.provider_add_provider),
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
                        contentDescription = stringResource(R.string.nav_authenticator),
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
                    title = stringResource(R.string.authenticator_empty_title),
                    body = stringResource(R.string.authenticator_empty_body),
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
                    // Provider-grouped list (Phase 4 — Provider/Account
                    // management). Each Provider row exposes a menu
                    // (Rename / Add Account / Delete) and each Account row
                    // exposes a menu (Rename / Move / Merge / Delete). The
                    // home list only shows counts — code values live in the
                    // account detail screen and are never expanded here.
                    items(
                        count = uiState.providers.size,
                        key = { index -> uiState.providers[index].id },
                    ) { pIndex ->
                        val provider = uiState.providers[pIndex]
                        ProviderGroupHeader(
                            provider = provider,
                            onRename = onRenameProvider,
                            onAddAccount = onAddAccount,
                            onDelete = onDeleteProvider,
                        )
                    }

                    // Provider/Account rows with a recovery summary (P3). The
                    // home list only shows counts — code values live in the
                    // account detail screen and are never expanded here.
                    items(
                        count = uiState.accounts.size,
                        key = { index -> uiState.accounts[index].id },
                    ) { index ->
                        val account = uiState.accounts[index]
                        ProviderAccountListItem(
                            account = account,
                            onClick = onOpenAccount?.let { { it(account.id) } },
                            trailingAction = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RecoverySummaryLabel(account)
                                    AccountActionsMenu(
                                        account = account,
                                        onTogglePin = onTogglePin,
                                        onRename = onRenameAccount,
                                        onMove = onMoveAccount,
                                        onMerge = onMergeAccount,
                                        onDelete = onDeleteAccount,
                                    )
                                }
                            },
                        )
                    }

                    // Live TOTP cards (P1) remain directly visible.
                    items(
                        count = uiState.totpCards.size,
                        key = { index -> uiState.totpCards[index].credentialId },
                    ) { index ->
                        val card = uiState.totpCards[index]
                        TotpCard(
                            credential = card,
                            onCopyClick = onCopyClick?.let { { it(card) } },
                            onDeleteClick = onDeleteClick?.let { { it(card) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoverySummaryLabel(account: AccountUi) {
    if (account.recoverySets.isNotEmpty()) {
        Text(
            text = stringResource(
                R.string.recovery_codes_account_summary,
                account.remainingRecoveryCount,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Provider group header row with a management menu (Rename / Add Account /
 * Delete). Displays only the Provider name and its account count — never
 * secret values.
 */
@Composable
private fun ProviderGroupHeader(
    provider: com.rescueauth.v2.ui.model.ProviderUi,
    onRename: ((String) -> Unit)?,
    onAddAccount: ((String) -> Unit)?,
    onDelete: ((String) -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = provider.serviceName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = provider.accounts.size.toString() + " account(s)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onRename != null || onAddAccount != null || onDelete != null) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.provider_actions),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    if (onRename != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.provider_rename)) },
                            onClick = {
                                menuOpen = false
                                onRename(provider.serviceName)
                            },
                        )
                    }
                    if (onAddAccount != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.provider_add_account)) },
                            onClick = {
                                menuOpen = false
                                onAddAccount(provider.serviceName)
                            },
                        )
                    }
                    if (onDelete != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.provider_delete)) },
                            onClick = {
                                menuOpen = false
                                onDelete(provider.serviceName)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Account row overflow menu (Rename / Move / Merge / Delete). No secrets are
 * ever shown here — only safe management actions.
 */
@Composable
private fun AccountActionsMenu(
    account: AccountUi,
    onTogglePin: ((AccountUi) -> Unit)?,
    onRename: ((AccountUi) -> Unit)?,
    onMove: ((AccountUi) -> Unit)?,
    onMerge: ((AccountUi) -> Unit)?,
    onDelete: ((AccountUi) -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.account_actions_label),
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            if (onTogglePin != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (account.isPinned) R.string.account_unpin else R.string.account_pin,
                            ),
                        )
                    },
                    onClick = { menuOpen = false; onTogglePin(account) },
                )
            }
            if (onRename != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.account_rename)) },
                    onClick = { menuOpen = false; onRename(account) },
                )
            }
            if (onMove != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.account_move)) },
                    onClick = { menuOpen = false; onMove(account) },
                )
            }
            if (onMerge != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.account_merge)) },
                    onClick = { menuOpen = false; onMerge(account) },
                )
            }
            if (onDelete != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.account_delete)) },
                    onClick = { menuOpen = false; onDelete(account) },
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AuthenticatorScreenEmptyPreview() {
    RescueAuthTheme {
        AuthenticatorScreen(
            uiState = AuthenticatorUiState(loading = false, totpCards = emptyList()),
            onAddClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AuthenticatorScreenWithDataPreview() {
    RescueAuthTheme {
        AuthenticatorScreen(
            uiState = AuthenticatorUiState(
                loading = false,
                accounts = listOf(
                    AccountUi(
                        id = "a1",
                        providerName = "GitHub",
                        accountName = "alice@example.com",
                        recoverySets = listOf(
                            RecoveryCodeSetUi(
                                id = "s1",
                                title = "Backup codes",
                                usedCount = 1,
                                totalCount = 3,
                            ),
                        ),
                    ),
                ),
                totpCards = listOf(
                    TotpCardUi(
                        credentialId = "t1",
                        stableId = "t1",
                        accountId = "a1",
                        issuer = "GitHub",
                        accountName = "alice@example.com",
                        algorithm = "SHA1",
                        digits = 6,
                        periodSeconds = 30,
                        currentCode = "123 456",
                        remainingSeconds = 24,
                        progressFraction = 0.8f,
                    ),
                ),
            ),
            onAddClick = {},
            onOpenAccount = {},
        )
    }
}
