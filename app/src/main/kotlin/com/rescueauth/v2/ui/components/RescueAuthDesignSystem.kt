package com.rescueauth.v2.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.ui.theme.CornerRadius
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.Spacing
import com.rescueauth.v2.R

/**
 * Shared visual primitives for the application shell.
 *
 * The product is a utility, so the primitives deliberately favour a quiet
 * background, one clear accent, compact metadata, and strong scan order. They
 * are kept independent from navigation and domain models so every route can
 * use the same language without coupling UI to storage.
 */

@Composable
fun RescueAuthPageBackground(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(content = content)
    }
}

/**
 * Quiet decorative ribbons used behind welcome / empty states. The strokes are
 * intentionally low contrast so they add depth without competing with vault
 * content or reducing text contrast.
 */
@Composable
fun RescueAuthRibbonBackdrop(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val blue = colors.primary.copy(alpha = 0.055f)
    val violet = colors.secondary.copy(alpha = 0.045f)
    val cyan = colors.tertiary.copy(alpha = 0.04f)
    Canvas(modifier = modifier) {
        val first = Path().apply {
            moveTo(size.width * -0.12f, size.height * 0.28f)
            cubicTo(size.width * 0.24f, size.height * 0.05f, size.width * 0.7f, size.height * 0.62f, size.width * 1.12f, size.height * 0.34f)
        }
        val second = Path().apply {
            moveTo(size.width * -0.12f, size.height * 0.34f)
            cubicTo(size.width * 0.28f, size.height * 0.11f, size.width * 0.72f, size.height * 0.7f, size.width * 1.12f, size.height * 0.4f)
        }
        val third = Path().apply {
            moveTo(size.width * -0.08f, size.height * 0.4f)
            cubicTo(size.width * 0.33f, size.height * 0.2f, size.width * 0.72f, size.height * 0.76f, size.width * 1.1f, size.height * 0.48f)
        }
        drawPath(first, blue, style = Stroke(width = 26.dp.toPx()))
        drawPath(second, violet, style = Stroke(width = 18.dp.toPx()))
        drawPath(third, cyan, style = Stroke(width = 12.dp.toPx()))
    }
}

@Composable
fun RescueAuthPageHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = ScreenTokens.horizontalPadding,
                    end = ScreenTokens.horizontalPadding,
                    top = 10.dp,
                    bottom = 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            navigationIcon?.invoke()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(ScreenTokens.headerGap))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
        }
        RescueAuthDivider()
        }
    }
}

/**
 * The single back affordance used by secondary pages.
 *
 * Keeping this in the design system prevents each route from reimplementing
 * icon sizing, semantics, and the localized label slightly differently.
 */
@Composable
fun RescueAuthBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.IconButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = androidx.compose.ui.res.stringResource(R.string.a11y_back),
        )
    }
}

@Composable
fun RescueAuthSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xxs),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(Spacing.xxs))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun RescueAuthIconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
    contentColor: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(CornerRadius.xs))
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Small circular identity mark used for providers and developer categories. */
@Composable
fun RescueAuthInitialBadge(
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    val initial = label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = modifier
            .size(size)
            .clip(androidx.compose.material3.MaterialTheme.shapes.small)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = contentColor,
        )
    }
}

/**
 * Bright, hue-varied palette for auto badges (UI polish 2026-09).
 *
 * Each entry is a (container, content) pair: a light vivid background with a
 * deep letter/glyph of the same hue family — replacing the muddy M3
 * `secondaryContainer` grey-purple the user explicitly rejected.
 * Tones are tuned for the light theme; dark-theme variants are deferred
 * until the dark palette work happens.
 */
object RescueAuthBadgePalette {
    private val pairs = listOf(
        0xFFE6DBFF to 0xFF5A2ECF, // violet
        0xFFD6E6FF to 0xFF1A56C4, // blue
        0xFFD2F3FF to 0xFF036C8F, // cyan
        0xFFCCF5EA to 0xFF007A5E, // teal
        0xFFDCF5CC to 0xFF2F6B14, // green
        0xFFFFE9C7 to 0xFF8F5A00, // amber
        0xFFFFE0CC to 0xFF9A3D0C, // orange
        0xFFFFDCEC to 0xFFA8155F, // pink
    )

    private val colors = pairs.map { Color(it.first) to Color(it.second) }

    /** Stable per-name colour: same name always gets the same hue. */
    fun forName(seed: String): Pair<Color, Color> {
        val h = seed.hashCode()
        val index = ((h % colors.size) + colors.size) % colors.size
        return colors[index]
    }
}

/**
 * Circular identity badge with the auto colour palette and optional brand
 * glyph. With [iconRes] == null the uppercase initial is drawn; otherwise the
 * monochrome brand VectorDrawable is tinted with the palette content colour.
 */
@Composable
fun RescueAuthAutoBadge(
    label: String,
    modifier: Modifier = Modifier,
    colorSeed: String = label,
    iconRes: Int? = null,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
) {
    val (lightContainer, lightContent) = RescueAuthBadgePalette.forName(colorSeed)
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val container = if (dark) lightContainer.copy(alpha = 0.12f) else lightContent.copy(alpha = 0.07f)
    val content = if (dark) lightContainer else lightContent
    Box(
        modifier = modifier
            .size(size)
            .clip(androidx.compose.material3.MaterialTheme.shapes.small)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        if (iconRes != null) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(iconRes),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(iconSize),
            )
        } else {
            val initial = label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = content,
            )
        }
    }
}

@Composable
fun RescueAuthMetaPill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(CornerRadius.pill),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun RescueAuthMetric(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (icon != null) {
            RescueAuthIconBadge(
                icon = icon,
                size = 36.dp,
                iconSize = 18.dp,
            )
        }
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun RescueAuthStatusLine(
    text: String,
    modifier: Modifier = Modifier,
    positive: Boolean = true,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Icon(
            imageVector = if (positive) Icons.Filled.CheckCircle else Icons.Filled.Lock,
            contentDescription = null,
            tint = if (positive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun RescueAuthChevron(
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = Icons.Filled.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(20.dp),
    )
}

@Composable
fun RescueAuthDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        color = com.rescueauth.v2.ui.theme.CardTokens.outlineColor(),
        thickness = com.rescueauth.v2.ui.theme.CardTokens.borderWidth,
    )
}
