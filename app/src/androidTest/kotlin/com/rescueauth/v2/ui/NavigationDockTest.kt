package com.rescueauth.v2.ui

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rescueauth.v2.ui.components.RescueAuthNavigationBar
import com.rescueauth.v2.ui.navigation.TopLevelDestination
import com.rescueauth.v2.ui.navigation.TopLevelDestinations
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class NavigationDockTest {
    private val motionScale = object : MotionDurationScale { override var scaleFactor = 1f }
    @get:Rule val rule = createComposeRule(effectContext = motionScale)
    private val selected = mutableStateOf(TopLevelDestinations.AUTHENTICATOR)
    private lateinit var inputMode: InputModeManager

    private fun show(fontScale: Float = 1f, rtl: Boolean = false, width: Dp = 360.dp,
        onNavigate: (TopLevelDestination) -> Unit = { selected.value = it }) {
        rule.setContent {
            inputMode = LocalInputModeManager.current
            val base = LocalContext.current
            // Exercise the longer English labels regardless of the device's current language.
            val config = Configuration(base.resources.configuration).apply {
                setLocales(LocaleList(Locale.ENGLISH))
            }
            CompositionLocalProvider(
                LocalContext provides base.createConfigurationContext(config),
                LocalConfiguration provides config,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                RescueAuthTheme {
                    Box(Modifier.width(width)) {
                        RescueAuthNavigationBar(isSelected = { selected.value == it }, onNavigate = onNavigate)
                    }
                }
            }
        }
    }

    private fun left(tag: String) = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().left.value

    @Test fun compactDockKeepsFullTouchTargets() {
        show()
        rule.onNodeWithTag("navigation_dock").assertHeightIsEqualTo(56.dp)
        TopLevelDestinations.all.forEach {
            rule.onNodeWithTag(it.testTag).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test fun largeLabelsCanGrowInsteadOfBeingClipped() {
        show(fontScale = 1.5f)
        rule.onNodeWithTag("navigation_dock").assertHeightIsAtLeast(56.dp)
        listOf("Accounts", "Developer", "Settings").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        TopLevelDestinations.all.forEach { rule.onNodeWithTag(it.testTag).assertHeightIsAtLeast(48.dp) }
    }

    @Test fun narrowViewportKeepsAllLabelsAndTouchTargets() {
        show(width = 320.dp)
        listOf("Accounts", "Developer", "Settings").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        TopLevelDestinations.all.forEach { rule.onNodeWithTag(it.testTag).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp) }
    }

    @Test fun indicatorSlidesAndRetargetsDuringRapidSwitches() {
        show()
        val start = left("navigation_indicator")
        val end = left(RescueAuthTestTags.NAV_SETTINGS)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(80)
        val middle = left("navigation_indicator")
        assertTrue("The pill must visibly travel between cells: $start → $middle → $end", middle > start && middle < end)
        rule.onNodeWithTag(RescueAuthTestTags.NAV_DEVELOPER).performClick()
        rule.mainClock.advanceTimeBy(400)
        assertEquals(left(RescueAuthTestTags.NAV_DEVELOPER), left("navigation_indicator"), 1f)
        rule.onNodeWithTag(RescueAuthTestTags.NAV_DEVELOPER).assertIsSelected()
    }

    @Test fun slideMirrorsTheActualCellsInRtl() {
        show(rtl = true)
        val start = left("navigation_indicator")
        val end = left(RescueAuthTestTags.NAV_SETTINGS)
        assertTrue(start > end)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(80)
        val middle = left("navigation_indicator")
        assertTrue("RTL positions: $start → $middle → $end", middle < start && middle > end)
        rule.mainClock.advanceTimeBy(400)
        assertEquals(end, left("navigation_indicator"), 1f)
    }

    @Test fun navigationDoesNotWaitForTheSlide() {
        var navigated: TopLevelDestination? = null
        show(onNavigate = { navigated = it; selected.value = it })
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).performClick()
        rule.mainClock.advanceTimeByFrame()
        assertEquals(TopLevelDestinations.SETTINGS, navigated)
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).assertIsSelected()
        assertTrue(left("navigation_indicator") < left(RescueAuthTestTags.NAV_SETTINGS))
    }

    @Test fun disabledSystemAnimationsSnapToTheDestination() {
        motionScale.scaleFactor = 0f
        show()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).performClick()
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        assertEquals(left(RescueAuthTestTags.NAV_SETTINGS), left("navigation_indicator"), 1f)
    }

    @Test fun holdingAnUnselectedTabDoesNotPaintAnExtraBackground() {
        show()
        val before = rule.onNodeWithTag("navigation_dock").captureToImage().toPixelMap()
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).performTouchInput { down(center) }
            rule.mainClock.advanceTimeBy(160)
            val pressed = rule.onNodeWithTag("navigation_dock").captureToImage().toPixelMap()
            var differentPixels = 0
            for (y in 0 until before.height) for (x in 0 until before.width) {
                if (before[x, y] != pressed[x, y]) differentPixels++
            }
            assertEquals("Pressing must not add a ripple beneath the sliding pill", 0, differentPixels)
            rule.onNodeWithTag(RescueAuthTestTags.NAV_AUTHENTICATOR).assertIsSelected()
        } finally {
            rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).performTouchInput { cancel() }
            rule.mainClock.autoAdvance = true
        }
    }

    @Test fun keyboardFocusStillHasAVisibleCueAndCanActivateTheTab() {
        show()
        val before = rule.onNodeWithTag("navigation_dock").captureToImage().toPixelMap()
        rule.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).assertIsFocused()
        val focused = rule.onNodeWithTag("navigation_dock").captureToImage().toPixelMap()
        var differentPixels = 0
        for (y in 0 until before.height) for (x in 0 until before.width) {
            if (before[x, y] != focused[x, y]) differentPixels++
        }
        assertTrue("Keyboard focus must remain visible without touch ripples", differentPixels > 0)
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).performKeyInput { pressKey(Key.Enter) }
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).assertIsSelected()
    }
}
