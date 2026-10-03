package com.rescueauth.v2.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.Spacing

/** Decorative identity colors; semantic text and controls still use the theme. */
object StudioColors {
    val ink = Color(0xFF242A38)
    val onInk = Color(0xFFF8F7F2)
    val mutedInk = Color(0xFFBCBFCC)
    val lavender = Color(0xFFC1B4F3)
}

/** E2 ribbon key, generated from the editable path-and-gradient SVG master. */
@Composable
fun RescueAuthMark(modifier: Modifier = Modifier, animated: Boolean = false, monochrome: Boolean = false) {
    val markModifier = if (animated) {
        val settle = remember { Animatable(0f) }
        LaunchedEffect(Unit) { settle.animateTo(1f, tween(CardTokens.bannerEnterDurationMillis)) }
        modifier.graphicsLayer { alpha = settle.value }
    } else modifier
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

/** The selected transparent Chinese wordmark; other locales retain native text. */
@Composable
fun RescueAuthWordmark(modifier: Modifier = Modifier, prominent: Boolean = false) {
    val name = stringResource(R.string.app_name)
    // Follow the resolved product-name resource, including Android's locale fallback.
    if (name == "拾遗坊") {
        val bitmap = ImageBitmap.imageResource(R.drawable.shiyifang_wordmark)
        val painter = remember(bitmap) {
            // Trim only the drawing viewport, keeping the original RGBA asset intact.
            // The inset leaves 12 px around the visible brushwork and its soft edges.
            BitmapPainter(bitmap, srcOffset = IntOffset(133, 91), srcSize = IntSize(1847, 579))
        }
        val height = (if (prominent) CardTokens.welcomeWordmarkHeight else CardTokens.bannerWordmarkHeight) *
            LocalDensity.current.fontScale
        val colorFilter = if (MaterialTheme.colorScheme.background.luminance() < 0.5f)
            ColorFilter.tint(MaterialTheme.colorScheme.onSurface) else null
        Box(modifier, contentAlignment = if (prominent) Alignment.Center else Alignment.CenterStart) {
            Image(painter, contentDescription = name,
                modifier = Modifier.height(height).width(height * (1847f / 579f)),
                contentScale = ContentScale.Fit, colorFilter = colorFilter)
        }
    } else {
        Text(name, modifier = modifier,
            color = MaterialTheme.colorScheme.onSurface,
            style = if (prominent) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Brand artwork panel used by the About page. */
@Composable
fun StudioBrandArtwork(modifier: Modifier = Modifier) {
    RescueAuthCard(modifier = modifier, shape = CardTokens.heroShape,
        contentPadding = CardTokens.noPadding, containerColor = StudioColors.ink) {
        Box(Modifier.fillMaxWidth().height(CardTokens.brandArtworkSize)) {
            RescueAuthMark(Modifier.align(Alignment.Center).size(CardTokens.brandArtworkSize))
        }
        StudioIconLabel(Icons.Outlined.PhoneAndroid, stringResource(R.string.compact_on_device),
            Modifier.padding(start = CardTokens.heroPadding, end = CardTokens.heroPadding, bottom = Spacing.lg),
            StudioColors.mutedInk)
    }
}

@Composable
fun StudioEyebrow(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, modifier, color = color, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
}

/** Brand card with quiet cropped artwork behind the content, without an extra icon slot. */
@Composable
fun StudioVaultHero(
    modifier: Modifier = Modifier,
    accountCount: Int? = null,
) {
    val fontScale = LocalDensity.current.fontScale
    val bannerHeight = CardTokens.bannerHeight + CardTokens.bannerLargeTextGrowth * (fontScale - 1f).coerceAtLeast(0f)
    val motif = painterResource(R.drawable.ic_rescueauth_mark)
    val motifAlpha = CardTokens.bannerMotifAlpha()
    val washColor = CardTokens.bannerWashColor()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val reveal = rememberBrandEntrance()
    RescueAuthCard(
        modifier = modifier.testTag("vault_brand_banner").heightIn(min = bannerHeight),
        containerColor = CardTokens.containerColor(),
        shape = CardTokens.shape,
        contentPadding = CardTokens.noPadding,
    ) {
        Box(Modifier.fillMaxWidth().drawWithCache {
            val stops = listOf(Color.Transparent, Color.Transparent, washColor)
            val wash = Brush.horizontalGradient(if (rtl) stops.reversed() else stops)
            val artworkSize = CardTokens.bannerMotifSize.toPx()
            val artworkX = if (rtl) -CardTokens.bannerMotifEndOffset.toPx()
                else size.width - artworkSize + CardTokens.bannerMotifEndOffset.toPx()
            val artworkY = CardTokens.bannerMotifTopOffset.toPx()
            onDrawBehind {
                // Text and account totals are readable from the first frame. Only the
                // decorative paint fades, without an extra layer over the whole card.
                drawRect(wash, alpha = reveal.value)
                withTransform({ translate(artworkX, artworkY) }) {
                    with(motif) { draw(Size(artworkSize, artworkSize), alpha = motifAlpha * reveal.value) }
                }
            }
        }) {
            Row(Modifier.fillMaxWidth()
                .padding(horizontal = CardTokens.bannerHorizontalPadding, vertical = CardTokens.bannerPadding)
                .heightIn(min = bannerHeight - CardTokens.bannerPadding * 2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                RescueAuthWordmark(Modifier.weight(1f))
                if (accountCount != null) {
                    val description = pluralStringResource(R.plurals.banner_account_count, accountCount, accountCount)
                    Column(Modifier.testTag("vault_banner_accounts").clearAndSetSemantics {
                        contentDescription = description
                    }, horizontalAlignment = Alignment.End) {
                        Text(accountCount.toString(), style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.compact_accounts), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Navigation saves this with the page: returning must not replay decorative work under a slide. */
@Composable
internal fun rememberBrandEntrance(): State<Float> {
    var appeared by rememberSaveable { mutableStateOf(false) }
    val alpha = remember { Animatable(if (appeared) 1f else 0f) }
    LaunchedEffect(Unit) {
        appeared = true
        if (alpha.value < 1f) alpha.animateTo(1f, tween(CardTokens.bannerEnterDurationMillis))
    }
    return alpha.asState()
}

/** Compact visual metadata, with the complete localized meaning retained for TalkBack. */
@Composable
fun StudioIconCount(
    icon: ImageVector,
    count: Int,
    description: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Icon(icon, null, Modifier.size(16.dp), tint = color)
        Text(count.toString(), color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun StudioCountBadge(count: Int, description: String, modifier: Modifier = Modifier) {
    Surface(modifier.clearAndSetSemantics { contentDescription = description },
        shape = CardTokens.rowShape, color = MaterialTheme.colorScheme.background.copy(alpha = 0.65f)) {
        Text(count.toString(), Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun StudioIconLabel(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Icon(icon, null, Modifier.size(18.dp), tint = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
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
