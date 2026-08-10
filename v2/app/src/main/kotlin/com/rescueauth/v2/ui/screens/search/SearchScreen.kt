package com.rescueauth.v2.ui.screens.search

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.rescueauth.v2.R
import com.rescueauth.v2.search.SearchResult
import com.rescueauth.v2.ui.search.SearchUiState
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing

object SearchTestTags {
    const val SCREEN_SEARCH = "screen_search"
    const val SEARCH_INPUT = "search_input"
    const val SEARCH_CLEAR = "search_clear"
    const val SEARCH_RESULTS = "search_results"
}

/**
 * P7 Global Search screen — safe metadata only, never shows secrets.
 *
 * Renders a query field (autofocus), a clear button, empty-query / no-results
 * states, and results grouped by type with safe subtitles for context.
 * Tapping a result navigates via the caller-provided [onResultClick].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    uiState: SearchUiState = SearchUiState(),
    onBack: () -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onClearQuery: () -> Unit = {},
    onResultClick: (SearchResult) -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(SearchTestTags.SCREEN_SEARCH),
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.search_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.common_close),
                    )
                }
            },
        )

        OutlinedTextField(
            value = uiState.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md)
                .focusRequester(focusRequester)
                .testTag(SearchTestTags.SEARCH_INPUT)
                .semantics { contentDescription = uiState.query.ifEmpty { "" } },
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                )
            },
            trailingIcon = {
                if (uiState.query.isNotEmpty()) {
                    IconButton(onClick = onClearQuery) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.search_clear),
                            modifier = Modifier.testTag(SearchTestTags.SEARCH_CLEAR),
                        )
                    }
                }
            },
            singleLine = true,
        )

        when {
            uiState.isEmptyQuery -> {
                EmptyQueryState(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                )
            }
            uiState.results.isEmpty() -> {
                NoResultsState(
                    query = uiState.query,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .testTag(SearchTestTags.SEARCH_RESULTS),
                    contentPadding = PaddingValues(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    // Group by type in fixed order, with a type label header.
                    val grouped = uiState.results.groupBy { it.typeLabel }
                    val order = listOf("provider", "account", "totp", "recovery", "developer")
                    val orderedKeys = grouped.keys.sortedBy { order.indexOf(it).let { i -> if (i < 0) order.size else i } }
                    for (label in orderedKeys) {
                        item(key = "header_$label") {
                            Text(
                                text = resultTypeLabel(label),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(vertical = Spacing.xs),
                            )
                        }
                        items(
                            count = grouped.getValue(label).size,
                            key = { i -> label + "_" + grouped.getValue(label)[i].navigationId },
                        ) { i ->
                            SearchResultRow(
                                result = grouped.getValue(label)[i],
                                onClick = { onResultClick(grouped.getValue(label)[i]) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun resultTypeLabel(label: String): String =
    stringResource(
        when (label) {
            "provider" -> R.string.search_type_provider
            "account" -> R.string.search_type_account
            "totp" -> R.string.search_type_totp
            "recovery" -> R.string.search_type_recovery
            "developer" -> R.string.search_type_developer
            else -> R.string.search_type_other
        },
    )

@Composable
private fun SearchResultRow(
    result: SearchResult,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { role = Role.Button },
        onClick = onClick,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                result.subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (result.pinned) {
                Text(
                    text = stringResource(R.string.account_pinned_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun EmptyQueryState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.search_empty_query),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoResultsState(query: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.search_no_results),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = query,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SearchScreenEmptyPreview() {
    RescueAuthTheme {
        SearchScreen(uiState = SearchUiState(query = "", unlocked = true))
    }
}

@Preview(showBackground = true)
@Composable
private fun SearchScreenResultsPreview() {
    RescueAuthTheme {
        SearchScreen(
            uiState = SearchUiState(
                query = "github",
                results = listOf(
                    SearchResult.Account(
                        title = "xincy22",
                        subtitle = "GitHub",
                        navigationId = "a1",
                        pinned = true,
                        providerName = "GitHub",
                    ),
                ),
                unlocked = true,
            ),
        )
    }
}
