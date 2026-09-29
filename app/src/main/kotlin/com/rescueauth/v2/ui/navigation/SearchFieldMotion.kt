package com.rescueauth.v2.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.rescueauth.v2.ui.theme.CardTokens

internal object SearchFieldMotion {
    const val DURATION_MILLIS = 220
    val easing = CubicBezierEasing(.2f, 0f, 0f, 1f)
    fun matches(from: String?, to: String?): Boolean =
        (from == RescueAuthRoutes.HOME && to == RescueAuthRoutes.SEARCH) ||
            (from == RescueAuthRoutes.SEARCH && to == RescueAuthRoutes.HOME)
    fun enter() = fadeIn(tween(100, delayMillis = 120, easing = easing))
    fun exit() = fadeOut(tween(90, easing = easing))
}

/** Animate the real search surfaces in one overlay, with text remeasured rather than stretched. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun SharedTransitionScope.searchFieldMotion(visibility: AnimatedVisibilityScope): Modifier =
    Modifier.sharedBounds(
        sharedContentState = rememberSharedContentState("vault-search-field"),
        animatedVisibilityScope = visibility,
        boundsTransform = { _, _ -> tween(SearchFieldMotion.DURATION_MILLIS, easing = SearchFieldMotion.easing) },
        enter = fadeIn(tween(90)), exit = fadeOut(tween(90)),
        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        clipInOverlayDuringTransition = OverlayClip(CardTokens.rowShape),
    )
