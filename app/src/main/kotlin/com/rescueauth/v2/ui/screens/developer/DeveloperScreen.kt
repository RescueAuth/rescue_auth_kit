package com.rescueauth.v2.ui.screens.developer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.components.*
import com.rescueauth.v2.ui.developer.DeveloperListUiState
import com.rescueauth.v2.ui.model.*
import com.rescueauth.v2.ui.theme.*

/** Categories are a visual directory; secret-bearing entries are never expanded here. */
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
    var selectedCategory by rememberSaveable { mutableStateOf(categoryType) }
    val visibleEntries = selectedCategory?.let { type -> uiState.entries.filter { it.type == type } } ?: uiState.entries
    val back = { if (onBack != null) onBack() else selectedCategory = null }
    BackHandler(enabled = selectedCategory != null && onBack == null) { selectedCategory = null }
    RescueAuthPageScaffold(
        modifier = modifier.fillMaxSize(),
        title = selectedCategory?.let { typeLabel(it) } ?: stringResource(R.string.developer_title),
        navigationIcon = if (selectedCategory != null) { { RescueAuthBackButton(back) } } else null,
        actions = {
            if (selectedCategory != null) StudioCountBadge(visibleEntries.size,
                stringResource(R.string.developer_group_count, visibleEntries.size))
            if (onAddClick != null) RescueAuthIconAction(Icons.Filled.Add,
                stringResource(R.string.studio_add), onAddClick)
        },
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
    ) { padding ->
        when {
            uiState.loading -> LoadingState(Modifier.fillMaxSize().padding(padding), stringResource(R.string.developer_loading))
            uiState.error != null -> ErrorState(title = stringResource(R.string.common_error_title),
                message = uiState.error, modifier = Modifier.fillMaxSize().padding(padding))
            selectedCategory == null -> DeveloperDirectoryContent(uiState.entries,
                onOpenCategory = { if (onOpenCategory != null) onOpenCategory(it) else selectedCategory = it },
                modifier = Modifier.padding(padding))
            visibleEntries.isEmpty() -> EmptyState(
                title = typeLabel(selectedCategory!!), body = stringResource(R.string.studio_vault_empty_body),
                actionLabel = onAddClick?.let { stringResource(R.string.developer_add_first) }, onAction = onAddClick,
                icon = Icons.Outlined.Code, modifier = Modifier.fillMaxSize().padding(padding))
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = ScreenTokens.horizontalPadding, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(CardTokens.listSpacing),
            ) {
                items(visibleEntries, key = { it.stableId }) { entry ->
                    DeveloperEntryCard(entry, onClick = onEntryClick?.let { cb -> { cb(entry) } })
                }
            }
        }
    }
}

@Composable
private fun DeveloperDirectoryContent(
    entries: List<DeveloperEntryUi>,
    onOpenCategory: (DeveloperEntryType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = entries.groupingBy { it.type }.eachCount()
    val types = listOf(DeveloperEntryType.API_CREDENTIAL, DeveloperEntryType.SSH_KEY,
        DeveloperEntryType.ANDROID_SIGNING_KEY, DeveloperEntryType.ENVIRONMENT_VARIABLE_SET, DeveloperEntryType.GENERIC_SECRET)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxSize()) {
        val columns = if (maxWidth < 340.dp || fontScale > 1.3f) 1 else 2
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(start = ScreenTokens.horizontalPadding, end = ScreenTokens.horizontalPadding,
                top = Spacing.xs, bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(CardTokens.actionSpacing),
            horizontalArrangement = Arrangement.spacedBy(CardTokens.actionSpacing),
        ) {
            item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
                StudioVaultHero()
            }
            items(types, key = { it.name }, span = { GridItemSpan(if (it == DeveloperEntryType.GENERIC_SECRET) maxLineSpan else 1) }) { type ->
                val fill = if (dark) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer
                val foreground = if (dark) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer
                if (type == DeveloperEntryType.GENERIC_SECRET) {
                    RescueAuthRowCard(onClick = { onOpenCategory(type) }, containerColor = fill,
                        modifier = Modifier.testTag("developer_category_${type.name}")) {
                        Icon(type.icon(), null, Modifier.size(26.dp), tint = foreground)
                        Text(typeLabel(type), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        StudioCountBadge(groups[type] ?: 0, stringResource(R.string.developer_group_count, groups[type] ?: 0))
                        RescueAuthChevron()
                    }
                } else RescueAuthCard(onClick = { onOpenCategory(type) }, containerColor = fill,
                    modifier = Modifier.testTag("developer_category_${type.name}")) {
                    Column(Modifier.fillMaxWidth().heightIn(min = 108.dp),
                        verticalArrangement = Arrangement.SpaceBetween) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(type.icon(), null, Modifier.size(26.dp), tint = foreground)
                            StudioCountBadge(groups[type] ?: 0, stringResource(R.string.developer_group_count, groups[type] ?: 0))
                        }
                        Column(Modifier.padding(top = Spacing.md)) {
                            Text(typeLabel(type), style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun typeLabel(type: DeveloperEntryType): String = stringResource(when (type) {
    DeveloperEntryType.API_CREDENTIAL -> R.string.studio_api
    DeveloperEntryType.SSH_KEY -> R.string.studio_ssh
    DeveloperEntryType.GENERIC_SECRET -> R.string.studio_generic
    DeveloperEntryType.ANDROID_SIGNING_KEY -> R.string.studio_signing
    DeveloperEntryType.ENVIRONMENT_VARIABLE_SET -> R.string.studio_environment
})

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
