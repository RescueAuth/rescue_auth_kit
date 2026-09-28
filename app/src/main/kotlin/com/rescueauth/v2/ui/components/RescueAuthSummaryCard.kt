package com.rescueauth.v2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.Spacing

/** Presentation-only labeled values. Never accepts a package, a parser result or a Vault entity. */
@Composable
fun RescueAuthSummaryCard(title: String, rows: List<Pair<String, String>>, icon: ImageVector? = null) {
    RescueAuthCard {
        Column(verticalArrangement = Arrangement.spacedBy(CardTokens.listSpacing)) {
            if (icon != null) RescueAuthIconHeader(icon, title, modifier = Modifier.testTag("summary_card_header"))
            else RescueAuthSectionHeader(title = title)
            rows.forEach { (label, value) -> RescueAuthSummaryRow(label, value) }
        }
    }
}

@Composable
fun RescueAuthSummaryRow(label: String, value: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val valueMaxWidth = maxWidth * 0.55f
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.widthIn(max = valueMaxWidth), contentAlignment = Alignment.CenterEnd) {
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
