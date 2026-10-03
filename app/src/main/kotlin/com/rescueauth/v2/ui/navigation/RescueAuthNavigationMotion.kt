package com.rescueauth.v2.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NamedNavArgument
import androidx.navigation.compose.composable

/** Move opaque page layers without reading a slide offset in the layout/measure pass. */
internal class RescueAuthNavigationMotion(layoutDirection: LayoutDirection) {
    private val forward = if (layoutDirection == LayoutDirection.Rtl) -1 else 1
    private data class Segment(val isPop: Boolean = false, val sharedSearch: Boolean = false)
    private var segment by mutableStateOf(Segment())

    // NavHost resolves the direction, including predictive back. Keep equal resolutions stable:
    // deriving direction from destination history would miss an uncommitted back gesture.
    fun enter(isPop: Boolean, sharedSearch: Boolean = false): EnterTransition {
        segment = Segment(isPop, sharedSearch)
        return if (sharedSearch) SearchFieldMotion.enter() else EnterTransition.None
    }

    fun exit(isPop: Boolean, sharedSearch: Boolean = false): ExitTransition {
        segment = Segment(isPop, sharedSearch)
        return if (sharedSearch) SearchFieldMotion.exit() else ExitTransition.None
    }

    @Composable
    fun Page(scope: AnimatedVisibilityScope, opaque: Boolean, content: @Composable () -> Unit) {
        val spec = segment
        // Register on NavHost's child transition so it owns completion/disposal and seeking.
        val fraction = scope.transition.animateFloat(
            transitionSpec = { tween(if (spec.isPop) BACK_MILLIS else FORWARD_MILLIS, easing = FastOutSlowInEasing) },
            label = "navigation page translation",
        ) { state ->
            if (spec.sharedSearch) 0f else when (state) {
                EnterExitState.PreEnter -> if (spec.isPop) -BACK_LAYER_FRACTION else 1f
                EnterExitState.Visible -> 0f
                EnterExitState.PostExit -> if (spec.isPop) 1f else -BACK_LAYER_FRACTION
            }
        }
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationX = fraction.value * size.width * forward
        }.then(if (opaque) Modifier.background(MaterialTheme.colorScheme.background) else Modifier)) { content() }
    }

    private companion object {
        const val FORWARD_MILLIS = 220
        const val BACK_MILLIS = 200
        const val BACK_LAYER_FRACTION = .18f
    }
}

/** Preserve Navigation's route, saved-state, lifecycle and security owners inside the moving layer. */
internal fun NavGraphBuilder.motionDestination(
    motion: RescueAuthNavigationMotion,
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(route, arguments) { entry ->
        val scope = this
        motion.Page(scope, opaque = route != RescueAuthRoutes.HOME) { content(scope, entry) }
    }
}
