package com.rescueauth.v2.ui.screens.authenticator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.authenticator.TotpCardUi
import com.rescueauth.v2.ui.components.RescueAuthButton
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.ui.components.RescueAuthChevron
import com.rescueauth.v2.ui.components.RescueAuthInitialBadge
import com.rescueauth.v2.ui.components.RescueAuthRowCard
import com.rescueauth.v2.ui.model.AccountUi
import com.rescueauth.v2.ui.model.ProviderUi
import com.rescueauth.v2.ui.model.TotpCredentialUi
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.Spacing

/** Joined account rows: one quiet group, with lazy composition for long directories. */
@Composable
internal fun ProviderAccountsContent(
    provider: ProviderUi,
    onOpenAccount: (String) -> Unit,
    contentBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize().testTag("provider_accounts"),
        contentPadding = PaddingValues(start = ScreenTokens.horizontalPadding, end = ScreenTokens.horizontalPadding,
            top = Spacing.xs, bottom = Spacing.xxl + contentBottomPadding)) {
        item(key = "accounts-heading") {
            Row(Modifier.fillMaxWidth().padding(bottom = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.authenticator_accounts_heading), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(provider.accounts.size.toString(), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (provider.accounts.isEmpty()) item(key = "empty-accounts") {
            RescueAuthCard(shape = CardTokens.shape) {
                Text(stringResource(R.string.authenticator_empty_title),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        itemsIndexed(provider.accounts, key = { _, account -> account.id }) { index, account ->
            val last = index == provider.accounts.lastIndex
            val shape = when {
                index == 0 && last -> CardTokens.shape
                index == 0 -> CardTokens.groupTopShape
                last -> CardTokens.groupBottomShape
                else -> CardTokens.groupMiddleShape
            }
            RescueAuthCard(onClick = { onOpenAccount(account.id) }, shape = shape, contentPadding = CardTokens.noPadding,
                modifier = Modifier.testTag("account_row_${account.id}").semantics { role = Role.Button }) {
                Row(Modifier.fillMaxWidth().heightIn(min = CardTokens.accountRowMinHeight)
                    .padding(horizontal = CardTokens.accountRowPadding, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    RescueAuthInitialBadge(account.accountName, Modifier.clearAndSetSemantics {},
                        size = CardTokens.accountAvatarSize,
                        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .055f),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                        Text(account.accountName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val summary = buildList {
                            if (account.totpCredentials.isNotEmpty()) add(pluralStringResource(R.plurals.account_code_count,
                                account.totpCredentials.size, account.totpCredentials.size))
                            if (account.recoverySets.isNotEmpty()) add(pluralStringResource(R.plurals.account_recovery_group_count,
                                account.recoverySets.size, account.recoverySets.size))
                        }.joinToString(" · ")
                        Text(summary.ifEmpty { stringResource(R.string.account_no_credentials) },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (account.isPinned) Icon(Icons.Filled.PushPin, stringResource(R.string.account_pinned_label),
                        Modifier.size(Spacing.md), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    RescueAuthChevron()
                }
                if (!last) HorizontalDivider(Modifier.padding(start = CardTokens.accountRowPadding + CardTokens.accountAvatarSize + Spacing.md,
                    end = CardTokens.accountRowPadding), thickness = CardTokens.borderWidth, color = CardTokens.outlineColor())
            }
        }

    }
}

/** The account itself is named in the page header; no duplicate provider summary. */
@Composable
internal fun AccountDetailContent(
    account: AccountUi,
    onCopyClick: ((TotpCardUi) -> Unit)?,
    onDeleteClick: ((TotpCardUi) -> Unit)?,
    onOpenRecovery: ((String) -> Unit)?,
    contentBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize().testTag("account_credentials"),
        contentPadding = PaddingValues(start = ScreenTokens.horizontalPadding, end = ScreenTokens.horizontalPadding,
            top = Spacing.xs, bottom = Spacing.xxl + contentBottomPadding),
        verticalArrangement = Arrangement.spacedBy(CardTokens.listSpacing)) {
        if (account.totpCredentials.isEmpty()) item(key = "no-codes") {
            RescueAuthRowCard {
                Icon(Icons.Outlined.Timer, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.account_no_codes), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(account.totpCredentials, key = { it.id }) { totp ->
            CredentialPanel(totp,
                onCopyClick = onCopyClick?.let { callback -> { callback(totp.toTotpCardUi(account)) } },
                onDeleteClick = onDeleteClick?.let { callback -> { callback(totp.toTotpCardUi(account)) } })
        }
        if (onOpenRecovery != null || account.recoverySets.isNotEmpty()) item(key = "recovery-link") {
            RescueAuthRowCard(onClick = onOpenRecovery?.let { callback -> { callback(account.id) } },
                modifier = Modifier.testTag("account_recovery"),
                horizontalPadding = CardTokens.accountRowPadding, verticalPadding = Spacing.md) {
                Icon(Icons.Outlined.Shield, null, Modifier.size(Spacing.lg), tint = CardTokens.actionPrimaryContentColor())
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Text(stringResource(R.string.recovery_codes_title), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                    Text(if (account.recoverySets.isEmpty()) stringResource(R.string.account_no_recovery)
                        else pluralStringResource(R.plurals.account_recovery_available, account.recoverySets.size,
                            account.recoverySets.size, account.remainingRecoveryCount),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onOpenRecovery != null) RescueAuthChevron()
            }
        }
    }
}

@Composable
private fun CredentialPanel(totp: TotpCredentialUi, onCopyClick: (() -> Unit)?, onDeleteClick: (() -> Unit)?) {
    var menuOpen by remember { mutableStateOf(false) }
    val available = !totp.currentCode.isNullOrBlank()
    RescueAuthCard(contentPadding = CardTokens.credentialPadding, modifier = Modifier.testTag("credential_${totp.id}")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Icon(Icons.Outlined.Timer, null, Modifier.size(Spacing.md), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.account_verification_code), Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CredentialCountdown(totp, available)
            if (onDeleteClick != null) Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("totp_actions_${totp.id}")) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.totp_actions), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.totp_delete)) },
                        onClick = { menuOpen = false; onDeleteClick() })
                }
            }
        }
        val raw = totp.currentCode?.filterNot(Char::isWhitespace)?.takeIf { it.isNotEmpty() } ?: "•".repeat(totp.digits)
        val displayed = raw.chunked((raw.length / 2).coerceAtLeast(1)).joinToString(" ")
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium, fontSize = 46.sp, letterSpacing = 1.sp, fontFeatureSettings = "tnum")
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = Spacing.md, bottom = Spacing.lg), contentAlignment = Alignment.Center) {
            // Keep a complete code on one line at narrow widths and large font settings.
            // Only this fixed-length numeric display adapts; surrounding text still scales normally.
            val naturalWidth = measurer.measure(AnnotatedString(displayed), style = style, maxLines = 1).size.width
            val scale = (with(density) { maxWidth.toPx() } / naturalWidth.coerceAtLeast(1)).coerceAtMost(1f)
            Text(displayed, Modifier.testTag("totp_value_${totp.id}"), maxLines = 1, softWrap = false,
                style = style.copy(fontSize = style.fontSize * scale, letterSpacing = style.letterSpacing * scale),
                color = MaterialTheme.colorScheme.onSurface)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(stringResource(R.string.authenticator_code_metadata, totp.algorithm, totp.digits),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onCopyClick != null) RescueAuthButton(onClick = onCopyClick, enabled = available,
                modifier = Modifier.testTag("totp_copy_${totp.id}"), shape = CardTokens.actionButtonShape,
                colors = ButtonDefaults.buttonColors(containerColor = CardTokens.actionPrimaryContainerColor(),
                    contentColor = CardTokens.actionPrimaryContentColor()),
                contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.xs)) {
                Icon(Icons.Filled.ContentCopy, null, Modifier.size(Spacing.md))
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.totp_copy_code), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun CredentialCountdown(totp: TotpCredentialUi, available: Boolean) {
    val progress = if (available) totp.progressFraction.coerceIn(0f, 1f) else 0f
    val color = if (available && progress <= .18f) MaterialTheme.colorScheme.error else CardTokens.actionPrimaryContentColor()
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = .10f)
    val description = if (available) stringResource(R.string.totp_refresh_countdown, totp.remainingSeconds)
        else stringResource(R.string.totp_unavailable)
    Box(Modifier.size(CardTokens.credentialCountdownSize).semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = CardTokens.credentialCountdownStroke.toPx()
            val inset = width / 2
            drawCircle(track, radius = size.minDimension / 2 - inset, style = Stroke(width))
            drawArc(color, startAngle = -90f, sweepAngle = 360 * progress, useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(size.width - width, size.height - width),
                style = Stroke(width, cap = StrokeCap.Round))
        }
        Text(if (available) totp.remainingSeconds.toString() else "—", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

private fun TotpCredentialUi.toTotpCardUi(account: AccountUi): TotpCardUi = TotpCardUi(
    credentialId = id, stableId = stableId, accountId = account.id, issuer = account.providerName,
    accountName = account.accountName, algorithm = algorithm, digits = digits, periodSeconds = periodSeconds,
    currentCode = currentCode ?: "••••••", remainingSeconds = remainingSeconds, progressFraction = progressFraction,
)
