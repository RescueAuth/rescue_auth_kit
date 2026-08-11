package com.rescueauth.v2.ui.screens.authenticator

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.authenticator.AuthenticatorUiState
import com.rescueauth.v2.ui.authenticator.TotpCardUi
import com.rescueauth.v2.ui.components.CountdownIndicator
import com.rescueauth.v2.ui.components.EmptyState
import com.rescueauth.v2.ui.components.LoadingState
import com.rescueauth.v2.ui.model.AccountUi
import com.rescueauth.v2.ui.model.ProviderUi
import com.rescueauth.v2.ui.model.RecoveryCodeSetUi
import com.rescueauth.v2.ui.model.TotpCredentialUi
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing

/**
 * Authenticator top-level screen — production data path (Phase 4 P1/P3).
 *
 * Renders a Provider → Account → TOTP nested list so the user sees current
 * TOTP codes directly on the home screen without navigating to a detail page.
 * Each account card shows its TOTP codes (current code, countdown, copy
 * action) and a recovery summary line (counts only, never secret values).
 * An EmptyState is shown for an empty vault and a FAB opens the Add TOTP flow.
 *
 * Composable never touches Room entities; it only consumes UI models and
 * callbacks.
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
                    // Provider → Account → TOTP nested display.
                    // Each Provider is a visual grouping header. Each Account
                    // is a card that directly shows its TOTP codes (current
                    // code, countdown, copy) so the user sees codes at a
                    // glance without navigating to a detail screen.
                    uiState.providers.forEachIndexed { pIndex, provider ->
                        item(key = "provider_${provider.id}") {
                            ProviderGroupHeader(
                                provider = provider,
                                onRename = onRenameProvider,
                                onAddAccount = onAddAccount,
                                onDelete = onDeleteProvider,
                            )
                        }
                        provider.accounts.forEach { account ->
                            item(key = "account_${account.id}") {
                                AccountWithTotpCard(
                                    account = account,
                                    onCopyClick = onCopyClick,
                                    onDeleteClick = onDeleteClick,
                                    onOpenAccount = onOpenAccount?.let { { it(account.id) } },
                                    onTogglePin = onTogglePin,
                                    onRename = onRenameAccount,
                                    onMove = onMoveAccount,
                                    onMerge = onMergeAccount,
                                    onDelete = onDeleteAccount,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Account card that directly shows its TOTP codes inline.
 *
 * Each TOTP code is rendered as a row with:
 * - Account name (if different from provider)
 * - Current code (large monospace, clickable to copy)
 * - Countdown indicator (remaining seconds + progress ring)
 * - Copy and Delete icon buttons
 *
 * Multiple TOTPs under the same account are all shown — no silent dropping.
 */
@Composable
private fun AccountWithTotpCard(
    account: AccountUi,
    onCopyClick: ((TotpCardUi) -> Unit)? = null,
    onDeleteClick: ((TotpCardUi) -> Unit)? = null,
    onOpenAccount: (() -> Unit)? = null,
    onTogglePin: ((AccountUi) -> Unit)? = null,
    onRename: ((AccountUi) -> Unit)? = null,
    onMove: ((AccountUi) -> Unit)? = null,
    onMerge: ((AccountUi) -> Unit)? = null,
    onDelete: ((AccountUi) -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // Account header row: provider name + pin + actions menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = account.accountName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (account.isPinned) {
                            androidx.compose.foundation.layout.Spacer(
                                modifier = Modifier.padding(start = Spacing.xs),
                            )
                            Icon(
                                imageVector = Icons.Filled.PushPin,
                                contentDescription = stringResource(R.string.account_pinned_label),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                    // Recovery summary (counts only, never secret values)
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
                AccountActionsMenu(
                    account = account,
                    onTogglePin = onTogglePin,
                    onRename = onRename,
                    onMove = onMove,
                    onMerge = onMerge,
                    onDelete = onDelete,
                )
            }

            // TOTP codes — all of them, no silent dropping
            account.totpCredentials.forEach { totp ->
                TotpInlineRow(
                    totp = totp,
                    onCopyClick = onCopyClick?.let { cb ->
                        { cb(totp.toTotpCardUi(account)) }
                    },
                    onDeleteClick = onDeleteClick?.let { cb ->
                        { cb(totp.toTotpCardUi(account)) }
                    },
                )
            }

            // If account has no TOTP codes but has recovery sets, show a
            // hint to open the detail for recovery codes.
            if (account.totpCredentials.isEmpty() && account.recoverySets.isNotEmpty() && onOpenAccount != null) {
                Text(
                    text = stringResource(R.string.recovery_codes_account_summary, account.remainingRecoveryCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onOpenAccount),
                )
            }
        }
    }
}

/**
 * One TOTP code row shown inline within an account card.
 *
 * Shows the current code (large, monospace, clickable to copy), a live
 * [CountdownIndicator], and Copy/Delete actions. The code text is clickable
 * to copy as well.
 */
@Composable
private fun TotpInlineRow(
    totp: TotpCredentialUi,
    onCopyClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
) {
    val code = totp.currentCode ?: "••••••"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = code,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = MaterialTheme.typography.headlineMedium.letterSpacing,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = if (onCopyClick != null) {
                    Modifier.clickable(onClick = onCopyClick)
                } else {
                    Modifier
                },
            )
        }
        CountdownIndicator(
            progressFraction = totp.progressFraction,
            remainingSeconds = totp.remainingSeconds,
            ringSize = 48,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (onCopyClick != null) {
                IconButton(
                    onClick = onCopyClick,
                    modifier = Modifier.semantics { role = Role.Button },
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.totp_copy_code),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onDeleteClick != null) {
                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.semantics { role = Role.Button },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.totp_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Converts a [TotpCredentialUi] back to [TotpCardUi] for callback compat. */
private fun TotpCredentialUi.toTotpCardUi(account: AccountUi): TotpCardUi = TotpCardUi(
    credentialId = id,
    stableId = stableId,
    accountId = account.id,
    issuer = account.providerName,
    accountName = account.accountName,
    algorithm = algorithm,
    digits = digits,
    periodSeconds = periodSeconds,
    currentCode = currentCode ?: "••••••",
    remainingSeconds = remainingSeconds,
    progressFraction = progressFraction,
)

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
    provider: ProviderUi,
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
                        totpCredentials = listOf(
                            TotpCredentialUi(
                                id = "t1",
                                stableId = "t1",
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
                providers = listOf(
                    ProviderUi(
                        id = "provider:GitHub",
                        serviceName = "GitHub",
                        accounts = listOf(
                            AccountUi(
                                id = "a1",
                                providerName = "GitHub",
                                accountName = "alice@example.com",
                                totpCredentials = listOf(
                                    TotpCredentialUi(
                                        id = "t1",
                                        stableId = "t1",
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
