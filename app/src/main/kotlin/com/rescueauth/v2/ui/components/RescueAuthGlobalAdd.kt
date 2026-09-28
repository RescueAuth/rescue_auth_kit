package com.rescueauth.v2.ui.components

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.theme.AddActionTokens
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.Spacing
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Normalized coordinates remain in bounds across size/density changes; contains no Vault data. */
@Stable
class FloatingAddPosition(x: Float = 1f, y: Float = 1f) {
    var xFraction by mutableFloatStateOf(x)
    var yFraction by mutableFloatStateOf(y)
}

@Composable
fun rememberFloatingAddPosition(): FloatingAddPosition = rememberSaveable(saver = listSaver(
    save = { listOf(it.xFraction, it.yFraction) },
    restore = { FloatingAddPosition(it[0], it[1]) },
)) { FloatingAddPosition() }

/** The overlay itself does not intercept touches; only the 56 dp button owns a pointer handler. */
@Composable
fun RescueAuthFloatingAddOverlay(
    visible: Boolean,
    enabled: Boolean,
    position: FloatingAddPosition,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()
    var snapJob by remember { mutableStateOf<Job?>(null) }
    BoxWithConstraints(modifier.fillMaxSize().statusBarsPadding()
        .padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md,
            top = ScreenTokens.controlMinHeight * density.fontScale.coerceAtLeast(1f) + Spacing.md * 2),
        contentAlignment = AbsoluteAlignment.TopLeft) {
        val travelX = with(density) { (maxWidth - AddActionTokens.size).toPx().coerceAtLeast(0f) }
        val travelY = with(density) { (maxHeight - AddActionTokens.size).toPx().coerceAtLeast(0f) }
        val settle: () -> Unit = {
            snapJob?.cancel()
            snapJob = scope.launch {
                animate(position.xFraction, if (position.xFraction < .5f) 0f else 1f,
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = 600f)) { value, _ ->
                    position.xFraction = value.coerceIn(0f, 1f)
                }
            }
        }
        Box(Modifier.absoluteOffset {
            IntOffset(((if (rtl) 1f - position.xFraction else position.xFraction) * travelX).roundToInt(),
                (position.yFraction * travelY).roundToInt())
        }.pointerInput(enabled, visible, rtl, travelX, travelY) {
            if (enabled && visible) detectDragGestures(
                onDragStart = { snapJob?.cancel() },
                onDragEnd = settle,
                onDragCancel = settle,
            ) { change, delta ->
                change.consume()
                if (travelX > 0f) position.xFraction =
                    (position.xFraction + delta.x / travelX * if (rtl) -1f else 1f).coerceIn(0f, 1f)
                if (travelY > 0f) position.yFraction =
                    (position.yFraction + delta.y / travelY).coerceIn(0f, 1f)
            }
        }) {
            RescueAuthGlobalAdd(visible, enabled, onClick)
        }
    }
}

/** Owned by the shell, outside the pager. Animated values are read only during drawing. */
@Composable
fun RescueAuthGlobalAdd(visible: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val transition = updateTransition(visible, label = "global add visibility")
    val scale = transition.animateFloat(transitionSpec = {
        if (targetState) spring(dampingRatio = AddActionTokens.scaleDamping, stiffness = AddActionTokens.stiffness)
        else tween(AddActionTokens.exitMillis, easing = FastOutLinearInEasing)
    }, label = "add scale") { if (it) 1f else 0f }
    val rotation = transition.animateFloat(transitionSpec = {
        if (targetState) spring(dampingRatio = AddActionTokens.rotationDamping, stiffness = AddActionTokens.stiffness)
        else tween(AddActionTokens.exitMillis, easing = FastOutLinearInEasing)
    }, label = "add rotation") { if (it) 0f else AddActionTokens.hiddenRotation }

    // Keep the slot geometry stable through the exit, but remove its touch/semantics node at rest.
    Box(Modifier.size(AddActionTokens.size)) {
        if (transition.currentState || transition.targetState) {
            Surface(
                onClick = onClick,
                enabled = visible && enabled,
                shape = CardTokens.shape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = AddActionTokens.elevation,
                modifier = Modifier.size(AddActionTokens.size).graphicsLayer {
                    scaleX = scale.value.coerceAtLeast(0f)
                    scaleY = scale.value.coerceAtLeast(0f)
                    rotationZ = rotation.value
                    // Keep alpha at 1: an offscreen alpha layer would clip the Surface's shadow while shrinking.
                }.testTag("global_add").semantics { role = Role.Button },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, stringResource(R.string.studio_add), Modifier.size(AddActionTokens.iconSize))
                }
            }
        }
    }
}
