package com.rescueauth.v2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.model.DeveloperEntryType
import com.rescueauth.v2.ui.model.DeveloperEntryUi
import com.rescueauth.v2.ui.model.DeveloperPreviewData
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing

/** Maps a [DeveloperEntryType] to its icon for list presentation. */
fun DeveloperEntryType.icon(): ImageVector = when (this) {
    DeveloperEntryType.ANDROID_SIGNING_KEY -> Icons.Filled.VpnKey
    DeveloperEntryType.API_CREDENTIAL -> Icons.Filled.Key
    DeveloperEntryType.SSH_KEY -> Icons.Filled.Lock
    DeveloperEntryType.ENVIRONMENT_VARIABLE_SET -> Icons.Filled.Build
    DeveloperEntryType.GENERIC_SECRET -> Icons.Filled.Key
}

/**
 * Developer Entry card.
 *
 * Renders one of the five developer entry types with its type icon, title,
 * subtitle and pinned indicator. The card body may embed [SensitiveValueRow]
 * instances for hidden/reveal of fields.
 *
 * This component only hosts presentation — no CRUD, no reveal enforcement and
 * no persistence.
 */
@Composable
fun DeveloperEntryCard(
    entry: DeveloperEntryUi,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val clickableModifier = if (onClick != null) {
        Modifier.semantics { role = Role.Button }
    } else {
        Modifier
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(clickableModifier),
        onClick = onClick ?: {},
        enabled = onClick != null,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(
                    imageVector = entry.type.icon(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (entry.subtitle != null) {
                        Text(
                            text = entry.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (entry.isPinned) {
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = stringResource(R.string.account_pinned_label),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (content != null) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                content()
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DeveloperEntryCardPreview() {
    RescueAuthTheme {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            DeveloperEntryCard(entry = DeveloperPreviewData.signingKey)
            DeveloperEntryCard(entry = DeveloperPreviewData.apiCredential)
            DeveloperEntryCard(entry = DeveloperPreviewData.sshKey)
        }
    }
}
