package com.rescueauth.v2.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.rescueauth.v2.R
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.SheetTokens
import com.rescueauth.v2.ui.theme.Spacing

/** Shared presentation only: callers retain their own form, selection, authentication and persistence logic. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RescueAuthSheet(
    title: String,
    icon: ImageVector,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    headerModifier: Modifier = Modifier,
    bodyModifier: Modifier = Modifier,
    minimumBodyHeight: Dp = 0.dp,
    dismissEnabled: Boolean = true,
    onBack: (() -> Unit)? = null,
    footer: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val canDismiss by rememberUpdatedState(dismissEnabled)
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || canDismiss })
    val dismiss = rememberSheetDismiss(state, onDismiss)
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * SheetTokens.maxHeightFraction
    val scroll = rememberScrollState()
    LaunchedEffect(title) { scroll.scrollTo(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, modifier = modifier) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        val returnToParent by rememberUpdatedState(onBack)
        DisposableEffect(window, onBack != null) {
            // Material 1.3's modal owns a ComponentDialog dispatcher, separate from the Activity.
            // Register after its default handler; primary-page Back still follows Material dismissal.
            val dialog = window?.callback as? ComponentDialog
            val callback = if (onBack != null && dialog != null) object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { returnToParent?.invoke() }
            } else null
            if (callback != null) dialog!!.onBackPressedDispatcher.addCallback(callback)
            onDispose { callback?.remove() }
        }
        val lightBars = MaterialTheme.colorScheme.background.luminance() > .5f
        SideEffect { window?.let { WindowCompat.getInsetsController(it, view).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        } } }
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding()) {
            Column(bodyModifier.fillMaxWidth().weight(1f, fill = false)
                .heightIn(min = minimumBodyHeight)
                .verticalScroll(scroll)
                .padding(horizontal = ScreenTokens.horizontalPadding)
                .padding(bottom = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(CardTokens.formFieldSpacing)) {
                if (onBack == null) RescueAuthIconHeader(icon, title, subtitle, headerModifier,
                    titleStyle = MaterialTheme.typography.titleLarge)
                else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CardTokens.headerGap)) {
                    IconButton(onClick = onBack, modifier = Modifier.size(CardTokens.headerBadgeSize).testTag("sheet_page_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), Modifier.size(CardTokens.headerIconSize))
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).testTag("sheet_page_title"))
                }
                content()
            }
            if (footer != null) footer(dismiss)
            else Spacer(Modifier.navigationBarsPadding().height(Spacing.sm))
        }
    }
}

/** Secondary pages change draft options only; this action never submits the parent form. */
@Composable
fun RescueAuthSheetPageActions(onBack: () -> Unit) {
    RescueAuthActionBar(stringResource(R.string.common_back), Icons.AutoMirrored.Filled.ArrowBack, onBack,
        stringResource(R.string.sheet_done), Icons.Filled.Check, onBack,
        secondaryTestTag = "sheet_page_back_action", primaryTestTag = "sheet_page_done")
}
