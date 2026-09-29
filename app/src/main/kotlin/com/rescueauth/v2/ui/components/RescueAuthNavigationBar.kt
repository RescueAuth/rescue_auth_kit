package com.rescueauth.v2.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import com.rescueauth.v2.ui.navigation.TopLevelDestination
import com.rescueauth.v2.ui.navigation.TopLevelDestinations
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.DockTokens
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.Spacing
import kotlin.math.roundToInt

/** A translucent floating capsule with theme-aware materials and three permanent labels. */
@Composable
fun RescueAuthNavigationBar(
    isSelected: (TopLevelDestination) -> Boolean,
    pagePosition: (() -> Float)? = null,
    onNavigate: (TopLevelDestination) -> Unit,
) {
    val destinations = TopLevelDestinations.all
    val selectedIndex = destinations.indexOfFirst(isSelected)
    val indicatorPosition = pagePosition ?: run {
        val position = animateFloatAsState(
            targetValue = selectedIndex.coerceAtLeast(0).toFloat(),
            animationSpec = tween(DockTokens.slideDurationMillis, easing = FastOutSlowInEasing),
            label = "dock indicator position",
        )
        return@run { position.value }
    }
    Box(Modifier.navigationBarsPadding().padding(horizontal = ScreenTokens.horizontalPadding, vertical = Spacing.xs)) {
        Surface(modifier = Modifier.testTag("navigation_dock"),
            color = DockTokens.containerColor(), shape = CardTokens.dockShape,
            border = BorderStroke(DockTokens.borderWidth, DockTokens.borderColor()),
            shadowElevation = DockTokens.shadowElevation) {
            Box(Modifier.fillMaxWidth().padding(DockTokens.contentPadding)) {
                // The row determines the height, including wrapped / enlarged labels. Its sibling
                // indicator uses those measured bounds without an onSizeChanged state round trip.
                if (selectedIndex >= 0) Layout(
                    modifier = Modifier.matchParentSize(),
                    content = { Box(Modifier.testTag("navigation_indicator")
                        .background(DockTokens.selectionColor(), DockTokens.selectionShape)) },
                ) { measurables, constraints ->
                    val gap = DockTokens.itemSpacing.roundToPx()
                    val cellWidth = (constraints.maxWidth - gap * (destinations.size - 1)).toFloat() / destinations.size
                    val pill = measurables.single().measure(Constraints.fixed(cellWidth.roundToInt(), constraints.maxHeight))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        // Relative placement mirrors both the slide and the resting position in RTL.
                        // Read the shared home transition position only during placement; scrolling
                        // must not recompose the app shell or run a second indicator clock.
                        pill.placeRelative(((cellWidth + gap) * indicatorPosition()).roundToInt(), 0)
                    }
                }
                Row(Modifier.fillMaxWidth().selectableGroup(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DockTokens.itemSpacing)) {
                    destinations.forEachIndexed { index, destination ->
                        val selected = index == selectedIndex
                        val interactions = remember { MutableInteractionSource() }
                        val focused by interactions.collectIsFocusedAsState()
                        val foreground by animateColorAsState(
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            animationSpec = tween(DockTokens.slideDurationMillis), label = "dock foreground")
                        val scale by animateFloatAsState(
                            if (selected) DockTokens.selectedIconScale else 1f,
                            animationSpec = tween(DockTokens.slideDurationMillis, easing = FastOutSlowInEasing), label = "dock icon scale")
                        Column(
                            Modifier.weight(1f)
                                .then(if (focused) Modifier.border(DockTokens.focusBorderWidth,
                                    MaterialTheme.colorScheme.primary, DockTokens.selectionShape) else Modifier)
                                .selectable(
                                    selected = selected,
                                    interactionSource = interactions,
                                    indication = null, // The sliding pill is the only touch-selection background.
                                    role = Role.Tab,
                                    onClick = { onNavigate(destination) },
                                )
                                .heightIn(min = ScreenTokens.controlMinHeight).padding(vertical = DockTokens.itemVerticalPadding)
                                .testTag(destination.testTag),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(DockTokens.labelSpacing, Alignment.CenterVertically),
                        ) {
                            Icon(destination.icon, null,
                                Modifier.size(DockTokens.iconSize).graphicsLayer { scaleX = scale; scaleY = scale }, tint = foreground)
                            Text(stringResource(destination.labelRes), style = MaterialTheme.typography.labelSmall, color = foreground)
                        }
                    }
                }
            }
        }
    }
}
