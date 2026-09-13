package com.rescueauth.v2.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.ui.navigation.TopLevelDestination
import com.rescueauth.v2.ui.navigation.TopLevelDestinations
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.Spacing

/** A floating ink dock with three permanent labels and a moving color selection. */
@Composable
fun RescueAuthNavigationBar(
    isSelected: (TopLevelDestination) -> Boolean,
    onNavigate: (TopLevelDestination) -> Unit,
) {
    Box(Modifier.navigationBarsPadding().padding(horizontal = ScreenTokens.horizontalPadding, vertical = Spacing.xs)) {
        Surface(color = StudioColors.ink, shape = CardTokens.dockShape) {
            Row(Modifier.fillMaxWidth().padding(Spacing.xs).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                TopLevelDestinations.all.forEach { destination ->
                    val selected = isSelected(destination)
                    val fill by animateColorAsState(
                        if (selected) StudioColors.lavender else StudioColors.ink, label = "navigation selection")
                    val foreground = if (selected) StudioColors.ink else StudioColors.mutedInk
                    Column(
                        Modifier.weight(1f).clip(CardTokens.rowShape).background(fill)
                            .selectable(selected = selected, role = Role.Tab, onClick = { onNavigate(destination) })
                            .heightIn(min = 58.dp).padding(vertical = Spacing.xs)
                            .testTag(destination.testTag),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.xxs, Alignment.CenterVertically),
                    ) {
                        Icon(destination.icon, null, Modifier.size(21.dp), tint = foreground)
                        Text(stringResource(destination.labelRes), style = MaterialTheme.typography.labelSmall, color = foreground)
                    }
                }
            }
        }
    }
}
