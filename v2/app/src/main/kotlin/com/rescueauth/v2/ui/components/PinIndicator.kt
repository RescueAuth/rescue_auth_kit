package com.rescueauth.v2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing

/**
 * Pin indicator / action UI (Phase 4 P7 contract).
 *
 * Renders the pinned state for an item and exposes a toggle action. Persistence
 * of the pinned flag is deliberately out of scope for this foundation PR — this
 * component only presents the state and forwards the toggle.
 */
@Composable
fun PinIndicator(
    isPinned: Boolean,
    onToggle: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    showLabel: Boolean = false,
) {
    if (onToggle != null) {
        IconButton(
            onClick = onToggle,
            modifier = modifier.semantics { role = Role.Button },
        ) {
            Icon(
                imageVector = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = stringResource(R.string.account_pinned_label),
                tint = if (isPinned) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    } else {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Icon(
                imageVector = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = null,
                tint = if (isPinned) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(16.dp),
            )
            if (showLabel && isPinned) {
                Text(
                    text = stringResource(R.string.account_pinned_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PinIndicatorPreview() {
    RescueAuthTheme {
        Row(
            modifier = Modifier,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PinIndicator(isPinned = true, onToggle = {})
            PinIndicator(isPinned = false, onToggle = {})
            PinIndicator(isPinned = true, showLabel = true)
        }
    }
}
