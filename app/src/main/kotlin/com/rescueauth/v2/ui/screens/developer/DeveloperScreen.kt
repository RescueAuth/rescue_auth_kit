package com.rescueauth.v2.ui.screens.developer

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.components.DeveloperEntryCard
import com.rescueauth.v2.ui.components.EmptyState
import com.rescueauth.v2.ui.components.ErrorState
import com.rescueauth.v2.ui.components.LoadingState
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.ui.components.RescueAuthIconBadge
import com.rescueauth.v2.ui.components.RescueAuthPageHeader
import com.rescueauth.v2.ui.components.RescueAuthRowCard
import com.rescueauth.v2.ui.components.RescueAuthSectionHeader
import com.rescueauth.v2.ui.components.RescueAuthChevron
import com.rescueauth.v2.ui.components.icon
import com.rescueauth.v2.ui.developer.DeveloperListUiState
import com.rescueauth.v2.ui.model.DeveloperEntryType
import com.rescueauth.v2.ui.model.DeveloperEntryUi
import com.rescueauth.v2.ui.model.DeveloperPreviewData
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.Spacing

/** Modern Developer Vault list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperScreen(
    modifier: Modifier = Modifier,
    uiState: DeveloperListUiState = DeveloperListUiState(),
    snackbarHostState: SnackbarHostState? = null,
    onAddClick: (() -> Unit)? = null,
    onEntryClick: ((DeveloperEntryUi) -> Unit)? = null,
    onOpenCategory: ((DeveloperEntryType) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    categoryType: DeveloperEntryType? = null,
) {
    var localCategory by remember { mutableStateOf(categoryType) }
    val selectedCategory = localCategory
    val visibleEntries = selectedCategory?.let { type -> uiState.entries.filter { it.type == type } }
        ?: uiState.entries
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            RescueAuthPageHeader(
                title = selectedCategory?.let { typeLabel(it) }
                    ?: stringResource(R.string.developer_title),
                subtitle = selectedCategory?.let {
                    stringResource(R.string.developer_group_count, visibleEntries.size)
                } ?: stringResource(R.string.developer_subtitle),
                navigationIcon = if (selectedCategory != null) {
                    {
                        androidx.compose.material3.IconButton(
                            onClick = onBack ?: { localCategory = null },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.a11y_back),
                            )
                        }
                    }
                } else null,
            )
        },
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
        floatingActionButton = if (onAddClick != null) {
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
                        contentDescription = stringResource(R.string.developer_add),
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
                label = stringResource(R.string.developer_loading),
            )
            uiState.error != null -> ErrorState(
                title = stringResource(R.string.common_error_title),
                message = uiState.error,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            selectedCategory != null && visibleEntries.isEmpty() -> EmptyState(
                title = selectedCategory?.let { typeLabel(it) }
                    ?: stringResource(R.string.developer_empty_title),
                body = stringResource(R.string.developer_empty_body),
                actionLabel = onAddClick?.let { stringResource(R.string.developer_add_first) },
                onAction = onAddClick,
                icon = Icons.Filled.Build,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            selectedCategory == null -> DeveloperDirectoryContent(
                entries = uiState.entries,
                onOpenCategory = { type ->
                    if (onOpenCategory != null) onOpenCategory(type) else localCategory = type
                },
                onAddClick = onAddClick,
                modifier = Modifier.padding(padding),
            )
            else -> DeveloperCategoryContent(
                entries = visibleEntries,
                onEntryClick = onEntryClick,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun DeveloperDirectoryContent(
    entries: List<DeveloperEntryUi>,
    onOpenCategory: (DeveloperEntryType) -> Unit,
    onAddClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val groups = entries.groupingBy { it.type }.eachCount()
    val orderedTypes = listOf(
        DeveloperEntryType.API_CREDENTIAL,
        DeveloperEntryType.SSH_KEY,
        DeveloperEntryType.ANDROID_SIGNING_KEY,
        DeveloperEntryType.ENVIRONMENT_VARIABLE_SET,
        DeveloperEntryType.GENERIC_SECRET,
    )
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
        item(key = "developer-summary") {
            RescueAuthCard(containerColor = MaterialTheme.colorScheme.surface) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RescueAuthIconBadge(
                        icon = Icons.Filled.Build,
                        size = 44.dp,
                        iconSize = 22.dp,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Column(modifier = Modifier.padding(start = Spacing.sm)) {
                        Text(
                            text = stringResource(R.string.developer_overview_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.developer_overview_count, entries.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.xxs),
                        )
                        if (entries.isEmpty()) {
                            Text(
                                text = stringResource(R.string.developer_empty_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Spacing.xs),
                            )
                        }
                        if (entries.isEmpty() && onAddClick != null) {
                            com.rescueauth.v2.ui.components.RescueAuthButton(
                                onClick = onAddClick,
                                modifier = Modifier.padding(top = Spacing.sm),
                            ) {
                                Text(stringResource(R.string.developer_add_first))
                            }
                        }
                    }
                }
            }
        }
        items(orderedTypes, key = { it.name }) { type ->
            RescueAuthRowCard(
                onClick = { onOpenCategory(type) },
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.testTag("developer_category_${type.name}"),
            ) {
                RescueAuthIconBadge(icon = type.icon(), size = 36.dp, iconSize = 18.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = typeLabel(type),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.developer_group_count, groups[type] ?: 0),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RescueAuthChevron()
            }
        }
    }
}

@Composable
private fun DeveloperCategoryContent(
    entries: List<DeveloperEntryUi>,
    onEntryClick: ((DeveloperEntryUi) -> Unit)?,
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
        item(key = "category-summary") {
            RescueAuthSectionHeader(
                title = typeLabel(entries.first().type),
                subtitle = stringResource(R.string.developer_group_count, entries.size),
            )
        }
        items(entries, key = { it.stableId }) { entry ->
            DeveloperEntryCard(
                entry = entry,
                onClick = onEntryClick?.let { callback -> { callback(entry) } },
            )
        }
    }
}

@Composable
private fun DeveloperContent(
    entries: List<DeveloperEntryUi>,
    onEntryClick: ((DeveloperEntryUi) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val groups = entries.groupBy { it.type }
    // Show one useful group immediately, while keeping a large vault calm on
    // first render. Every other category remains one tap away.
    var expandedTypes by remember { mutableStateOf(groups.keys.firstOrNull()?.let(::setOf).orEmpty()) }
    val orderedTypes = listOf(
        DeveloperEntryType.API_CREDENTIAL,
        DeveloperEntryType.SSH_KEY,
        DeveloperEntryType.ANDROID_SIGNING_KEY,
        DeveloperEntryType.ENVIRONMENT_VARIABLE_SET,
        DeveloperEntryType.GENERIC_SECRET,
    )
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
        item(key = "developer-summary") {
            RescueAuthCard(containerColor = MaterialTheme.colorScheme.surface) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    RescueAuthIconBadge(
                        icon = Icons.Filled.Build,
                        size = 44.dp,
                        iconSize = 22.dp,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.padding(start = Spacing.sm),
                    ) {
                        Text(
                            text = stringResource(R.string.developer_overview_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.developer_overview_count, entries.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = Spacing.xxs),
                        )
                    }
                }
            }
        }
        orderedTypes.forEach { type ->
            val typeEntries = groups[type].orEmpty()
            if (typeEntries.isNotEmpty()) {
                item(key = "header-$type") {
                    RescueAuthRowCard(
                        onClick = {
                            expandedTypes = if (type in expandedTypes) {
                                expandedTypes - type
                            } else {
                                expandedTypes + type
                            }
                        },
                        modifier = Modifier
                            .padding(top = Spacing.md)
                            .testTag("developer_group_${type.name}"),
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        Text(
                            text = typeLabel(type),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.developer_group_count, typeEntries.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            imageVector = if (type in expandedTypes) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (type in expandedTypes) {
                    items(typeEntries, key = { it.stableId }) { entry ->
                        DeveloperEntryCard(
                            entry = entry,
                            onClick = onEntryClick?.let { callback -> { callback(entry) } },
                        )
                    }
                }
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
private fun DeveloperScreenPreview() {
    RescueAuthTheme {
        DeveloperScreen(
            uiState = DeveloperListUiState(
                loading = false,
                entries = listOf(
                    DeveloperPreviewData.apiCredential,
                    DeveloperPreviewData.sshKey,
                    DeveloperPreviewData.envVarSet,
                ),
            ),
            onAddClick = {},
        )
    }
}
