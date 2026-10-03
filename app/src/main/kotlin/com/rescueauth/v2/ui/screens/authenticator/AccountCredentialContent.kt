package com.rescueauth.v2.ui.screens.authenticator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.authenticator.TotpCardUi
import com.rescueauth.v2.ui.components.RescueAuthIconBadge
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
@OptIn(ExperimentalLayoutApi::class)
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
        itemsIndexed(account.totpCredentials, key = { _, item -> item.id }) { index, totp ->
            val title = if (account.totpCredentials.size > 1)
                stringResource(R.string.account_verification_code_number, index + 1)
                else stringResource(R.string.account_verification_code)
            CredentialPanel(totp, title,
                onCopyClick = onCopyClick?.let { callback -> { callback(totp.toTotpCardUi(account)) } },
                onDeleteClick = onDeleteClick?.let { callback -> { callback(totp.toTotpCardUi(account)) } })
        }
        if (onOpenRecovery != null || account.recoverySets.isNotEmpty()) item(key = "recovery-link") {
            val recoveryDescription = if (account.recoverySets.isEmpty()) stringResource(R.string.account_no_recovery)
                else pluralStringResource(R.plurals.account_recovery_available, account.recoverySets.size,
                    account.recoverySets.size, account.remainingRecoveryCount)
            RescueAuthRowCard(onClick = onOpenRecovery?.let { callback -> { callback(account.id) } },
                modifier = Modifier.testTag("account_recovery").semantics { contentDescription = recoveryDescription },
                horizontalPadding = CardTokens.accountRowPadding, verticalPadding = Spacing.sm) {
                RescueAuthIconBadge(Icons.Outlined.Shield, size = CardTokens.accountAvatarSize, iconSize = Spacing.lg,
                    containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .055f),
                    contentColor = CardTokens.actionPrimaryContentColor())
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Text(stringResource(R.string.recovery_codes_title), Modifier.padding(end = Spacing.sm),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Text(if (account.recoverySets.isEmpty()) stringResource(R.string.account_recovery_empty_status)
                        else pluralStringResource(R.plurals.recovery_available_count, account.remainingRecoveryCount, account.remainingRecoveryCount),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onOpenRecovery != null) RescueAuthChevron()
            }
        }
    }
}

@Composable
private fun CredentialPanel(totp: TotpCredentialUi, title: String, onCopyClick: (() -> Unit)?, onDeleteClick: (() -> Unit)?) {
    var menuOpen by remember { mutableStateOf(false) }
    val available = !totp.currentCode.isNullOrBlank()
    val copyLabel = stringResource(R.string.totp_copy_code_description)
    RescueAuthCard(contentPadding = CardTokens.credentialPadding, modifier = Modifier.testTag("credential_${totp.id}")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            CredentialCountdown(totp, available)
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("totp_actions_${totp.id}")) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.totp_actions), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                    Text(stringResource(R.string.totp_parameters_summary, totp.algorithm, totp.digits, totp.periodSeconds),
                        Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (onDeleteClick != null) {
                        HorizontalDivider(color = CardTokens.outlineColor(), thickness = CardTokens.borderWidth)
                        DropdownMenuItem(text = { Text(stringResource(R.string.totp_delete)) },
                            onClick = { menuOpen = false; onDeleteClick() })
                    }
                }
            }
        }
        val raw = totp.currentCode?.filterNot(Char::isWhitespace)?.takeIf { it.isNotEmpty() } ?: "•".repeat(totp.digits)
        val displayed = raw.chunked((raw.length / 2).coerceAtLeast(1)).joinToString(" ")
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium, fontSize = CardTokens.credentialCodeSize,
            letterSpacing = 1.sp, fontFeatureSettings = "tnum", textDirection = TextDirection.Ltr)
        val copyAction = if (onCopyClick != null) Modifier.clip(CardTokens.rowShape).clickable(
            enabled = available, role = Role.Button, onClickLabel = copyLabel, onClick = onCopyClick,
        ).semantics { contentDescription = copyLabel } else Modifier
        // One generous copy target includes both the numeric value and its quiet trailing icon.
        Row(Modifier.fillMaxWidth().padding(top = Spacing.xs).then(copyAction)
            .heightIn(min = ScreenTokens.controlMinHeight).testTag("totp_copy_${totp.id}"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            BoxWithConstraints(Modifier.weight(1f).padding(vertical = Spacing.xxs)) {
                val naturalWidth = measurer.measure(AnnotatedString(displayed), style = style, maxLines = 1).size.width
                val scale = (with(density) { maxWidth.toPx() } / naturalWidth.coerceAtLeast(1)).coerceAtMost(1f)
                Text(displayed, Modifier.testTag("totp_value_${totp.id}"), maxLines = 1, softWrap = false,
                    style = style.copy(fontSize = style.fontSize * scale, letterSpacing = style.letterSpacing * scale),
                    color = MaterialTheme.colorScheme.onSurface)
            }
            if (onCopyClick != null) Box(Modifier.size(ScreenTokens.controlMinHeight), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ContentCopy, null, Modifier.size(CardTokens.actionIconSize),
                    tint = if (available) CardTokens.actionPrimaryContentColor()
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f))
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
    Box(Modifier.size(CardTokens.credentialCountdownSize * LocalDensity.current.fontScale.coerceAtLeast(1f)).semantics(mergeDescendants = true) { contentDescription = description },
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
