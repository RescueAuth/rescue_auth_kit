package com.rescueauth.v2.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.Spacing

/** Decorative identity colors; semantic text and controls still use the theme. */
object StudioColors {
    val ink = Color(0xFF242A38)
    val onInk = Color(0xFFF8F7F2)
    val mutedInk = Color(0xFFBCBFCC)
    val lavender = Color(0xFFC1B4F3)
}

/** Finite entrance, respecting Compose's system animation duration scale. */
@Composable
fun StudioEntrance(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(440, easing = FastOutSlowInEasing))
    }
    Box(
        modifier = modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 12.dp.toPx()
        },
        content = content,
    )
}

/** Same SVG-derived mark on banners, welcome, launch chrome and the Android app icon. */
@Composable
fun RescueAuthMark(modifier: Modifier = Modifier, animated: Boolean = true, monochrome: Boolean = false) {
    val settle = remember { Animatable(if (animated) 0f else 1f) }
    LaunchedEffect(animated) {
        if (animated) settle.animateTo(1f, tween(600, easing = FastOutSlowInEasing)) else settle.snapTo(1f)
    }
    val markModifier = modifier.graphicsLayer {
        alpha = settle.value
        scaleX = 0.92f + 0.08f * settle.value
        scaleY = scaleX
    }
    if (monochrome) {
        Icon(androidx.compose.ui.res.painterResource(com.rescueauth.v2.R.drawable.ic_rescueauth_mark_mono),
            contentDescription = null, modifier = markModifier, tint = MaterialTheme.colorScheme.onBackground)
    } else {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.rescueauth.v2.R.drawable.ic_rescueauth_mark),
            contentDescription = null, modifier = markModifier,
        )
    }
}

@Composable
fun StudioEyebrow(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, modifier, color = color, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
}

/** A single visual anchor above the directory, with no repeated statistics. */
@Composable
fun StudioVaultHero(
    eyebrow: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    val bannerHeight = CardTokens.bannerHeight + CardTokens.bannerLargeTextGrowth * (fontScale - 1f).coerceAtLeast(0f)
    RescueAuthCard(
        modifier = modifier.height(bannerHeight),
        containerColor = StudioColors.ink,
        shape = CardTokens.heroShape,
        contentPadding = CardTokens.bannerPadding,
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Canvas(Modifier.size(6.dp)) { drawCircle(StudioColors.lavender) }
                    StudioEyebrow(eyebrow, color = StudioColors.mutedInk)
                }
                Text(title, color = StudioColors.onInk, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = StudioColors.mutedInk, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            RescueAuthMark(Modifier.padding(start = Spacing.xs).size(CardTokens.bannerArtworkSize))
        }
    }
}

@Composable
fun StudioAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    RescueAuthButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 54.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.onBackground,
            contentColor = MaterialTheme.colorScheme.background,
        ),
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, Modifier.size(20.dp))
    }
}
