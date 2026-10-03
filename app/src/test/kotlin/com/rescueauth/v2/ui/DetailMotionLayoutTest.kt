package com.rescueauth.v2.ui

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.navigation.RescueAuthNavigationMotion
import com.rescueauth.v2.ui.navigation.motionDestination
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The same SharedTransition/NavHost arrangement as the app, with static page content. */
@OptIn(ExperimentalSharedTransitionApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DetailMotionLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test fun movingDetailLayersDoNotRemeasureOrPlaceTheirContentOnEveryFrame() {
        lateinit var navigation: NavHostController
        var measures = 0
        var placements = 0
        rule.setContent {
            navigation = rememberNavController()
            val motion = remember { RescueAuthNavigationMotion(LayoutDirection.Ltr) }
            SharedTransitionLayout {
                NavHost(navigation, startDestination = "root", modifier = Modifier.fillMaxSize(),
                    enterTransition = { motion.enter(false) }, exitTransition = { motion.exit(false) },
                    popEnterTransition = { motion.enter(true) }, popExitTransition = { motion.exit(true) }) {
                    motionDestination(motion, "root") { Box(Modifier.fillMaxSize().testTag("root_page")) }
                    motionDestination(motion, "detail") {
                        Box(Modifier.fillMaxSize().testTag("detail_page").onPlaced { placements++ }
                            .layout { measurable, constraints ->
                                measures++
                                val child = measurable.measure(constraints)
                                layout(child.width, child.height) { child.place(0, 0) }
                            })
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { navigation.navigate("detail") }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(32)
        rule.waitForIdle()
        val initialMeasures = measures
        val initialPlacements = placements
        val before = rule.onNodeWithTag("detail_page").getUnclippedBoundsInRoot()
        repeat(4) {
            rule.mainClock.advanceTimeBy(32)
            rule.waitForIdle()
            assertEquals(initialMeasures, measures)
            assertEquals(initialPlacements, placements)
        }
        assertTrue(rule.onNodeWithTag("detail_page").getUnclippedBoundsInRoot().left < before.left)
        rule.mainClock.advanceTimeBy(240)
        rule.onNodeWithTag("root_page").assertDoesNotExist()
        rule.onNodeWithTag("detail_page").assertIsDisplayed()
        rule.runOnIdle { navigation.popBackStack() }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(64)
        rule.onNodeWithTag("root_page").assertExists()
        rule.onNodeWithTag("detail_page").assertExists()
        rule.mainClock.advanceTimeBy(240)
        rule.onNodeWithTag("detail_page").assertDoesNotExist()
        rule.onNodeWithTag("root_page").assertIsDisplayed()
    }
}
