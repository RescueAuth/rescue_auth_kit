package com.rescueauth.v2.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.ui.theme.CardTokens

/**
 * Standard RescueAuth card container.
 *
 * Card-first is a **project-wide UI constraint** (see
 * `docs/UI_CARD_CONVENTION.md`): every list row, grouped content block, account /
 * TOTP / recovery entry and form section must be presented inside a card rather
 * than as a bare flat row. Prefer this composable (or the [CardTokens] values
 * on a raw `Card`) everywhere so the global card language stays consistent and
 * a future restyle is a single-point change.
 *
 * [onClick] non-null renders a clickable card; otherwise a static card.
 * Content is wrapped with the standard card padding ([CardTokens.contentPadding]).
 */
@Composable
fun RescueAuthCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: androidx.compose.ui.graphics.Color = CardTokens.containerColor(),
    shape: Shape = CardTokens.shape,
    contentPadding: Dp = CardTokens.contentPadding,
    content: @Composable () -> Unit,
) {
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 2.dp),
            border = BorderStroke(1.dp, CardTokens.outlineColor()),
        ) {
            Column(modifier = Modifier.padding(contentPadding)) {
                content()
            }
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = BorderStroke(1.dp, CardTokens.outlineColor()),
        ) {
            Column(modifier = Modifier.padding(contentPadding)) {
                content()
            }
        }
    }
}

/**
 * Horizontal (row-layout) variant of [RescueAuthCard] — used by compact list
 * rows (Provider list item, account list item, settings entry) where the card
 * body is a single [Row] of icon / text / trailing action. The content lambda
 * is a [RowScope] so `Modifier.weight(...)` works as in a plain `Row`.
 */
@Composable
fun RescueAuthRowCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: androidx.compose.ui.graphics.Color = CardTokens.elevatedContainerColor(),
    shape: Shape = CardTokens.rowShape,
    horizontalPadding: Dp = CardTokens.contentPadding,
    verticalPadding: Dp = CardTokens.contentPadding,
    horizontalArrangement: androidx.compose.foundation.layout.Arrangement.Horizontal = androidx.compose.foundation.layout.Arrangement.Start,
    content: @Composable RowScope.() -> Unit,
) {
    val cardModifier = modifier.fillMaxWidth()
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = cardModifier,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 2.dp),
            border = BorderStroke(1.dp, CardTokens.outlineColor()),
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = horizontalPadding,
                    vertical = verticalPadding,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = horizontalArrangement,
                content = content,
            )
        }
    } else {
        Card(
            modifier = cardModifier,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = BorderStroke(1.dp, CardTokens.outlineColor()),
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = horizontalPadding,
                    vertical = verticalPadding,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = horizontalArrangement,
                content = content,
            )
        }
    }
}
