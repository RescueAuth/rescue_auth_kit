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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import com.rescueauth.v2.ui.components.BreadcrumbItem
import com.rescueauth.v2.ui.components.BreadcrumbTopBar
import com.rescueauth.v2.ui.components.CountdownIndicator
import com.rescueauth.v2.ui.components.EmptyState
import com.rescueauth.v2.ui.components.LoadingState
import com.rescueauth.v2.ui.components.RescueAuthRowCard
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.model.AccountUi
import com.rescueauth.v2.ui.model.ProviderUi
import com.rescueauth.v2.ui.model.RecoveryCodeSetUi
import com.rescueauth.v2.ui.model.TotpCredentialUi
import com.rescueauth.v2.ui.navigation.RescueAuthRoutes
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
    // Logical-path navigation: the home screen first presents the Provider
    // list (level 1). Tapping a Provider drills into that Provider's Account
    // list (level 2), and tapping an Account opens its detail (level 3 — see
    // RecoveryCodesScreen). No longer is every Provider/Account flattened onto
    // a single screen.
    var selectedProviderName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedProvider = selectedProviderName?.let { name ->
        uiState.providers.find { it.serviceName == name }
    }
    val atProviderLevel = selectedProviderName != null

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    // Breadcrumb mirrors the current logical path:
                    // level 1 → "Authenticator"; level 2 → "Authenticator > Provider".
                    BreadcrumbTopBar(
                        items = if (atProviderLevel) {
                            listOf(
                                BreadcrumbItem(
                                    label = stringResource(R.string.authenticator_title),
                                    destination = RescueAuthRoutes.AUTHENTICATOR,
                                ),
                                BreadcrumbItem(
                                    label = selectedProvider?.serviceName ?: "",
                                    isCurrent = true,
                                ),
                            )
                        } else {
                            listOf(
                                BreadcrumbItem(
                                    label = stringResource(R.string.authenticator_title),
                                    isCurrent = true,
                                ),
                            )
                        },
                        onNavigate = { selectedProviderName = null },
                        ellipsisContentDescription = stringResource(R.string.breadcrumb_ellipsis),
                        moreMenuContentDescription = stringResource(R.string.breadcrumb_more_ancestors),
                    )
                },
                actions = {
                    if (onOpenSearch != null) {
                        IconButton(onClick = onOpenSearch) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = stringResource(R.string.search_title),
                            )
                        }
                    }
                    if (!atProviderLevel && onAddProviderClick != null) {
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
            // Level 2: an Account list scoped to the selected Provider. The
            // provider's accounts are shown with their TOTP codes inline so the
            // user still sees codes at a glance along the logical path.
            selectedProvider != null -> {
                ProviderAccountsList(
                    provider = selectedProvider,
                    onCopyClick = onCopyClick,
                    onDeleteClick = onDeleteClick,
                    onOpenAccount = onOpenAccount,
                    onTogglePin = onTogglePin,
                    onRename = onRenameAccount,
                    onMove = onMoveAccount,
                    onMerge = onMergeAccount,
                    onDelete = onDeleteAccount,
                    onAddAccount = onAddAccount?.let { { it(selectedProvider.serviceName) } },
                    modifier = Modifier.padding(padding),
                )
            }
            // Level 1: the Provider list is the entry point of the logical path.
            else -> {
                ProviderList(
                    providers = uiState.providers,
                    onProviderClick = { selectedProviderName = it },
                    onRenameProvider = onRenameProvider,
                    onAddAccount = onAddAccount,
                    onDeleteProvider = onDeleteProvider,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

/**
 * Level 1 — the Provider list. Each row is the entry point of the logical
 * path (Provider → Account → TOTP). Tapping a row drills into its accounts.
 */
@Composable
private fun ProviderList(
    providers: List<ProviderUi>,
    onProviderClick: (String) -> Unit,
    onRenameProvider: ((String) -> Unit)? = null,
    onAddAccount: ((String) -> Unit)? = null,
    onDeleteProvider: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(CardTokens.listOuterPadding),
        verticalArrangement = Arrangement.spacedBy(CardTokens.listSpacing),
    ) {
        items(
            items = providers,
            key = { it.id },
        ) { provider ->
            ProviderListItem(
                provider = provider,
                onClick = { onProviderClick(provider.serviceName) },
                onRename = onRenameProvider,
                onAddAccount = onAddAccount,
                onDelete = onDeleteProvider,
            )
        }
    }
}

/**
 * Level 2 — the Account list of one Provider. Each Account card shows its
 * TOTP codes inline (the core "see your code" value is preserved) and opens
 * the account detail on tap.
 */
@Composable
private fun ProviderAccountsList(
    provider: ProviderUi,
    onCopyClick: ((TotpCardUi) -> Unit)? = null,
    onDeleteClick: ((TotpCardUi) -> Unit)? = null,
    onOpenAccount: ((String) -> Unit)? = null,
    onTogglePin: ((AccountUi) -> Unit)? = null,
    onRename: ((AccountUi) -> Unit)? = null,
    onMove: ((AccountUi) -> Unit)? = null,
    onMerge: ((AccountUi) -> Unit)? = null,
    onDelete: ((AccountUi) -> Unit)? = null,
    onAddAccount: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(CardTokens.listOuterPadding),
        verticalArrangement = Arrangement.spacedBy(CardTokens.listSpacing),
    ) {
        // provider.accounts is never empty (a Provider is built by grouping
        // accounts under a serviceName), so we render the accounts directly.
        items(
            items = provider.accounts,
            key = { it.id },
        ) { account ->
            AccountWithTotpCard(
                account = account,
                onCopyClick = onCopyClick,
                onDeleteClick = onDeleteClick,
                onOpenAccount = onOpenAccount?.let { { it(account.id) } },
                onTogglePin = onTogglePin,
                onRename = onRename,
                onMove = onMove,
                onMerge = onMerge,
                onDelete = onDelete,
            )
        }
        if (onAddAccount != null) {
            item(key = "add_account") {
                Text(
                    text = stringResource(R.string.provider_add_account),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(onClick = onAddAccount)
                        .padding(Spacing.md),
                )
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
        shape = CardTokens.shape,
        colors = CardDefaults.cardColors(
            containerColor = CardTokens.containerColor(),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CardTokens.contentPadding),
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
 * Level 1 — Provider list item (the entry point of the logical path).
 *
 * Renders one Provider as a tappable card that drills into its Account list
 * (level 2). Displays only safe metadata — the Provider name, its account
 * count and a pinned-account count — never secret values. A management menu
 * (Rename / Add Account / Delete) is available from the trailing "…".
 */
@Composable
private fun ProviderListItem(
    provider: ProviderUi,
    onClick: () -> Unit,
    onRename: ((String) -> Unit)? = null,
    onAddAccount: ((String) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    RescueAuthRowCard(
        onClick = onClick,
        containerColor = CardTokens.containerColor(),
        modifier = Modifier
            .testTag("provider_row_${provider.serviceName}")
            .semantics { role = Role.Button },
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = provider.serviceName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val pinnedCount = provider.accounts.count { it.isPinned }
            Text(
                text = if (pinnedCount > 0) {
                    stringResource(
                        R.string.provider_accounts_with_pinned,
                        provider.accounts.size,
                        pinnedCount,
                    )
                } else {
                    stringResource(
                        R.string.provider_accounts_count,
                        provider.accounts.size,
                    )
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Chevron signals this row opens a deeper level.
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
        } else {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
