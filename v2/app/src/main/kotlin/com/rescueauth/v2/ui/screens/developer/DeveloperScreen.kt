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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.components.DeveloperEntryCard
import com.rescueauth.v2.ui.components.EmptyState
import com.rescueauth.v2.ui.components.SensitiveValueRow
import com.rescueauth.v2.ui.model.DeveloperEntryUi
import com.rescueauth.v2.ui.model.DeveloperPreviewData
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing

/**
 * Developer Vault top-level screen — a **first-class product module** (never
 * hidden inside Settings).
 *
 * Future structure (contract only, no persistence): Android Signing Key / API
 * Credential / SSH Key / Environment Variable Set / Generic Secret.
 *
 * In this foundation the list hosts [DeveloperEntryCard] instances; sensitive
 * fields are rendered through [SensitiveValueRow] (hidden by default). The add
 * action and detail CRUD belong to later vertical slices (Phase 4 P4/P6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperScreen(
    modifier: Modifier = Modifier,
    entries: List<DeveloperEntryUi> = emptyList(),
    onAddClick: (() -> Unit)? = null,
    onEntryClick: ((DeveloperEntryUi) -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.developer_title)) })
        },
        floatingActionButton = {
            if (onAddClick != null) {
                FloatingActionButton(onClick = onAddClick) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.nav_developer),
                    )
                }
            }
        },
    ) { padding ->
        if (entries.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.developer_empty_title),
                body = stringResource(R.string.developer_empty_body),
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(entries, key = { it.id }) { entry ->
                    DeveloperEntryCard(
                        entry = entry,
                        onClick = onEntryClick?.let { { it(entry) } },
                    ) {
                        entry.sensitiveFields.take(2).forEach { field ->
                            SensitiveValueRow(
                                label = field.label,
                                value = field.value,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DeveloperScreenWithDataPreview() {
    RescueAuthTheme {
        DeveloperScreen(
            entries = listOf(
                DeveloperPreviewData.signingKey,
                DeveloperPreviewData.apiCredential,
                DeveloperPreviewData.sshKey,
                DeveloperPreviewData.envVarSet,
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun DeveloperScreenEmptyPreview() {
    RescueAuthTheme {
        DeveloperScreen(entries = emptyList())
    }
}
