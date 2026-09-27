package com.rescueauth.v2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.Spacing

/** A visible per-action authentication hint; expanding it never starts or grants authentication. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtectedFieldsCard(entryId: String, content: @Composable () -> Unit) {
    var showExplanation by remember(entryId) { mutableStateOf(false) }
    // Resolve in the page locale before ModalBottomSheet creates its separate window context.
    val hint = stringResource(R.string.developer_protection_hint)
    val title = stringResource(R.string.developer_protection_title)
    val explanation = stringResource(R.string.developer_protection_explanation)
    val close = stringResource(R.string.developer_protection_dismiss)
    RescueAuthCard(contentPadding = CardTokens.noPadding) {
        Surface(
            onClick = { showExplanation = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = ScreenTokens.controlMinHeight)
                .testTag("developer_protection_info").semantics { contentDescription = title },
            color = CardTokens.protectedHeaderColor(),
        ) {
            Row(Modifier.padding(horizontal = CardTokens.contentPadding, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Icon(Icons.Outlined.VerifiedUser, null, Modifier.size(CardTokens.actionIconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(hint, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Outlined.Info, null, Modifier.size(CardTokens.actionIconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.padding(CardTokens.contentPadding)) { content() }
    }
    if (showExplanation) {
        ModalBottomSheet(
            onDismissRequest = { showExplanation = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            // Material 3 1.3 hosts the sheet in its own window; mirror the app's
            // selected theme here instead of inheriting the system's light icons policy.
            val view = LocalView.current
            val window = (view.parent as? DialogWindowProvider)?.window
            val lightBars = MaterialTheme.colorScheme.background.luminance() > 0.5f
            SideEffect {
                window?.let {
                    WindowCompat.getInsetsController(it, view).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
            }
            Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenTokens.horizontalPadding, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(CardTokens.actionSpacing)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    RescueAuthIconBadge(Icons.Outlined.VerifiedUser)
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                }
                RescueAuthCard {
                    Text(explanation, Modifier.testTag("developer_protection_explanation"),
                        style = MaterialTheme.typography.bodyMedium)
                }
                RescueAuthButton(onClick = { showExplanation = false },
                    modifier = Modifier.fillMaxWidth().testTag("developer_protection_close")) {
                    Text(close)
                }
            }
        }
    }
}
