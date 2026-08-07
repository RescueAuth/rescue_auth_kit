package com.rescueauth.v2.ui.screens.authenticator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.components.EmptyState
import com.rescueauth.v2.ui.components.ProviderAccountListItem
import com.rescueauth.v2.ui.components.TotpCard
import com.rescueauth.v2.ui.model.AccountUi
import com.rescueauth.v2.ui.model.ProviderUi
import com.rescueauth.v2.ui.model.TotpCredentialUi
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing

/**
 * Authenticator top-level screen.
 *
 * Future structure (contract only, no persistence):
 * Provider → Account → TOTP / Recovery Codes.
 * This foundation shows provider/account groups with TOTP cards; the add
 * action and detail navigation belong to later vertical slices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthenticatorScreen(
    modifier: Modifier = Modifier,
    providers: List<ProviderUi> = emptyList(),
    onAddClick: (() -> Unit)? = null,
    onAccountClick: ((AccountUi) -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.authenticator_title)) })
        },
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
        if (providers.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.authenticator_empty_title),
                body = stringResource(R.string.authenticator_empty_body),
                modifier = Modifier.padding(padding),
            )
        } else {
            // Flatten providers -> accounts -> totps into ordered list entries so
            // the LazyColumn DSL can call item()/items() from a composable scope.
            val entries = providers.flatMap { provider ->
                provider.accounts.flatMap { account ->
                    val accountEntry = AuthenticatorListEntry.Account(account)
                    val totpEntries = account.totpCredentials.map { AuthenticatorListEntry.Totp(it) }
                    listOf(accountEntry) + totpEntries
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                entries.forEach { entry ->
                    when (entry) {
                        is AuthenticatorListEntry.Account -> item(key = "account-${entry.account.id}") {
                            ProviderAccountListItem(
                                account = entry.account,
                                onClick = onAccountClick?.let { { it(entry.account) } },
                            )
                        }
                        is AuthenticatorListEntry.Totp -> item(key = "totp-${entry.totp.id}") {
                            TotpCard(credential = entry.totp)
                        }
                    }
                }
            }
        }
    }
}

/** Internal list entry type (avoids composing inside a non-composable forEach). */
private sealed interface AuthenticatorListEntry {
    data class Account(val account: AccountUi) : AuthenticatorListEntry
    data class Totp(val totp: TotpCredentialUi) : AuthenticatorListEntry
}

/** Preview fixture — never injected into production flows. */
private val previewProviders = listOf(
    ProviderUi(
        id = "p1",
        serviceName = "GitHub",
        accounts = listOf(
            AccountUi(
                id = "a1",
                providerName = "GitHub",
                accountName = "alice@example.com",
                totpCredentials = listOf(
                    TotpCredentialUi(
                        id = "t1",
                        issuer = "GitHub",
                        accountName = "alice@example.com",
                        currentCode = "123 456",
                    ),
                ),
            ),
        ),
    ),
)

@Preview(showBackground = true)
@Composable
private fun AuthenticatorScreenWithDataPreview() {
    RescueAuthTheme {
        AuthenticatorScreen(providers = previewProviders)
    }
}

@Preview(showBackground = true)
@Composable
private fun AuthenticatorScreenEmptyPreview() {
    RescueAuthTheme {
        AuthenticatorScreen(providers = emptyList())
    }
}
