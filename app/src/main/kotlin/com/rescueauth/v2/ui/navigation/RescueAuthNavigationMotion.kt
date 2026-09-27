package com.rescueauth.v2.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

/** Opaque detail pages cover/reveal a shallow back layer; home pages share the pager's motion. */
internal class RescueAuthNavigationMotion(layoutDirection: LayoutDirection) {
    private val forward = if (layoutDirection == LayoutDirection.Rtl) -1 else 1

    fun enter(isPop: Boolean): EnterTransition =
        slideInHorizontally(tween(if (isPop) BACK_MILLIS else FORWARD_MILLIS, easing = easing)) { width ->
            if (isPop) (-width * BACK_LAYER_FRACTION * forward).roundToInt() else width * forward
        }

    fun exit(isPop: Boolean): ExitTransition =
        slideOutHorizontally(tween(if (isPop) BACK_MILLIS else FORWARD_MILLIS, easing = easing)) { width ->
            if (isPop) width * forward else (-width * BACK_LAYER_FRACTION * forward).roundToInt()
        }

    private companion object {
        const val FORWARD_MILLIS = 220
        const val BACK_MILLIS = 200
        const val BACK_LAYER_FRACTION = .18f
        val easing = CubicBezierEasing(.2f, 0f, 0f, 1f)
    }
}
