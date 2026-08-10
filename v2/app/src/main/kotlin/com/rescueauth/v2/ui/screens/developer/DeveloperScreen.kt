package com.rescueauth.v2.ui.screens.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.components.DeveloperEntryCard
import com.rescueauth.v2.ui.components.EmptyState
import com.rescueauth.v2.ui.components.LoadingState
import com.rescueauth.v2.ui.developer.DeveloperListUiState
import com.rescueauth.v2.ui.model.DeveloperEntryType
import com.rescueauth.v2.ui.model.DeveloperEntryUi
import com.rescueauth.v2.ui.model.DeveloperPreviewData
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing

/**
 * Developer Vault top-level screen (Phase 4 P4) — a first-class product module.
 *
 * Renders the real production Developer list (metadata only, never secrets):
 * type grouping/filter, entry title + non-secret metadata, and an Add action
 * that offers the three P4 types (API Credential / SSH Key / Generic Secret).
 * Android Signing Key / Env Var Set are NOT shown as clickable fake features —
 * they are P6 (Issue #20 §19, §31).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperScreen(
    modifier: Modifier = Modifier,
    uiState: DeveloperListUiState = DeveloperListUiState(),
    snackbarHostState: SnackbarHostState? = null,
    onAddClick: (() -> Unit)? = null,
    onEntryClick: ((DeveloperEntryUi) -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.developer_title)) })
        },
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
        floatingActionButton = {
            if (onAddClick != null) {
                FloatingActionButton(onClick = onAddClick) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.developer_add),
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
                    title = stringResource(R.string.developer_empty_title),
                    body = stringResource(R.string.developer_empty_body),
                    modifier = Modifier.padding(padding),
                )
            }
            else -> {
                val supported = uiState.entries.filter {
                    it.type == DeveloperEntryType.API_CREDENTIAL ||
                        it.type == DeveloperEntryType.SSH_KEY ||
                        it.type == DeveloperEntryType.GENERIC_SECRET ||
                        it.type == DeveloperEntryType.ANDROID_SIGNING_KEY ||
                        it.type == DeveloperEntryType.ENVIRONMENT_VARIABLE_SET
                }
                DeveloperEntryList(
                    entries = supported,
                    onEntryClick = onEntryClick,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun DeveloperEntryList(
    entries: List<DeveloperEntryUi>,
    onEntryClick: ((DeveloperEntryUi) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // Group by type (simple grouping/filter per Issue #20 §19).
        val groups = entries.groupBy { it.type }
        groups.forEach { (type, typeEntries) ->
            item(key = "header-$type") {
                Text(
                    text = typeLabel(type),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Spacing.xs),
                )
            }
            items(typeEntries, key = { it.stableId }) { entry ->
                DeveloperEntryCard(
                    entry = entry,
                    onClick = onEntryClick?.let { { it(entry) } },
                )
            }
        }
    }
}

@Composable
private fun typeLabel(type: DeveloperEntryType): String = when (type) {
    DeveloperEntryType.API_CREDENTIAL -> stringResource(R.string.developer_type_api_credential)
    DeveloperEntryType.SSH_KEY -> stringResource(R.string.developer_type_ssh_key)
    DeveloperEntryType.GENERIC_SECRET -> stringResource(R.string.developer_type_generic)
    DeveloperEntryType.ANDROID_SIGNING_KEY -> stringResource(R.string.developer_type_signing_key)
    DeveloperEntryType.ENVIRONMENT_VARIABLE_SET -> stringResource(R.string.developer_type_env_var)
}

@Preview(showBackground = true)
@Composable
private fun DeveloperScreenWithDataPreview() {
    RescueAuthTheme {
        DeveloperScreen(
            uiState = DeveloperListUiState(
                loading = false,
                entries = listOf(
                    DeveloperPreviewData.apiCredential,
                    DeveloperPreviewData.sshKey,
                    DeveloperPreviewData.generic,
                ),
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DeveloperScreenEmptyPreview() {
    RescueAuthTheme {
        DeveloperScreen(uiState = DeveloperListUiState(loading = false))
    }
}
