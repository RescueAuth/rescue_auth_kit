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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.authenticator.AuthenticatorUiState
import com.rescueauth.v2.ui.authenticator.TotpCardUi
import com.rescueauth.v2.ui.components.EmptyState
import com.rescueauth.v2.ui.components.LoadingState
import com.rescueauth.v2.ui.components.TotpCard
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing

/**
 * Authenticator top-level screen — production data path (Phase 4 P1).
 *
 * Renders the real TOTP list ([uiState]) with live codes + countdown, an
 * EmptyState for an empty vault and a FAB that opens the Add TOTP flow (owned
 * by the caller/Route). Composable never touches Room entities; it only
 * consumes UI models and callbacks.
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
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.authenticator_title)) })
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
        )
    }
}
