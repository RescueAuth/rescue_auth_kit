package com.rescueauth.v2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing

/**
 * A labelled sensitive-value row with hidden/reveal behaviour.
 *
 * Contract:
 * - While [revealed] is false the value is masked (`••••••••`) so a screenshot
 *   or shoulder-surf never exposes it.
 * - Tapping the visibility icon toggles the masked state (local, in-memory
 *   only; state survives configuration change via [rememberSaveable]).
 * - The component is fully controlled by [revealed]/[onRevealToggle] so callers
 *   can enforce Sensitive Action Re-authentication before revealing (a later
 *   slice wires the re-auth gate; this component only hosts the visual
 *   contract).
 *
 * No secret ever enters the UI model as plaintext unless an explicit reveal
 * has already been authorised by the caller.
 */
@Composable
fun SensitiveValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    revealed: Boolean = false,
    onRevealToggle: ((Boolean) -> Unit)? = null,
    valueStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    // Local fallback keeps the row usable as a preview/demo component even when
    // no controller is supplied (production screens always pass a controller).
    var localRevealed by rememberSaveable { mutableStateOf(revealed) }
    val isRevealed = if (onRevealToggle != null) revealed else localRevealed

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (isRevealed) value else "••••••••",
                style = if (isRevealed) {
                    valueStyle.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                color = valueColor,
            )
        }
        IconButton(
            onClick = {
                val next = !isRevealed
                if (onRevealToggle != null) onRevealToggle(next) else localRevealed = next
            },
        ) {
            Icon(
                imageVector = if (isRevealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = stringResource(
                    if (isRevealed) R.string.developer_hide else R.string.developer_reveal,
                ),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SensitiveValueRowHiddenPreview() {
    RescueAuthTheme {
        Column(modifier = Modifier.padding(Spacing.md)) {
            SensitiveValueRow(
                label = "apiKey",
                value = "sk_live_abc123",
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SensitiveValueRowRevealedPreview() {
    RescueAuthTheme {
        Column(modifier = Modifier.padding(Spacing.md)) {
            SensitiveValueRow(
                label = "apiKey",
                value = "sk_live_abc123",
                revealed = true,
                onRevealToggle = {},
            )
        }
    }
}
