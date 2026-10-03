package com.rescueauth.v2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.ui.theme.AddActionTokens
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.Spacing

data class AddSheetAction(val id: String, val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** Content-sized half sheet; tall text can grow and scroll without hiding the last action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RescueAuthAddSheet(
    title: String,
    actions: List<AddSheetAction>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    var selecting by remember { mutableStateOf(false) }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    RescueAuthSheet(title, Icons.Filled.Add, onDismiss, modifier, subtitle,
        minimumBodyHeight = screenHeight * AddActionTokens.sheetMinFraction) {
            actions.forEach { action ->
                RescueAuthRowCard(
                    modifier = Modifier.testTag(action.id),
                    onClick = {
                        if (!selecting) {
                            selecting = true
                            // Switch dialogs in one state update; no suspended callback can outlive this sheet.
                            onDismiss()
                            action.onClick()
                        }
                    },
                    containerColor = CardTokens.containerColor(),
                    verticalPadding = Spacing.md,
                ) {
                    RescueAuthIconBadge(action.icon, size = 40.dp, iconSize = 20.dp)
                    Text(action.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium)
                    RescueAuthChevron()
                }
            }
    }
}
