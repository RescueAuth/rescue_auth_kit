package com.rescueauth.v2.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.Spacing
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Safe display values only. Native and legacy parsing, validation and apply remain independent. */
data class ImportContentCounts(val accounts: Int, val codes: Int, val recoverySets: Int, val recoveryCodes: Int, val developer: Int)
data class ImportPlanCounts(val inserts: Int, val duplicates: Int, val conflicts: Int, val unchanged: Int, val divergences: Int) {
    val blocked: Boolean get() = conflicts > 0 || divergences > 0
}

internal fun formatImportDate(value: String, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
    runCatching { OffsetDateTime.parse(value).atZoneSameInstant(zone)
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)) }.getOrDefault(value)

@Composable
fun ImportFileIntro(legacy: Boolean) {
    RescueAuthCard(contentPadding = CardTokens.heroPadding) {
        RescueAuthIconHeader(if (legacy) Icons.Filled.History else Icons.Filled.FolderOpen,
            stringResource(if (legacy) R.string.import_legacy_file_heading else R.string.import_native_file_heading),
            stringResource(if (legacy) R.string.import_legacy_file_description else R.string.import_native_file_description))
        Row(Modifier.padding(top = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Icon(Icons.AutoMirrored.Filled.MergeType, null, Modifier.size(Spacing.lg), tint = CardTokens.actionPrimaryContentColor())
            Text(stringResource(R.string.import_merge_reassurance), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ImportProgressCard(applying: Boolean) {
    RescueAuthCard {
        RescueAuthIconHeader(if (applying) Icons.Filled.FileDownload else Icons.Filled.Lock,
            stringResource(if (applying) R.string.import_progress_applying else R.string.import_progress_decoding),
            stringResource(R.string.import_progress_hint))
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Spacing.lg).testTag("import_progress"),
            color = CardTokens.actionPrimaryContentColor(), trackColor = CardTokens.actionPrimaryContainerColor())
    }
}

@Composable
fun ImportMessageCard(title: String, body: String, error: Boolean = false) {
    RescueAuthCard(containerColor = if (error) MaterialTheme.colorScheme.errorContainer else CardTokens.containerColor()) {
        RescueAuthIconHeader(if (error) Icons.Filled.ErrorOutline else Icons.Filled.CheckCircle, title, body,
            iconContainerColor = if (error) MaterialTheme.colorScheme.errorContainer else CardTokens.actionPrimaryContainerColor(),
            iconContentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else CardTokens.actionPrimaryContentColor())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImportReviewContent(title: String, counts: ImportContentCounts, plan: ImportPlanCounts,
    metadata: List<Pair<String, String>>, developerDetails: List<Pair<String, String>>) {
    var detailsOpen by remember(counts, plan, metadata) { mutableStateOf(false) }
    RescueAuthCard(modifier = Modifier.testTag("import_plan"),
        containerColor = if (plan.blocked) MaterialTheme.colorScheme.errorContainer else CardTokens.containerColor()) {
        RescueAuthIconHeader(if (plan.blocked) Icons.Filled.ErrorOutline else Icons.Filled.FileDownload,
            if (plan.blocked) stringResource(R.string.import_review_blocked) else title,
            if (plan.blocked) null else stringResource(R.string.import_review_merge_hint),
            modifier = Modifier.testTag("summary_card_header"),
            iconContainerColor = if (plan.blocked) MaterialTheme.colorScheme.errorContainer else CardTokens.actionPrimaryContainerColor(),
            iconContentColor = if (plan.blocked) MaterialTheme.colorScheme.onErrorContainer else CardTokens.actionPrimaryContentColor())
        if (plan.blocked) {
            val reasons = listOfNotNull(
                plan.conflicts.takeIf { it > 0 }?.let { stringResource(R.string.import_conflict_count, it) },
                plan.divergences.takeIf { it > 0 }?.let { stringResource(R.string.import_divergence_count, it) },
            ).joinToString("\n")
            Text(reasons + "\n" + stringResource(R.string.import_review_blocked_hint),
                Modifier.padding(top = Spacing.md).testTag("import_blocked_reason"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        } else {
            FlowRow(Modifier.fillMaxWidth().padding(top = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ImportMetric(stringResource(R.string.preview_inserts), plan.inserts)
                ImportMetric(stringResource(R.string.import_duplicates_short), plan.duplicates)
                if (plan.unchanged > 0) ImportMetric(stringResource(R.string.preview_unchanged), plan.unchanged)
            }
        }
    }
    ImportContentsCard(counts)
    RescueAuthCard(contentPadding = CardTokens.noPadding) {
        Row(Modifier.fillMaxWidth().testTag("import_details_toggle").clickable { detailsOpen = !detailsOpen }.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Icon(Icons.Filled.Info, null, Modifier.size(Spacing.lg), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.import_details_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Icon(if (detailsOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        }
        if (detailsOpen) Column(Modifier.padding(Spacing.md).testTag("import_details_content"),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            metadata.forEach { (label, value) -> RescueAuthSummaryRow(label, value) }
            RescueAuthDivider()
            val contentRows = listOf(
                stringResource(R.string.authenticator_accounts_heading) to counts.accounts.toString(),
                stringResource(R.string.import_codes_label) to counts.codes.toString(),
                stringResource(R.string.preview_recovery_sets) to counts.recoverySets.toString(),
                stringResource(R.string.preview_recovery_codes) to counts.recoveryCodes.toString(),
                stringResource(R.string.preview_developer) to counts.developer.toString(),
            ) + developerDetails
            contentRows.forEach { (label, value) -> RescueAuthSummaryRow(label, value) }
            RescueAuthDivider()
            listOf(
                stringResource(R.string.preview_inserts) to plan.inserts,
                stringResource(R.string.preview_duplicates) to plan.duplicates,
                stringResource(R.string.preview_conflicts) to plan.conflicts,
                stringResource(R.string.preview_unchanged) to plan.unchanged,
                stringResource(R.string.preview_divergences) to plan.divergences,
            ).forEach { (label, value) -> RescueAuthSummaryRow(label, value.toString()) }
        }
    }
}

@Composable
private fun ImportMetric(label: String, count: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(count.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ImportContentsCard(counts: ImportContentCounts) {
    RescueAuthCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md), modifier = Modifier.testTag("import_content_counts")) {
            Text(stringResource(R.string.import_content_heading), style = MaterialTheme.typography.titleSmall)
            if (counts.accounts > 0) ImportCountRow(Icons.Filled.Person, stringResource(R.string.authenticator_accounts_heading), counts.accounts)
            if (counts.codes > 0) ImportCountRow(Icons.Filled.Timer, stringResource(R.string.import_codes_label), counts.codes)
            if (counts.recoverySets > 0) ImportCountRow(Icons.Filled.Shield, stringResource(R.string.preview_recovery_sets),
                counts.recoverySets, stringResource(R.string.import_recovery_code_count, counts.recoveryCodes))
            if (counts.developer > 0) ImportCountRow(Icons.Filled.Code, stringResource(R.string.preview_developer), counts.developer)
            if (counts == ImportContentCounts(0, 0, 0, 0, 0)) Text(stringResource(R.string.select_nothing_available),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ImportCountRow(icon: ImageVector, label: String, count: Int, detail: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Icon(icon, null, Modifier.size(Spacing.lg), tint = CardTokens.actionPrimaryContentColor())
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(count.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
    }
}
