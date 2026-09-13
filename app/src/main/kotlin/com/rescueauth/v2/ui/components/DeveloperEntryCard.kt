package com.rescueauth.v2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.model.DeveloperEntryType
import com.rescueauth.v2.ui.model.DeveloperEntryUi
import com.rescueauth.v2.ui.model.DeveloperPreviewData
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing

fun DeveloperEntryType.icon(): ImageVector = when (this) {
    DeveloperEntryType.ANDROID_SIGNING_KEY -> Icons.Outlined.VerifiedUser
    DeveloperEntryType.API_CREDENTIAL -> Icons.Outlined.Key
    DeveloperEntryType.SSH_KEY -> Icons.Outlined.Terminal
    DeveloperEntryType.ENVIRONMENT_VARIABLE_SET -> Icons.Outlined.DataObject
    DeveloperEntryType.GENERIC_SECRET -> Icons.Outlined.Article
}

private fun DeveloperEntryType.displayNameRes(): Int = when (this) {
    DeveloperEntryType.ANDROID_SIGNING_KEY -> R.string.developer_type_signing_key
    DeveloperEntryType.API_CREDENTIAL -> R.string.developer_type_api_credential
    DeveloperEntryType.SSH_KEY -> R.string.developer_type_ssh_key
    DeveloperEntryType.ENVIRONMENT_VARIABLE_SET -> R.string.developer_type_env_var
    DeveloperEntryType.GENERIC_SECRET -> R.string.developer_type_generic
}

/** A metadata-only Developer Vault entry. */
@Composable
fun DeveloperEntryCard(
    entry: DeveloperEntryUi,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val badgeContainer = MaterialTheme.colorScheme.primaryContainer
    val badgeContent = MaterialTheme.colorScheme.onPrimaryContainer
    RescueAuthCard(
        modifier = modifier.then(
            if (onClick != null) Modifier.semantics { role = Role.Button } else Modifier,
        ),
        onClick = onClick,
        containerColor = CardTokens.elevatedContainerColor(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            RescueAuthIconBadge(
                icon = entry.type.icon(),
                containerColor = badgeContainer,
                contentColor = badgeContent,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                (entry.subtitle?.takeIf { it.isNotBlank() }
                    ?: stringResource(entry.type.displayNameRes()))
                    .let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
            if (onClick != null) RescueAuthChevron()
        }
        if (content != null) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            RescueAuthDivider()
            Spacer(modifier = Modifier.height(Spacing.xs))
            content()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DeveloperEntryCardPreview() {
    RescueAuthTheme {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            DeveloperEntryCard(entry = DeveloperPreviewData.signingKey, onClick = {})
            DeveloperEntryCard(entry = DeveloperPreviewData.apiCredential, onClick = {})
        }
    }
}
