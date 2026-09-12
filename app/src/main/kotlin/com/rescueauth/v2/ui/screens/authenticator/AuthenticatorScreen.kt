package com.rescueauth.v2.ui.screens.authenticator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.authenticator.AuthenticatorUiState
import com.rescueauth.v2.ui.authenticator.TotpCardUi
import com.rescueauth.v2.ui.components.BrandIcons
import com.rescueauth.v2.ui.components.CountdownIndicator
import com.rescueauth.v2.ui.components.EmptyState
import com.rescueauth.v2.ui.components.ErrorState
import com.rescueauth.v2.ui.components.LoadingState
import com.rescueauth.v2.ui.components.RescueAuthAutoBadge
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.ui.components.RescueAuthChevron
import com.rescueauth.v2.ui.components.RescueAuthDivider
import com.rescueauth.v2.ui.components.RescueAuthInitialBadge
import com.rescueauth.v2.ui.components.RescueAuthIconBadge
import com.rescueauth.v2.ui.components.RescueAuthPageHeader
import com.rescueauth.v2.ui.components.RescueAuthRowCard
import com.rescueauth.v2.ui.components.RescueAuthSectionHeader
import com.rescueauth.v2.ui.components.RescueAuthMetric
import com.rescueauth.v2.ui.model.AccountUi
import com.rescueauth.v2.ui.model.ProviderUi
import com.rescueauth.v2.ui.model.TotpCredentialUi
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.Spacing

/** Modern, task-focused Authenticator home. */
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
    onOpenProvider: ((String) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onOpenRecovery: ((String) -> Unit)? = null,
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
    /** Persists a provider icon override (schema v4); null key = AUTO. */
    onSetProviderIcon: ((String, String?) -> Unit)? = null,
    initialProviderName: String? = null,
    initialAccountId: String? = null,
) {
    var selectedProviderName by rememberSaveable { mutableStateOf(initialProviderName) }
    var selectedAccountId by rememberSaveable { mutableStateOf(initialAccountId) }
    var pinnedOnly by rememberSaveable { mutableStateOf(false) }
    var iconPickerProvider by remember { mutableStateOf<ProviderUi?>(null) }
    // The production mapper supplies grouped providers. Keep the public screen
    // tolerant of older previews/tests that provide only the flat account list.
    val displayProviders = remember(uiState.providers, uiState.accounts) {
        if (uiState.providers.isNotEmpty()) {
            uiState.providers
        } else {
            uiState.accounts
                .groupBy { it.providerName }
                .map { (serviceName, accounts) ->
                    ProviderUi(
                        id = "provider:$serviceName",
                        serviceName = serviceName,
                        accounts = accounts,
                    )
                }
                .sortedBy { it.serviceName }
        }
    }
    val selectedProvider = selectedProviderName?.let { name ->
        displayProviders.firstOrNull { it.serviceName == name }
    }
    val selectedAccount = selectedAccountId?.let { id ->
        displayProviders.asSequence().flatMap { it.accounts.asSequence() }.firstOrNull { it.id == id }
    }
    val atAccountLevel = selectedAccount != null
    val atProviderLevel = selectedProvider != null && !atAccountLevel
    val providerCount = displayProviders.size
    val accountCount = displayProviders.sumOf { it.accounts.size }
    val credentialCount = displayProviders.sumOf { provider ->
        provider.accounts.sumOf { it.totpCredentials.size }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            RescueAuthPageHeader(
                title = if (atAccountLevel) {
                    selectedAccount?.accountName.orEmpty()
                } else if (atProviderLevel) {
                    selectedProvider?.serviceName.orEmpty()
                } else {
                    stringResource(R.string.authenticator_title)
                },
                subtitle = if (atAccountLevel) {
                    selectedAccount?.providerName
                } else if (atProviderLevel) {
                    stringResource(R.string.authenticator_provider_subtitle)
                } else {
                    stringResource(R.string.authenticator_home_subtitle)
                },
                navigationIcon = if (atAccountLevel || atProviderLevel) {
                    {
                        IconButton(onClick = {
                            if (onBack != null) {
                                onBack()
                            } else if (atAccountLevel) {
                                selectedAccountId = null
                            } else {
                                selectedProviderName = null
                            }
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.a11y_back),
                            )
                        }
                    }
                } else {
                    null
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
                    if (!atAccountLevel && !atProviderLevel && onAddProviderClick != null) {
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
        floatingActionButton = if (onAddClick != null && !uiState.isEmpty) {
            {
                FloatingActionButton(
                    onClick = onAddClick,
                    shape = MaterialTheme.shapes.medium,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.nav_authenticator),
                    )
                }
            }
        } else {
            {}
        },
    ) { padding ->
        when {
            uiState.loading -> LoadingState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                label = stringResource(R.string.authenticator_loading),
            )
            uiState.isEmpty -> EmptyState(
                title = stringResource(R.string.authenticator_empty_title),
                body = stringResource(R.string.authenticator_empty_body),
                actionLabel = onAddClick?.let { stringResource(R.string.authenticator_add_first) },
                onAction = onAddClick,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                icon = Icons.Filled.Shield,
            )
            uiState.error != null -> ErrorState(
                title = stringResource(R.string.common_error_title),
                message = uiState.error,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            atAccountLevel -> AccountDetailContent(
                account = selectedAccount!!,
                onCopyClick = onCopyClick,
                onDeleteClick = onDeleteClick,
                onTogglePin = onTogglePin,
                onRename = onRenameAccount,
                onMove = onMoveAccount,
                onMerge = onMergeAccount,
                onDelete = onDeleteAccount,
                onOpenRecovery = onOpenRecovery,
                modifier = Modifier.padding(padding),
            )
            atProviderLevel -> ProviderAccountsContent(
                provider = selectedProvider!!,
                onOpenAccount = { accountId ->
                    if (onOpenAccount != null) onOpenAccount(accountId) else selectedAccountId = accountId
                },
                onAddAccount = onAddAccount?.let { callback ->
                    { callback(selectedProvider.serviceName) }
                },
                modifier = Modifier.padding(padding),
            )
            else -> {
                val homeProviders = if (pinnedOnly) {
                    displayProviders.filter { provider -> provider.accounts.any { it.isPinned } }
                } else displayProviders
                ProviderHomeContent(
                providers = homeProviders,
                providerCount = homeProviders.size,
                accountCount = homeProviders.sumOf { it.accounts.size },
                credentialCount = homeProviders.sumOf { provider -> provider.accounts.sumOf { it.totpCredentials.size } },
                hasPinnedAccounts = displayProviders.any { provider -> provider.accounts.any { it.isPinned } },
                pinnedOnly = pinnedOnly,
                onPinnedOnlyChange = { pinnedOnly = it },
                onProviderClick = { providerName ->
                    if (onOpenProvider != null) onOpenProvider(providerName) else selectedProviderName = providerName
                },
                onRenameProvider = onRenameProvider,
                onAddAccount = onAddAccount,
                onDeleteProvider = onDeleteProvider,
                onEditIcon = if (onSetProviderIcon != null) {
                    { provider -> iconPickerProvider = provider }
                } else {
                    null
                },
                modifier = Modifier.padding(padding),
                )
            }
        }
    }

    if (iconPickerProvider != null) {
        ProviderIconPickerSheet(
            provider = iconPickerProvider!!,
            onSelect = { key ->
                onSetProviderIcon?.invoke(iconPickerProvider!!.serviceName, key)
                iconPickerProvider = null
            },
            onDismiss = { iconPickerProvider = null },
        )
    }
}

@Composable
private fun ProviderHomeContent(
    providers: List<ProviderUi>,
    providerCount: Int,
    accountCount: Int,
    credentialCount: Int,
    hasPinnedAccounts: Boolean,
    pinnedOnly: Boolean,
    onPinnedOnlyChange: (Boolean) -> Unit,
    onProviderClick: (String) -> Unit,
    onRenameProvider: ((String) -> Unit)?,
    onAddAccount: ((String) -> Unit)?,
    onDeleteProvider: ((String) -> Unit)?,
    onEditIcon: ((ProviderUi) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ScreenTokens.horizontalPadding,
            end = ScreenTokens.horizontalPadding,
            top = Spacing.md,
            bottom = Spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(CardTokens.listSpacing),
    ) {
        item(key = "vault-overview") {
            RescueAuthCard(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f),
                contentPadding = Spacing.md,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    RescueAuthIconBadge(
                        icon = Icons.Filled.Shield,
                        size = 38.dp,
                        iconSize = 20.dp,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.authenticator_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = stringResource(
                                R.string.authenticator_providers_subtitle,
                                providerCount,
                                accountCount,
                                credentialCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                        )
                    }
                }
            }
        }
        item(key = "providers-heading") {
            if (hasPinnedAccounts) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    FilterChip(
                        selected = !pinnedOnly,
                        onClick = { onPinnedOnlyChange(false) },
                        label = { Text(stringResource(R.string.authenticator_filter_all)) },
                    )
                    FilterChip(
                        selected = pinnedOnly,
                        onClick = { onPinnedOnlyChange(true) },
                        label = { Text(stringResource(R.string.authenticator_filter_pinned)) },
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.xs))
            }
            RescueAuthSectionHeader(
                title = stringResource(R.string.authenticator_providers_heading),
            )
        }
        items(providers, key = { it.id }) { provider ->
            ProviderListItem(
                provider = provider,
                onClick = { onProviderClick(provider.serviceName) },
                onRename = onRenameProvider,
                onAddAccount = onAddAccount,
                onDelete = onDeleteProvider,
                onEditIcon = onEditIcon?.let { callback -> { callback(provider) } },
            )
        }
    }
}

@Composable
private fun ProviderAccountsContent(
    provider: ProviderUi,
    onOpenAccount: (String) -> Unit,
    onAddAccount: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = ScreenTokens.horizontalPadding,
            vertical = Spacing.md,
        ),
        verticalArrangement = Arrangement.spacedBy(CardTokens.listSpacing),
    ) {
        item(key = "accounts-heading") {
            RescueAuthSectionHeader(
                title = stringResource(R.string.authenticator_accounts_heading),
                subtitle = stringResource(R.string.authenticator_accounts_subtitle),
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        items(provider.accounts, key = { it.id }) { account ->
            AccountDirectoryRow(
                account = account,
                onClick = { onOpenAccount(account.id) },
            )
        }
        if (onAddAccount != null) {
            item(key = "add-account") {
                RescueAuthRowCard(
                    onClick = onAddAccount,
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.padding(top = Spacing.xs),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.provider_add_account),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    RescueAuthChevron()
                }
            }
        }
    }
}

@Composable
private fun AccountDirectoryRow(
    account: AccountUi,
    onClick: () -> Unit,
) {
    RescueAuthRowCard(
        onClick = onClick,
        containerColor = CardTokens.elevatedContainerColor(),
        modifier = Modifier
            .testTag("account_row_${account.id}")
            .semantics { role = Role.Button },
    ) {
        RescueAuthAutoBadge(label = account.accountName, colorSeed = account.providerName)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.accountName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = accountSummary(account),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (account.isPinned) {
            Icon(
                imageVector = Icons.Filled.PushPin,
                contentDescription = stringResource(R.string.account_pinned_label),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        RescueAuthChevron()
    }
}

@Composable
private fun accountSummary(account: AccountUi): String {
    val credentialCount = account.totpCredentials.size
    val recoveryCount = account.recoverySets.size
    return if (recoveryCount > 0) {
        stringResource(R.string.account_credentials_and_recovery_summary, credentialCount, recoveryCount)
    } else {
        stringResource(R.string.account_credentials_summary, credentialCount)
    }
}

@Composable
private fun AccountDetailContent(
    account: AccountUi,
    onCopyClick: ((TotpCardUi) -> Unit)?,
    onDeleteClick: ((TotpCardUi) -> Unit)?,
    onTogglePin: ((AccountUi) -> Unit)?,
    onRename: ((AccountUi) -> Unit)?,
    onMove: ((AccountUi) -> Unit)?,
    onMerge: ((AccountUi) -> Unit)?,
    onDelete: ((AccountUi) -> Unit)?,
    onOpenRecovery: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ScreenTokens.horizontalPadding, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(CardTokens.listSpacing),
    ) {
        item(key = "account-heading") {
            RescueAuthSectionHeader(
                title = stringResource(R.string.authenticator_account_detail_heading),
                subtitle = account.providerName,
            )
        }
        item(key = account.id) {
            AccountWithTotpCard(
                account = account,
                onCopyClick = onCopyClick,
                onDeleteClick = onDeleteClick,
                onOpenAccount = null,
                onTogglePin = onTogglePin,
                onRename = onRename,
                onMove = onMove,
                onMerge = onMerge,
                onDelete = onDelete,
            )
        }
        if (account.recoverySets.isNotEmpty()) {
            item(key = "recovery-link") {
                RescueAuthRowCard(
                    onClick = onOpenRecovery?.let { callback -> { callback(account.id) } },
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    RescueAuthIconBadge(icon = Icons.Filled.Shield, size = 36.dp, iconSize = 18.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.recovery_codes_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.recovery_codes_account_summary, account.remainingRecoveryCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    RescueAuthChevron()
                }
            }
        }
    }
}

@Composable
private fun AccountWithTotpCard(
    account: AccountUi,
    onCopyClick: ((TotpCardUi) -> Unit)?,
    onDeleteClick: ((TotpCardUi) -> Unit)?,
    onOpenAccount: (() -> Unit)?,
    onTogglePin: ((AccountUi) -> Unit)?,
    onRename: ((AccountUi) -> Unit)?,
    onMove: ((AccountUi) -> Unit)?,
    onMerge: ((AccountUi) -> Unit)?,
    onDelete: ((AccountUi) -> Unit)?,
) {
    RescueAuthCard(
        containerColor = CardTokens.containerColor(),
        onClick = onOpenAccount,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            RescueAuthAutoBadge(
                label = account.accountName,
                colorSeed = account.providerName,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.accountName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = account.providerName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (account.isPinned) {
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = stringResource(R.string.account_pinned_label),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
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

        if (account.recoverySets.isNotEmpty() && account.totpCredentials.isEmpty()) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            com.rescueauth.v2.ui.components.RescueAuthStatusLine(
                text = stringResource(
                    R.string.recovery_codes_account_summary,
                    account.remainingRecoveryCount,
                ),
            )
        }

        account.totpCredentials.forEachIndexed { index, totp ->
            Spacer(modifier = Modifier.height(Spacing.sm))
            if (index > 0) RescueAuthDivider()
            TotpInlineRow(
                totp = totp,
                onCopyClick = onCopyClick?.let { callback ->
                    { callback(totp.toTotpCardUi(account)) }
                },
                onDeleteClick = onDeleteClick?.let { callback ->
                    { callback(totp.toTotpCardUi(account)) }
                },
            )
        }

        if (account.totpCredentials.isEmpty() && account.recoverySets.isNotEmpty() && onOpenAccount != null) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.authenticator_open_account_hint),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun TotpInlineRow(
    totp: TotpCredentialUi,
    onCopyClick: (() -> Unit)?,
    onDeleteClick: (() -> Unit)?,
) {
    val code = totp.currentCode ?: "••••••"
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .then(if (onCopyClick != null) Modifier.clickable(onClick = onCopyClick) else Modifier),
        ) {
            Text(
                text = code,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    R.string.authenticator_code_metadata,
                    totp.algorithm,
                    totp.digits,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CountdownIndicator(
            progressFraction = totp.progressFraction,
            remainingSeconds = totp.remainingSeconds,
            modifier = Modifier.width(72.dp),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (onCopyClick != null) {
                IconButton(onClick = onCopyClick) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.totp_copy_code),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (onDeleteClick != null) {
                IconButton(onClick = onDeleteClick) {
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
private fun ProviderListItem(
    provider: ProviderUi,
    onClick: () -> Unit,
    onRename: ((String) -> Unit)?,
    onAddAccount: ((String) -> Unit)?,
    onDelete: ((String) -> Unit)?,
    onEditIcon: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    RescueAuthRowCard(
        onClick = onClick,
        containerColor = CardTokens.elevatedContainerColor(),
        modifier = Modifier
            .testTag("provider_row_${provider.serviceName}")
            .semantics { role = Role.Button },
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            val brandRes = BrandIcons.effectiveDrawableRes(provider.iconKey, provider.serviceName)
            Box {
                // Icon-only semantics live on the wrapping row; tapping the
                // badge edits the icon instead of entering the provider.
                val badgeModifier = if (onEditIcon != null) {
                    Modifier
                        .testTag("provider_icon_${provider.serviceName}")
                        .clickable(onClick = onEditIcon)
                } else {
                    Modifier
                }
                RescueAuthAutoBadge(
                    label = provider.serviceName,
                    colorSeed = provider.serviceName,
                    iconRes = brandRes,
                    modifier = badgeModifier,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = provider.serviceName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                        stringResource(R.string.provider_accounts_count, provider.accounts.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onRename != null || onAddAccount != null || onDelete != null) {
            androidx.compose.foundation.layout.Box {
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
                            onClick = { menuOpen = false; onRename(provider.serviceName) },
                        )
                    }
                    if (onAddAccount != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.provider_add_account)) },
                            onClick = { menuOpen = false; onAddAccount(provider.serviceName) },
                        )
                    }
                    if (onDelete != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.provider_delete)) },
                            onClick = { menuOpen = false; onDelete(provider.serviceName) },
                        )
                    }
                }
            }
        } else {
            RescueAuthChevron()
        }
    }
}

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
    if (onTogglePin == null && onRename == null && onMove == null && onMerge == null && onDelete == null) {
        return
    }
    androidx.compose.foundation.layout.Box {
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
                            stringResource(if (account.isPinned) R.string.account_unpin else R.string.account_pin),
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
private fun AuthenticatorScreenPreview() {
    RescueAuthTheme {
        AuthenticatorScreen(
            uiState = AuthenticatorUiState(
                loading = false,
                providers = listOf(
                    ProviderUi(
                        id = "provider:GitHub",
                        serviceName = "GitHub",
                        accounts = listOf(
                            AccountUi(
                                id = "a1",
                                providerName = "GitHub",
                                accountName = "alice@example.com",
                                isPinned = true,
                                totpCredentials = listOf(
                                    TotpCredentialUi(
                                        id = "t1",
                                        stableId = "t1",
                                        issuer = "GitHub",
                                        accountName = "alice@example.com",
                                        currentCode = "123 456",
                                        remainingSeconds = 24,
                                        progressFraction = 0.8f,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            onAddClick = {},
            onOpenSearch = {},
        )
    }
}
