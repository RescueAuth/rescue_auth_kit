package com.rescueauth.v2.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.Spacing

/** Shared bottom slot for page actions. Owns IME/navigation insets; callers own action semantics. */
@Composable
fun RescueAuthActionBar(
    secondaryLabel: String,
    secondaryIcon: ImageVector,
    onSecondaryClick: () -> Unit,
    primaryLabel: String,
    primaryIcon: ImageVector,
    onPrimaryClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryDestructive: Boolean = false,
    secondaryEnabled: Boolean = true,
    primaryEnabled: Boolean = true,
    secondaryTestTag: String = "page_action_secondary",
    primaryTestTag: String = "page_action_primary",
) {
    RescueAuthBottomBar(modifier) {
        RescueAuthCard(
            modifier = Modifier.testTag("page_action_bar").border(
                CardTokens.actionBarBorderWidth, CardTokens.outlineColor(), CardTokens.actionBarShape),
            shape = CardTokens.actionBarShape,
            contentPadding = CardTokens.actionBarPadding,
        ) {
            BoxWithConstraints {
                // Keep the available button widths at 2:3 in every configuration.
                // Narrow / enlarged labels may stack and wrap; both targets grow equally tall.
                val stackedLabels = maxWidth < CardTokens.actionBarBalancedWidth && LocalDensity.current.fontScale > 1.2f
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(CardTokens.actionBarGap)) {
                    RescueAuthButton(
                        onClick = onSecondaryClick,
                        enabled = secondaryEnabled,
                        modifier = Modifier.weight(CardTokens.actionSecondaryWeight).fillMaxHeight().heightIn(min = CardTokens.actionButtonHeight)
                            .testTag(secondaryTestTag),
                        shape = CardTokens.actionButtonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = if (secondaryDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledContainerColor = Color.Transparent,
                            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        ),
                        contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = Spacing.xs),
                    ) {
                        ActionLabel(secondaryLabel, secondaryIcon, stackedLabels)
                    }
                    RescueAuthButton(
                        onClick = onPrimaryClick,
                        enabled = primaryEnabled,
                        modifier = Modifier.weight(CardTokens.actionPrimaryWeight).fillMaxHeight()
                            .heightIn(min = CardTokens.actionButtonHeight).testTag(primaryTestTag),
                        shape = CardTokens.actionButtonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CardTokens.actionPrimaryContainerColor(),
                            contentColor = CardTokens.actionPrimaryContentColor(),
                        ),
                        contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = Spacing.xs),
                    ) {
                        ActionLabel(primaryLabel, primaryIcon, stackedLabels)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.ActionLabel(label: String, icon: ImageVector, stacked: Boolean) {
    if (stacked) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(CardTokens.actionIconSize))
            Text(label, Modifier.fillMaxWidth().padding(top = Spacing.xxs), textAlign = TextAlign.Center)
        }
    } else {
        Icon(icon, contentDescription = null, modifier = Modifier.size(CardTokens.actionIconSize))
        Text(label, Modifier.padding(start = Spacing.xs).weight(1f, fill = false))
    }
}

/** Single-action steps use the same footer geometry as paired import actions. */
@Composable
fun RescueAuthSingleActionBar(label: String, icon: ImageVector, onClick: () -> Unit,
    testTag: String = "import_single_action") {
    RescueAuthBottomBar {
        RescueAuthCard(shape = CardTokens.actionBarShape, contentPadding = CardTokens.actionBarPadding,
            modifier = Modifier.border(CardTokens.actionBarBorderWidth, CardTokens.outlineColor(), CardTokens.actionBarShape)) {
            RescueAuthButton(onClick = onClick,
                modifier = Modifier.fillMaxWidth().heightIn(min = CardTokens.actionButtonHeight).testTag(testTag),
                shape = CardTokens.actionButtonShape,
                colors = ButtonDefaults.buttonColors(containerColor = CardTokens.actionPrimaryContainerColor(),
                    contentColor = CardTokens.actionPrimaryContentColor())) {
                Icon(icon, null, Modifier.size(CardTokens.actionIconSize))
                Text(label, Modifier.padding(start = Spacing.xs).weight(1f, fill = false), textAlign = TextAlign.Center)
            }
        }
    }
}
