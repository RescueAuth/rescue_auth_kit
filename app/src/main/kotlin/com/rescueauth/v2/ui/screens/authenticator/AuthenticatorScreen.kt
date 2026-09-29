package com.rescueauth.v2.ui.screens.authenticator

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.rescueauth.v2.ui.components.RescueAuthPageScaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.authenticator.AuthenticatorUiState
import com.rescueauth.v2.ui.authenticator.TotpCardUi
import com.rescueauth.v2.ui.components.BrandIcons
import com.rescueauth.v2.ui.components.EmptyState
import com.rescueauth.v2.ui.components.ErrorState
import com.rescueauth.v2.ui.components.LoadingState
import com.rescueauth.v2.ui.components.RescueAuthAutoBadge
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.ui.components.RescueAuthChevron
import com.rescueauth.v2.ui.components.RescueAuthRowCard
import com.rescueauth.v2.ui.components.StudioVaultHero
import com.rescueauth.v2.ui.components.StudioAction
import com.rescueauth.v2.ui.components.RescueAuthIconAction
import com.rescueauth.v2.ui.components.StudioIconCount
import com.rescueauth.v2.ui.components.FloatingAddPosition
import com.rescueauth.v2.ui.components.RescueAuthFloatingAddOverlay
import com.rescueauth.v2.ui.components.rememberFloatingAddPosition
import com.rescueauth.v2.ui.model.AccountUi
import com.rescueauth.v2.ui.model.ProviderUi
import com.rescueauth.v2.ui.model.TotpCredentialUi
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.AddActionTokens
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.Spacing

enum class AccountAddKind { CREDENTIAL, RECOVERY }

/** Modern, task-focused Authenticator home. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthenticatorScreen(
    modifier: Modifier = Modifier,
    uiState: AuthenticatorUiState = AuthenticatorUiState(),
    snackbarHostState: SnackbarHostState? = null,
    onAddClick: (() -> Unit)? = null,
    onAddCredentialToAccount: ((String) -> Unit)? = null,
    onCopyClick: ((TotpCardUi) -> Unit)? = null,
    onDeleteClick: ((TotpCardUi) -> Unit)? = null,
    onOpenAccount: ((String) -> Unit)? = null,
    onOpenProvider: ((String) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onOpenRecovery: ((String) -> Unit)? = null,
    onAddRecovery: ((String) -> Unit)? = null,
    onOpenSearch: (() -> Unit)? = null,
    searchFieldModifier: Modifier = Modifier,
    onTogglePin: ((AccountUi) -> Unit)? = null,
    onAddProviderClick: (() -> Unit)? = null,
    onRenameProvider: ((String) -> Unit)? = null,
    onDeleteProvider: ((String) -> Unit)? = null,
    onAddAccount: ((String) -> Unit)? = null,
    onCreateAccountFor: ((String, AccountAddKind) -> Unit)? = null,
    onRenameAccount: ((AccountUi) -> Unit)? = null,
    onMoveAccount: ((AccountUi) -> Unit)? = null,
    onMergeAccount: ((AccountUi) -> Unit)? = null,
    onDeleteAccount: ((AccountUi) -> Unit)? = null,
    /** Persists a provider icon override (schema v4); null key = AUTO. */
    onSetProviderIcon: ((String, String?) -> Unit)? = null,
    initialProviderName: String? = null,
    initialAccountId: String? = null,
    showAddAction: Boolean = true,
    contentBottomPadding: Dp = 0.dp,
    floatingAddPosition: FloatingAddPosition = rememberFloatingAddPosition(),
) {
    var selectedProviderName by rememberSaveable { mutableStateOf(initialProviderName) }
    var selectedAccountId by rememberSaveable { mutableStateOf(initialAccountId) }
    var pinnedOnly by rememberSaveable { mutableStateOf(false) }
    var addMenuOpen by remember { mutableStateOf(false) }
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
    val isDetailRequested = selectedAccountId != null || selectedProviderName != null
    val selectionExists = if (selectedAccountId != null) selectedAccount != null else selectedProvider != null
    val selectionUnavailable = isDetailRequested && !selectionExists && !uiState.loading && uiState.error == null
    val returnToParent: () -> Unit = {
        if (onBack != null) {
            onBack()
        } else if (selectedAccountId != null) {
            selectedAccountId = null
        } else {
            selectedProviderName = null
        }
    }
    BackHandler(enabled = isDetailRequested && onBack == null, onBack = returnToParent)

    var choosingAccountFor by remember(selectedProviderName, selectedAccountId) { mutableStateOf<AccountAddKind?>(null) }
    val addToAccount: (AccountUi, AccountAddKind) -> Unit = { account, kind ->
        when (kind) {
            AccountAddKind.CREDENTIAL -> if (onAddCredentialToAccount != null) onAddCredentialToAccount(account.id) else onAddClick?.invoke()
            AccountAddKind.RECOVERY -> onAddRecovery?.invoke(account.id)
        }
    }
    val requestAccountAdd: (AccountAddKind) -> Unit = { kind ->
        val account = selectedAccount
        if (account != null) addToAccount(account, kind)
        else selectedProvider?.let { provider ->
            when (provider.accounts.size) {
                0 -> if (onCreateAccountFor != null) onCreateAccountFor(provider.serviceName, kind)
                    else onAddAccount?.invoke(provider.serviceName)
                1 -> addToAccount(provider.accounts.single(), kind)
                else -> choosingAccountFor = kind
            }
        }
    }
    val credentialAction: (() -> Unit)? = if (onAddClick == null && onAddCredentialToAccount == null) null
        else if (isDetailRequested) { { requestAccountAdd(AccountAddKind.CREDENTIAL) } } else onAddClick
    val recoveryAction: (() -> Unit)? = if (isDetailRequested && onAddRecovery != null) { { requestAccountAdd(AccountAddKind.RECOVERY) } } else null
    val accountAction: (() -> Unit)? = if (atProviderLevel && onAddAccount != null) { { onAddAccount(selectedProvider!!.serviceName) } } else null
    val detailActions = listOfNotNull(accountAction, credentialAction, recoveryAction)
    val hasDetailAdd = showAddAction && isDetailRequested && selectionExists && detailActions.isNotEmpty()
    val contentClearance = if (hasDetailAdd) maxOf(contentBottomPadding, AddActionTokens.contentClearance) else contentBottomPadding
    LaunchedEffect(isDetailRequested, selectionExists, uiState.loading) {
        if (isDetailRequested && !selectionExists && !uiState.loading) {
            addMenuOpen = false
            choosingAccountFor = null
        }
    }

    Box(modifier.fillMaxSize()) {
        RescueAuthPageScaffold(
            modifier = Modifier.fillMaxSize(),
            titleMaxLines = if (isDetailRequested) 2 else null,
            title = if (selectionUnavailable) {
                stringResource(R.string.vault_item_unavailable_title)
            } else if (atAccountLevel) {
                selectedAccount?.accountName.orEmpty()
            } else if (atProviderLevel) {
                selectedProvider?.serviceName.orEmpty()
            } else {
                stringResource(R.string.authenticator_title)
            },
            navigationIcon = if (isDetailRequested) {
                {
                    IconButton(onClick = returnToParent, modifier = Modifier.testTag("authenticator_back")) {
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
                if (atAccountLevel && !uiState.loading) {
                    AccountActionsMenu(selectedAccount!!, onTogglePin, onRenameAccount, onMoveAccount, onMergeAccount, onDeleteAccount)
                }
                if (showAddAction && !isDetailRequested && onAddClick != null && !uiState.loading) {
                    RescueAuthIconAction(
                        icon = Icons.Filled.Add,
                        label = stringResource(R.string.studio_add),
                        onClick = { if (!atAccountLevel && !atProviderLevel) addMenuOpen = true else onAddClick() },
                        modifier = Modifier.testTag("vault_add_menu"),
                    )
                }
            },
            snackbarHost = { snackbarHostState?.let { SnackbarHost(it, Modifier.padding(bottom = contentClearance)) } },
        ) { padding ->
            when {
                uiState.loading -> LoadingState(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    label = stringResource(R.string.authenticator_loading),
                )
                uiState.error != null -> ErrorState(
                    title = stringResource(R.string.common_error_title),
                    message = uiState.error,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
                selectionUnavailable -> EmptyState(
                    title = stringResource(R.string.vault_item_unavailable_title),
                    body = stringResource(R.string.vault_item_unavailable_body),
                    actionLabel = stringResource(R.string.a11y_back),
                    onAction = returnToParent,
                    modifier = Modifier.fillMaxSize().padding(padding).testTag("vault_item_unavailable"),
                )
                atAccountLevel -> AccountDetailContent(
                    account = selectedAccount!!,
                    onCopyClick = onCopyClick,
                    onDeleteClick = onDeleteClick,
                    onOpenRecovery = onOpenRecovery,
                    contentBottomPadding = contentClearance,
                    modifier = Modifier.padding(padding),
                )
                atProviderLevel -> ProviderAccountsContent(
                    provider = selectedProvider!!,
                    onOpenAccount = { accountId ->
                        if (onOpenAccount != null) onOpenAccount(accountId) else selectedAccountId = accountId
                    },
                    contentBottomPadding = contentClearance,
                    modifier = Modifier.padding(padding),
                )
                else -> {
                    val homeProviders = if (pinnedOnly) {
                        displayProviders.filter { provider -> provider.accounts.any { it.isPinned } }
                    } else displayProviders
                    ProviderHomeContent(
                    providers = homeProviders,
                    onAddClick = onAddClick,
                    onOpenSearch = onOpenSearch,
                    searchFieldModifier = searchFieldModifier,
                    accountCount = displayProviders.sumOf { it.accounts.size },
                    hasPinnedAccounts = displayProviders.any { provider -> provider.accounts.any { it.isPinned } },
                    pinnedOnly = pinnedOnly,
                    onPinnedOnlyChange = { pinnedOnly = it },
                    onProviderClick = { providerName ->
                        if (onOpenProvider != null) onOpenProvider(providerName) else selectedProviderName = providerName
                    },
                    onRenameProvider = onRenameProvider,
                    onAddAccount = onAddAccount,
                    onDeleteProvider = onDeleteProvider,
                    contentBottomPadding = contentBottomPadding,
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
        if (hasDetailAdd) RescueAuthFloatingAddOverlay(
            visible = !uiState.loading && uiState.error == null,
            enabled = !uiState.loading && uiState.error == null && !addMenuOpen && choosingAccountFor == null,
            position = floatingAddPosition,
            onClick = { if (detailActions.size == 1) detailActions.single()() else addMenuOpen = true },
            modifier = Modifier.navigationBarsPadding(),
        )
    }

    if (addMenuOpen && (!isDetailRequested || selectionExists)) {
        AuthenticatorAddSheet(
            onDismiss = { addMenuOpen = false },
            onAddCredential = credentialAction,
            onAddProvider = if (!isDetailRequested) onAddProviderClick else null,
            onAddAccount = accountAction,
            onAddRecovery = recoveryAction,
            contextName = if (atAccountLevel) selectedAccount!!.accountName else if (atProviderLevel) selectedProvider!!.serviceName else null,
        )
    }
    val requestedKind = choosingAccountFor
    if (requestedKind != null && atProviderLevel && selectionExists) {
        AccountAddTargetSheet(selectedProvider!!.serviceName, selectedProvider.accounts,
            onDismiss = { choosingAccountFor = null },
            onCreateAccount = onCreateAccountFor?.let { create -> { create(selectedProvider.serviceName, requestedKind) } },
            onSelect = { id -> selectedProvider.accounts.firstOrNull { it.id == id }?.let { addToAccount(it, requestedKind) } })
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
    onAddClick: (() -> Unit)?,
    onOpenSearch: (() -> Unit)?,
    searchFieldModifier: Modifier,
    accountCount: Int,
    hasPinnedAccounts: Boolean,
    pinnedOnly: Boolean,
    onPinnedOnlyChange: (Boolean) -> Unit,
    onProviderClick: (String) -> Unit,
    onRenameProvider: ((String) -> Unit)?,
    onAddAccount: ((String) -> Unit)?,
    onDeleteProvider: ((String) -> Unit)?,
    onEditIcon: ((ProviderUi) -> Unit)?,
    contentBottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("provider_directory"),
        contentPadding = PaddingValues(
            start = ScreenTokens.horizontalPadding,
            end = ScreenTokens.horizontalPadding,
            top = Spacing.xs,
            bottom = Spacing.xxl + contentBottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(CardTokens.listSpacing),
    ) {
        item(key = "vault-overview") {
            StudioVaultHero(accountCount = accountCount)
        }
        if (onOpenSearch != null) item(key = "search-entry") {
            RescueAuthRowCard(onClick = onOpenSearch, modifier = Modifier.padding(top = Spacing.xs).then(searchFieldModifier).testTag("home_search_entry")) {
                Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.studio_search_prompt), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
        }
        if (providers.isEmpty()) item(key = "empty-directory") {
            RescueAuthCard(modifier = Modifier.padding(top = Spacing.sm)) {
                Text(stringResource(R.string.authenticator_empty_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.authenticator_empty_body), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
                if (onAddClick != null) StudioAction(stringResource(R.string.authenticator_add_first), onAddClick,
                    Modifier.padding(top = Spacing.lg))
            }
        }
        if (hasPinnedAccounts) item(key = "providers-filter") {
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
                    leadingIcon = { Icon(Icons.Filled.PushPin, null, Modifier.size(16.dp)) },
                )
            }
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
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    StudioIconCount(Icons.Outlined.PeopleOutline, provider.accounts.size,
                        stringResource(R.string.provider_accounts_count, provider.accounts.size))
                    if (pinnedCount > 0) StudioIconCount(Icons.Filled.PushPin, pinnedCount,
                        stringResource(R.string.compact_pinned_count, pinnedCount))
                }
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
