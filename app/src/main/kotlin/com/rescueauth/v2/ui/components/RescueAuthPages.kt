package com.rescueauth.v2.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.Spacing

/** Common chrome for normal pages. Content remains caller-owned (lazy lists, grids or forms). */
@Composable
fun RescueAuthPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    titleMaxLines: Int? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        // Header and footer own their respective system insets; the app shell adds neither twice.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { RescueAuthPageHeader(title, subtitle, navigationIcon = navigationIcon, actions = actions, titleMaxLines = titleMaxLines) },
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        content = content,
    )
}

/** Shared task/form page: independently scrollable cards above a caller-provided fixed footer. */
@Composable
fun RescueAuthFormPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    backEnabled: Boolean = true,
    titleMaxLines: Int? = null,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    RescueAuthPageScaffold(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        navigationIcon = { RescueAuthBackButton(onBack, enabled = backEnabled) },
        titleMaxLines = titleMaxLines,
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .imePadding().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenTokens.horizontalPadding, vertical = ScreenTokens.verticalPadding),
            verticalArrangement = Arrangement.spacedBy(CardTokens.actionSpacing),
            content = content,
        )
    }
}

/** Footer placement shared by paired actions and single-action workflow steps. */
@Composable
fun RescueAuthBottomBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
        .imePadding().navigationBarsPadding()
        .padding(horizontal = ScreenTokens.horizontalPadding, vertical = Spacing.xs)) {
        content()
    }
}
