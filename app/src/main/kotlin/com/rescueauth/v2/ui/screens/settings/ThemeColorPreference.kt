package com.rescueauth.v2.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.components.RescueAuthRowCard
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.CornerRadius
import com.rescueauth.v2.ui.theme.Spacing
import com.rescueauth.v2.ui.theme.ThemeColor
import com.rescueauth.v2.ui.theme.swatchColor

object ThemeColorPreferenceTestTags {
    const val ROW = "theme_color_row"
    const val DIALOG = "theme_color_dialog"
}

@Composable
fun ThemeColorPreference(
    selected: ThemeColor,
    onSelect: (ThemeColor) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
            .padding(vertical = Spacing.xxs)
            .testTag(ThemeColorPreferenceTestTags.ROW),
    ) {
        Text(
            text = stringResource(R.string.settings_theme_color),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.padding(top = Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            ThemeColorSwatch(color = selected.swatchColor(), size = 18.dp)
            Text(
                text = stringResource(selected.displayNameRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showDialog) {
        ThemeColorPickerDialog(
            selected = selected,
            onSelect = { onSelect(it); showDialog = false },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun ThemeColorPickerDialog(
    selected: ThemeColor,
    onSelect: (ThemeColor) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ThemeColorPreferenceTestTags.DIALOG),
        title = {
            Text(
                text = stringResource(R.string.settings_theme_color),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                ThemeColor.entries.forEach { color ->
                    val isSelected = color == selected
                    RescueAuthRowCard(
                        onClick = { onSelect(color) },
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            CardTokens.elevatedContainerColor()
                        },
                        horizontalPadding = Spacing.sm,
                        verticalPadding = Spacing.sm,
                    ) {
                        ThemeColorSwatch(color = color.swatchColor(), size = 24.dp)
                        Text(
                            text = stringResource(color.displayNameRes()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = stringResource(R.string.settings_theme_color_selected),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        },
    )
}

@Composable
private fun ThemeColorSwatch(color: Color, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            ),
    )
}

private fun ThemeColor.displayNameRes(): Int = when (this) {
    ThemeColor.SHIYI_ORANGE -> R.string.theme_color_shiyi_orange
    ThemeColor.CYAN_BLUE -> R.string.theme_color_cyan_blue
    ThemeColor.JADE_GREEN -> R.string.theme_color_jade_green
    ThemeColor.INDIGO -> R.string.theme_color_indigo
    ThemeColor.VIOLET -> R.string.theme_color_violet
    ThemeColor.ROSE -> R.string.theme_color_rose
}
