package com.rescueauth.v2.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.authenticator.TotpCardUi
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing

/**
 * TOTP card — production data path (Phase 4 P1).
 *
 * Shows the current code (large, monospace), issuer/account, a live
 * [CountdownIndicator] (remaining fraction + seconds) and Copy/Delete actions.
 * The code text is clickable to copy as well.
 */
@Composable
fun TotpCard(
    credential: TotpCardUi,
    modifier: Modifier = Modifier,
    onCopyClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = CardTokens.shape,
        colors = CardDefaults.cardColors(
            containerColor = CardTokens.containerColor(),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CardTokens.contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = credential.issuer,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = credential.accountName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = credential.currentCode,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = MaterialTheme.typography.headlineMedium.letterSpacing,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = if (onCopyClick != null) {
                        Modifier.clickable(onClick = onCopyClick)
                    } else {
                        Modifier
                    },
                )
            }
            CountdownIndicator(
                progressFraction = credential.progressFraction,
                remainingSeconds = credential.remainingSeconds,
                ringSize = 56,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (onCopyClick != null) {
                    IconButton(
                        onClick = onCopyClick,
                        modifier = Modifier.semantics { role = Role.Button },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.totp_copy_code),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (onDeleteClick != null) {
                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier.semantics { role = Role.Button },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.totp_delete),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TotpCardPreview() {
    RescueAuthTheme {
        Column(modifier = Modifier.padding(Spacing.md)) {
            TotpCard(
                credential = TotpCardUi(
                    credentialId = "t1",
                    stableId = "t1",
                    accountId = "a1",
                    issuer = "GitHub",
                    accountName = "alice@example.com",
                    algorithm = "SHA1",
                    digits = 6,
                    periodSeconds = 30,
                    currentCode = "123 456",
                    remainingSeconds = 24,
                    progressFraction = 0.8f,
                ),
                onCopyClick = {},
                onDeleteClick = {},
            )
        }
    }
}
