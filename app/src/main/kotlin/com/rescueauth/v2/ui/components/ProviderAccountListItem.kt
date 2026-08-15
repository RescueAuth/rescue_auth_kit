package com.rescueauth.v2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
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
import com.rescueauth.v2.ui.model.AccountUi
import com.rescueauth.v2.ui.model.TotpCredentialUi
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing

/**
 * Provider/Account list item.
 *
 * Renders a single account under a provider group. The [isPinned] badge and
 * trailing actions are part of the future Pin/action UI contract (Phase 4 P7)
 * and are visual-only in this foundation PR.
 */
@Composable
fun ProviderAccountListItem(
    account: AccountUi,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    val clickable = if (onClick != null) {
        Modifier
            .semantics { role = Role.Button }
            .then(Modifier)
    } else {
        Modifier
    }

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .then(clickable),
        onClick = onClick ?: {},
        enabled = onClick != null,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = account.providerName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (account.isPinned) {
                        Spacer(modifier = Modifier.size(Spacing.xs))
                        Icon(
                            imageVector = Icons.Filled.PushPin,
                            contentDescription = stringResource(R.string.account_pinned_label),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Text(
                    text = account.accountName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailingAction?.invoke()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ProviderAccountListItemPreview() {
    RescueAuthTheme {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            ProviderAccountListItem(
                account = AccountUi(
                    id = "a1",
                    providerName = "GitHub",
                    accountName = "alice@example.com",
                ),
            )
            ProviderAccountListItem(
                account = AccountUi(
                    id = "a2",
                    providerName = "Google",
                    accountName = "bob@example.com",
                    isPinned = true,
                ),
            )
        }
    }
}
