package com.rescueauth.v2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.Spacing

/** A single visual group: decorative icon, wrapping title and optional supporting text. */
@Composable
fun RescueAuthIconHeader(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    iconContainerColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
    iconContentColor: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null,
) {
    // With a long description, keep the icon anchored to the title instead of
    // letting it drift into the middle of the paragraph. Title-only rows center.
    Row(modifier.fillMaxWidth(), verticalAlignment = if (subtitle.isNullOrBlank()) Alignment.CenterVertically else Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(CardTokens.headerGap)) {
        RescueAuthIconBadge(icon, Modifier.testTag("card_header_icon"), size = CardTokens.headerBadgeSize,
            iconSize = CardTokens.headerIconSize, containerColor = iconContainerColor, contentColor = iconContentColor)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(title, Modifier.testTag("card_header_title").semantics { heading() },
                style = titleStyle, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            if (!subtitle.isNullOrBlank()) Text(subtitle, Modifier.testTag("card_header_description"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}
