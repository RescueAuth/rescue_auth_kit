package com.rescueauth.v2.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.navigation.HomePageMotionState
import com.rescueauth.v2.ui.navigation.HomePages
import com.rescueauth.v2.ui.navigation.rememberHomePageMotionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HomePageMotionLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test fun intermediateFramesOnlyPlaceRetainedPagesWithoutComposingOrMeasuringThemAgain() {
        lateinit var state: HomePageMotionState
        lateinit var scope: CoroutineScope
        val compositions = IntArray(3)
        val measurements = IntArray(3)
        rule.setContent {
            state = rememberHomePageMotionState(0, 3)
            scope = rememberCoroutineScope()
            HomePages(state, Modifier.fillMaxSize()) { page ->
                SideEffect { compositions[page]++ }
                Box(Modifier.fillMaxSize().testTag("page_$page").layout { measurable, constraints ->
                    measurements[page]++
                    val child = measurable.measure(constraints)
                    layout(child.width, child.height) { child.place(0, 0) }
                })
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { scope.launch { state.animateTo(2) } }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        val initialCompositions = compositions.toList()
        val initialMeasurements = measurements.toList()
        val start = rule.onNodeWithTag("page_2").getUnclippedBoundsInRoot().left
        repeat(4) {
            rule.mainClock.advanceTimeBy(32)
            rule.waitForIdle()
            rule.onNodeWithTag("page_1").assertIsNotDisplayed()
            assertEquals(initialCompositions, compositions.toList())
            assertEquals(initialMeasurements, measurements.toList())
        }
        assertTrue(rule.onNodeWithTag("page_2").getUnclippedBoundsInRoot().left < start)
        rule.mainClock.advanceTimeBy(240)
        rule.onNodeWithTag("page_2").assertIsDisplayed()
    }

    @Test fun recreationDuringEndpointTravelRestoresTheRequestedPageInASettledState() {
        lateinit var state: HomePageMotionState
        lateinit var scope: CoroutineScope
        val mounted = mutableStateOf(true)
        val registry = mutableStateOf(SaveableStateRegistry(restoredValues = null) { true })
        rule.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry.value) {
                if (mounted.value) {
                    state = rememberHomePageMotionState(0, 3)
                    scope = rememberCoroutineScope()
                    HomePages(state, Modifier.fillMaxSize()) { page -> Box(Modifier.fillMaxSize().testTag("page_$page")) }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { scope.launch { state.animateTo(2) } }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(64)
        rule.waitForIdle()
        assertTrue(state.isScrollInProgress)
        assertEquals(2, state.targetPage)
        // Save while the animation clock is paused, then actually dispose the old composition.
        // StateRestorationTester requires auto-advance to do this, which would finish the motion.
        lateinit var saved: Map<String, List<Any?>>
        rule.runOnIdle { saved = registry.value.performSave(); mounted.value = false }
        assertTrue("Saved navigation: $saved", saved.values.any { 2 in it })
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
        rule.onAllNodesWithTag("page_0").assertCountEquals(0)
        rule.runOnIdle {
            registry.value = SaveableStateRegistry(saved) { true }
            mounted.value = true
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals("Restored selected page", 2, state.settledPage)
        assertEquals("Restored Dock position", 2f, state.dockPosition)
        assertEquals("Restored offscreen page location", -1f, state.pageOffset(0))
        assertTrue("Restored bounds: ${rule.onNodeWithTag("page_0").getUnclippedBoundsInRoot()}",
            rule.onNodeWithTag("page_0").getUnclippedBoundsInRoot().right.value <= 0f)
        rule.onNodeWithTag("page_2").assertIsDisplayed()
        rule.onNodeWithTag("page_0").assertIsNotDisplayed()
    }
}
