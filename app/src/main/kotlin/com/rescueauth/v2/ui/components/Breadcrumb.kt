package com.rescueauth.v2.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.theme.CornerRadius
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.Spacing

/**
 * A single logical breadcrumb segment.
 *
 * [label] is the human-readable segment text. [destination] is the navigation
 * route/identity an ancestor navigates back to when tapped; it is `null` for
 * the current page (never clickable). [isCurrent] marks the last segment so
 * the renderer can give it the highest visual weight.
 *
 * This model deliberately represents "where the user is now" in navigation
 * terms (a logical path), not the underlying Room/foreign-key relationship
 * graph (Issue #62 §4).
 */
data class BreadcrumbItem(
    val label: String,
    val destination: String? = null,
    val isCurrent: Boolean = false,
)

/** The maximum number of visual breadcrumb segments (Issue #62 §2). */
const val MAX_BREADCRUMB_SEGMENTS = 3

/**
 * A lightweight, single-row top-level breadcrumb navigation bar (max
 * [MAX_BREADCRUMB_SEGMENTS] visual segments).
 *
 * Designed to replace the plain title of a
 * [androidx.compose.material3.TopAppBar] so the app keeps a compact header
 * while offering hierarchical path navigation (Issue #62 §9). It never adds a
 * second title row and never grows unbounded horizontally.
 *
 * Rules implemented here (and nowhere else in the app):
 *  - depth <= 3 → show the full path.
 *  - depth > 3  → collapse the earliest ancestors into a single tappable
 *    `…` segment; the last two stay visible (`… > parent > current`).
 *  - tapping `…` opens a [DropdownMenu] listing all hidden ancestors.
 *  - tapping an ancestor navigates directly to [BreadcrumbItem.destination]
 *    (via [onNavigate]); the current segment is never clickable.
 *  - long labels truncate with ellipsis; the current page gets the most space.
 *  - separators (chevrons) are decorative and excluded from the accessibility
 *    tree so TalkBack does not read them one-by-one (Issue #62 §13).
 *
 * @param items the full logical path; the last element is the current page.
 * @param onNavigate invoked with a [BreadcrumbItem.destination] when an
 *   ancestor segment is tapped.
 * @param ellipsisContentDescription accessibility label for the `…` trigger.
 * @param moreMenuContentDescription accessibility label for the menu of hidden
 *   ancestors.
 */
@Composable
fun BreadcrumbTopBar(
    items: List<BreadcrumbItem>,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    ellipsisContentDescription: String? = null,
    moreMenuContentDescription: String? = null,
) {
    if (items.isEmpty()) return

    var ellipsisMenuOpen by remember { mutableStateOf(false) }

    val showEllipsis = items.size > MAX_BREADCRUMB_SEGMENTS
    // Hidden ancestors (behind `…`) are all but the last two segments.
    val hiddenAncestors = if (showEllipsis) {
        items.subList(0, items.size - 2)
    } else {
        emptyList()
    }
    // Visible segments: either the full path (<=3) or `… , last parent, current`.
    val visible: List<BreadcrumbItem?> = if (showEllipsis) {
        // `…` (null) + the last two segments (parent + current).
        listOf(null) + items.takeLast(2)
    } else {
        items
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "breadcrumb" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visible.forEachIndexed { index, item ->
            if (index > 0) {
                BreadcrumbSeparator()
            }
            if (item == null) {
                Box {
                    EllipsisTrigger(
                        contentDescription = ellipsisContentDescription,
                        onClick = { ellipsisMenuOpen = true },
                    )
                    DropdownMenu(
                        expanded = ellipsisMenuOpen,
                        onDismissRequest = { ellipsisMenuOpen = false },
                        modifier = Modifier.semantics {
                            if (moreMenuContentDescription != null) {
                                contentDescription = moreMenuContentDescription
                            }
                        },
                    ) {
                        hiddenAncestors.forEach { ancestor ->
                            DropdownMenuItem(
                                text = { Text(ancestor.label) },
                                onClick = {
                                    ellipsisMenuOpen = false
                                    ancestor.destination?.let(onNavigate)
                                },
                            )
                        }
                    }
                }
            } else if (item.isCurrent) {
                // Current page: highest visual weight, most horizontal space,
                // not clickable. Accessibility reads it as "<label>, current page".
                val currentDescription = stringResource(
                    R.string.breadcrumb_current_page,
                    item.label,
                )
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(horizontal = Spacing.xxs)
                        .semantics {
                            selected = true
                            contentDescription = currentDescription
                        },
                )
            } else {
                AncestorSegment(
                    label = item.label,
                    onClick = item.destination?.let { dest -> { onNavigate(dest) } },
                )
            }
        }
    }
}

/** A non-clickable chevron separator, excluded from the accessibility tree. */
@Composable
private fun RowScope.BreadcrumbSeparator() {
    Icon(
        imageVector = Icons.Filled.ChevronRight,
        // Decorative separator: `contentDescription = null` keeps it out of the
        // accessibility tree so TalkBack does not read chevrons one-by-one.
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.xxs),
    )
}

/**
 * A clickable ancestor segment with a generous touch target (the padded row,
 * not just the glyph).
 */
@Composable
private fun RowScope.AncestorSegment(
    label: String,
    onClick: (() -> Unit)?,
) {
    val clickable = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(CornerRadius.xs))
            .then(clickable)
            .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
    )
}

/** The tappable `…` that opens the hidden-ancestor menu. */
@Composable
private fun EllipsisTrigger(
    onClick: () -> Unit,
    contentDescription: String?,
) {
    Text(
        text = "…",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .testTag("breadcrumb-ellipsis")
            .clip(RoundedCornerShape(CornerRadius.xs))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.xs, vertical = Spacing.xxs)
            .semantics {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
    )
}

@Preview(showBackground = true)
@Composable
private fun BreadcrumbTopBarShallowPreview() {
    RescueAuthTheme {
        BreadcrumbTopBar(
            items = listOf(
                BreadcrumbItem("Settings", "settings"),
                BreadcrumbItem("Appearance", "appearance", isCurrent = true),
            ),
            onNavigate = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BreadcrumbTopBarDeepPreview() {
    RescueAuthTheme {
        BreadcrumbTopBar(
            items = listOf(
                BreadcrumbItem("Authenticator", "authenticator"),
                BreadcrumbItem("GitHub", "github"),
                BreadcrumbItem("xincy22", "xincy22"),
                BreadcrumbItem("Primary TOTP", null, isCurrent = true),
            ),
            onNavigate = {},
        )
    }
}
