package com.rescueauth.v2.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import com.rescueauth.v2.ui.theme.DockTokens
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** Button-driven home navigation. Page travel is one viewport even when the Dock skips a tab. */
@Stable
internal class HomePageMotionState(initialPage: Int, val pageCount: Int) {
    init { require(initialPage in 0 until pageCount) }

    private class Travel(
        val starts: List<Float>,
        val ends: List<Float>,
        val dockStart: Float,
        val target: Int,
        val progress: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    )

    private fun resting(page: Int): Travel {
        val offsets = List(pageCount) { (it - page).sign.toFloat() }
        return Travel(offsets, offsets, page.toFloat(), page, Animatable(1f))
    }

    private var travel by mutableStateOf(resting(initialPage))
    var settledPage by mutableIntStateOf(initialPage)
        private set
    val targetPage: Int get() = travel.target
    val isScrollInProgress by derivedStateOf { travel.progress.value < 1f }

    // Read these only during placement: an animation frame must not recompose / remeasure pages.
    val dockPosition: Float
        get() = travel.let { it.dockStart + (it.target - it.dockStart) * it.progress.value }

    fun pageOffset(page: Int): Float = travel.let {
        it.starts[page] + (it.ends[page] - it.starts[page]) * it.progress.value
    }

    fun snapTo(page: Int) {
        require(page in 0 until pageCount)
        travel = resting(page)
        settledPage = page
    }

    /** The shell cancels its previous navigation job before calling this again. */
    suspend fun animateTo(page: Int) {
        require(page in 0 until pageCount)
        if (page == targetPage && !isScrollInProgress) return
        val dockStart = dockPosition
        val direction = (page - dockStart).sign.takeIf { it != 0f }
            ?: (page - settledPage).sign.toFloat().takeIf { it != 0f } ?: 1f
        val starts = List(pageCount) { index ->
            val current = pageOffset(index)
            // Only the incoming page changes sides, and only while completely offscreen.
            // A reversal during motion retains every visible page's exact current position.
            if (index == page && abs(current) >= 1f) direction else current
        }
        val ends = starts.mapIndexed { index, offset ->
            when {
                index == page -> 0f
                abs(offset) < 1f -> -direction
                else -> offset // Unrelated pages must never sweep through the viewport.
            }
        }
        val next = Travel(starts, ends, dockStart, page, Animatable(0f))
        travel = next
        next.progress.animateTo(1f, tween(DockTokens.slideDurationMillis, easing = FastOutSlowInEasing))
        if (travel === next) settledPage = page
    }
}

@Composable
internal fun rememberHomePageMotionState(initialPage: Int, pageCount: Int): HomePageMotionState =
    rememberSaveable(pageCount, saver = Saver(
        save = { it: HomePageMotionState -> it.targetPage },
        restore = { HomePageMotionState(it, pageCount) },
    )) { HomePageMotionState(initialPage, pageCount) }

/** Retain the three fixed root layouts; move their layers without rebuilding page content. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun HomePages(
    state: HomePageMotionState,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    Layout(modifier = modifier.clipToBounds(), content = {
        repeat(state.pageCount) { page ->
            key(page) {
                Box(Modifier.fillMaxSize().clipToBounds().semantics {
                    @Suppress("DEPRECATION")
                    if (page != state.targetPage) invisibleToUser()
                }) { content(page) }
            }
        }
    }) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val pages = measurables.map { it.measure(Constraints.fixed(width, height)) }
        layout(width, height) {
            pages.forEachIndexed { index, page ->
                page.placeRelativeWithLayer(
                    x = (state.pageOffset(index) * width).roundToInt(), y = 0,
                    zIndex = if (index == state.targetPage) 1f else 0f,
                )
            }
        }
    }
}
