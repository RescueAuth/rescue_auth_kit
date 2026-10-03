package com.rescueauth.v2.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.Spacing

/** Display-only help. Opening it cannot submit a form or grant authentication. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RescueAuthExplainedCard(
    identityKey: String,
    title: String,
    explanation: String,
    icon: ImageVector,
    testTagPrefix: String,
    modifier: Modifier = Modifier,
    explanationTitle: String = title,
    compact: Boolean = false,
    closeLabel: String = stringResource(R.string.common_close),
    content: @Composable () -> Unit,
) {
    var open by remember(identityKey) { mutableStateOf(false) }
    // Resolve outside the sheet's separate window to retain the page locale.
    val infoLabel = stringResource(R.string.a11y_card_information, title)
    RescueAuthCard(modifier = modifier, contentPadding = CardTokens.noPadding) {
        Surface(onClick = { open = true }, color = CardTokens.protectedHeaderColor(),
            modifier = Modifier.fillMaxWidth().heightIn(min = ScreenTokens.controlMinHeight)
                .testTag("${testTagPrefix}_info").semantics { contentDescription = infoLabel }) {
            if (compact) {
                Row(Modifier.padding(horizontal = CardTokens.contentPadding, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Icon(icon, null, Modifier.size(CardTokens.actionIconSize), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Outlined.Info, null, Modifier.size(CardTokens.actionIconSize), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                RescueAuthIconHeader(icon, title,
                    modifier = Modifier.padding(horizontal = CardTokens.contentPadding, vertical = Spacing.sm),
                    trailing = { Icon(Icons.Outlined.Info, null, Modifier.size(CardTokens.actionIconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant) })
            }
        }
        Column(Modifier.padding(CardTokens.contentPadding)) { content() }
    }
    if (open) {
        RescueAuthSheet(explanationTitle, icon, { open = false },
            footer = { dismiss -> RescueAuthSingleActionBar(closeLabel, Icons.Filled.Check, dismiss, testTag = "${testTagPrefix}_close") }) {
                RescueAuthCard {
                    Text(explanation, Modifier.testTag("${testTagPrefix}_explanation"), style = MaterialTheme.typography.bodyMedium)
                }
        }
    }
}
