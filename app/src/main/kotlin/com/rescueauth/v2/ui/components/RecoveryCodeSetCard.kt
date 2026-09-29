package com.rescueauth.v2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.model.RecoveryCodeSetUi
import com.rescueauth.v2.ui.model.RecoveryCodeUi
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing

/** Recovery-code set card with a compact summary and explicit actions. */
@Composable
fun RecoveryCodeSetCard(
    set: RecoveryCodeSetUi,
    modifier: Modifier = Modifier,
    revealedIds: Set<String> = emptySet(),
    onRevealToggle: ((String) -> Unit)? = null,
    onCopyCode: ((String) -> Unit)? = null,
    onCopyAll: (() -> Unit)? = null,
    onCopyRemaining: (() -> Unit)? = null,
    onMarkUsed: ((String) -> Unit)? = null,
    onMarkUnused: ((String) -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onMove: (() -> Unit)? = null,
) {
    // Scope transient controls to the set identity. After a delete/reorder a
    // different set must never inherit the previous row's expanded/menu state.
    var expanded by rememberSaveable(set.id) { mutableStateOf(false) }
    var menuOpen by rememberSaveable(set.id) { mutableStateOf(false) }
    RescueAuthCard(
        modifier = modifier,
        containerColor = CardTokens.containerColor(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            RescueAuthIconBadge(
                icon = if (set.remainingCount > 0) Icons.Filled.CheckCircle else Icons.Filled.VisibilityOff,
                size = 40.dp,
                iconSize = 20.dp,
                containerColor = if (set.remainingCount > 0) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (set.remainingCount > 0) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = set.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        R.string.recovery_codes_remaining_count,
                        set.remainingCount,
                        set.totalCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onCopyAll != null || onCopyRemaining != null || onEdit != null || onDelete != null || onMove != null) {
                androidx.compose.foundation.layout.Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.account_actions_label),
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        if (onCopyAll != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.recovery_codes_copy_all)) },
                                onClick = { menuOpen = false; onCopyAll() },
                            )
                        }
                        if (onCopyRemaining != null && set.remainingCount > 0) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.recovery_codes_copy_remaining)) },
                                onClick = { menuOpen = false; onCopyRemaining() },
                            )
                        }
                        if (onEdit != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.recovery_codes_edit_title)) },
                                onClick = { menuOpen = false; onEdit() },
                            )
                        }
                        if (onMove != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.recovery_codes_move)) },
                                onClick = { menuOpen = false; onMove() },
                            )
                        }
                        if (onDelete != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.recovery_codes_delete)) },
                                onClick = { menuOpen = false; onDelete() },
                            )
                        }
                    }
                }
            }
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("recovery_expand_${set.id}")) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(Spacing.sm))
            RescueAuthDivider()
            Spacer(modifier = Modifier.height(Spacing.xs))
            set.codes.forEach { code ->
                RecoveryCodeRow(
                    code = code,
                    revealed = code.id in revealedIds,
                    onRevealToggle = onRevealToggle,
                    onCopy = onCopyCode,
                    onMarkUsed = onMarkUsed,
                    onMarkUnused = onMarkUnused,
                )
            }
        }
    }
}

@Composable
private fun RecoveryCodeRow(
    code: RecoveryCodeUi,
    revealed: Boolean,
    onRevealToggle: ((String) -> Unit)?,
    onCopy: ((String) -> Unit)?,
    onMarkUsed: ((String) -> Unit)?,
    onMarkUnused: ((String) -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (revealed) code.value else "••••••••",
                style = if (revealed) {
                    MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    )
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = if (code.isUsed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = stringResource(if (code.isUsed) R.string.recovery_codes_used else R.string.recovery_codes_unused),
                style = MaterialTheme.typography.labelSmall,
                color = if (code.isUsed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        if (onRevealToggle != null) {
            IconButton(onClick = { onRevealToggle(code.id) }) {
                Icon(
                    imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(if (revealed) R.string.recovery_codes_hide else R.string.recovery_codes_reveal),
                )
            }
        }
        if (onCopy != null) {
            IconButton(onClick = { onCopy(code.id) }) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.recovery_codes_copy),
                )
            }
        }
        if (onMarkUsed != null && !code.isUsed) {
            IconButton(onClick = { onMarkUsed(code.id) }) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.recovery_codes_mark_used),
                )
            }
        }
        if (onMarkUnused != null && code.isUsed) {
            IconButton(onClick = { onMarkUnused(code.id) }) {
                Icon(
                    imageVector = Icons.Filled.Undo,
                    contentDescription = stringResource(R.string.recovery_codes_mark_unused),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RecoveryCodeSetCardPreview() {
    RescueAuthTheme {
        RecoveryCodeSetCard(
            set = RecoveryCodeSetUi(
                id = "r1",
                title = "Recovery codes",
                usedCount = 1,
                totalCount = 3,
                codes = listOf(
                    RecoveryCodeUi("rc1", "ABCD-EFGH-1", true),
                    RecoveryCodeUi("rc2", "IJKL-MNOP-2", false),
                ),
            ),
            onCopyAll = {},
        )
    }
}
