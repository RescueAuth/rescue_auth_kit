package com.rescueauth.v2.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.SheetTokens
import com.rescueauth.v2.ui.theme.Spacing

data class InlinePickerChoice(val id: String, val label: String, val icon: ImageVector,
    val selected: Boolean = false, val alwaysVisible: Boolean = false, val onSelect: () -> Unit)

/** Editable primary field; selection lives on an optional second level of the same sheet. */
@Composable
fun RescueAuthPickerField(value: String, onValueChange: (String) -> Unit, label: String,
    onOpenChoices: () -> Unit, fieldTag: String, toggleTag: String, enabled: Boolean = true) {
    RescueAuthTextField(value, onValueChange, label = { Text(label) }, enabled = enabled, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(fieldTag), trailingIcon = {
            IconButton(onClick = onOpenChoices, enabled = enabled, modifier = Modifier.testTag(toggleTag)) {
                Icon(Icons.Filled.ExpandMore, stringResource(R.string.input_choice_options, label))
            }
        })
}

/** Reused by service and account selection pages. All rows remain in one card, at the field width. */
@Composable
fun RescueAuthChoiceList(choices: List<InlinePickerChoice>, tag: String, query: String = "") {
    val filtered = choices.filter { it.alwaysVisible || it.label.contains(query.trim(), ignoreCase = true) }
    RescueAuthCard(shape = CardTokens.rowShape, contentPadding = CardTokens.noPadding,
        modifier = Modifier.fillMaxWidth().testTag(tag).border(CardTokens.inputRestBorderWidth,
            CardTokens.inputOutlineColor(), CardTokens.rowShape)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = SheetTokens.choiceListMaxHeight)) {
            items(filtered, key = { it.id }) { choice ->
                Surface(onClick = choice.onSelect,
                    color = if (choice.selected) MaterialTheme.colorScheme.primaryContainer else CardTokens.containerColor(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = ScreenTokens.controlMinHeight).testTag(choice.id)
                        .semantics { role = Role.RadioButton; selected = choice.selected }) {
                    Row(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Icon(choice.icon, null, Modifier.size(CardTokens.actionIconSize), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(choice.label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        if (choice.selected) Icon(Icons.Filled.Check, null, Modifier.size(CardTokens.actionIconSize))
                    }
                }
            }
        }
    }
}
