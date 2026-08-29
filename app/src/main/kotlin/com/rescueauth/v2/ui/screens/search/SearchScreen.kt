package com.rescueauth.v2.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.search.SearchResult
import com.rescueauth.v2.ui.components.EmptyState
import com.rescueauth.v2.ui.components.RescueAuthIconBadge
import com.rescueauth.v2.ui.components.RescueAuthPageHeader
import com.rescueauth.v2.ui.components.RescueAuthRowCard
import com.rescueauth.v2.ui.components.RescueAuthSectionHeader
import com.rescueauth.v2.ui.search.SearchUiState
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.Spacing

object SearchTestTags {
    const val SCREEN_SEARCH = "screen_search"
    const val SEARCH_INPUT = "search_input"
    const val SEARCH_CLEAR = "search_clear"
    const val SEARCH_RESULTS = "search_results"
}

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
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag(SearchTestTags.SCREEN_SEARCH),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            RescueAuthPageHeader(
                title = stringResource(R.string.search_title),
                subtitle = stringResource(R.string.search_subtitle),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.common_close),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = ScreenTokens.horizontalPadding,
                        vertical = Spacing.sm,
                    )
                    .focusRequester(focusRequester)
                    .testTag(SearchTestTags.SEARCH_INPUT)
                    .semantics { contentDescription = uiState.query },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.query.isNotEmpty()) {
                        IconButton(
                            onClick = onClearQuery,
                            modifier = Modifier.testTag(SearchTestTags.SEARCH_CLEAR),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.search_clear),
                            )
                        }
                    }
                },
                singleLine = true,
            )
            when {
                uiState.isEmptyQuery -> EmptyState(
                    title = stringResource(R.string.search_empty_query),
                    body = stringResource(R.string.search_safe_hint),
                    icon = Icons.Filled.Search,
                    modifier = Modifier.fillMaxSize(),
                )
                uiState.results.isEmpty() -> EmptyState(
                    title = stringResource(R.string.search_no_results),
                    body = stringResource(R.string.search_no_results_hint),
                    icon = Icons.Filled.Search,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> SearchResults(
                    results = uiState.results,
                    onResultClick = onResultClick,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(SearchTestTags.SEARCH_RESULTS),
                )
            }
        }
    }
}

@Composable
private fun SearchResults(
    results: List<SearchResult>,
    onResultClick: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val order = listOf("provider", "account", "totp", "recovery", "developer")
    val grouped = results.groupBy { it.typeLabel }
    val orderedKeys = grouped.keys.sortedBy { key ->
        order.indexOf(key).let { index -> if (index < 0) order.size else index }
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = ScreenTokens.horizontalPadding,
            end = ScreenTokens.horizontalPadding,
            bottom = Spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        orderedKeys.forEach { label ->
            item(key = "header_$label") {
                RescueAuthSectionHeader(
                    title = resultTypeLabel(label),
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
            items(
                items = grouped.getValue(label),
                key = { result -> "$label:${result.navigationId}" },
            ) { result ->
                SearchResultRow(result = result, onClick = { onResultClick(result) })
            }
        }
    }
}

@Composable
private fun resultTypeLabel(label: String): String = stringResource(
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
private fun SearchResultRow(result: SearchResult, onClick: () -> Unit) {
    RescueAuthRowCard(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.semantics { role = Role.Button },
        verticalPadding = Spacing.sm,
    ) {
        RescueAuthIconBadge(
            icon = Icons.Filled.Search,
            size = 36.dp,
            iconSize = 18.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            result.subtitle?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
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

@Preview(showBackground = true)
@Composable
private fun SearchScreenPreview() {
    RescueAuthTheme {
        SearchScreen(uiState = SearchUiState(query = "git"))
    }
}
