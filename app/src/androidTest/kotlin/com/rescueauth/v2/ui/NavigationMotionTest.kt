package com.rescueauth.v2.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import android.os.Build
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.rescueauth.v2.ui.components.StudioVaultHero
import com.rescueauth.v2.ui.screens.about.AboutTestTags
import com.rescueauth.v2.ui.screens.settings.SettingsTestTags
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the real shell at intermediate frames, not only after animations settle. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NavigationMotionTest {
    private val motionScale = object : MotionDurationScale { override var scaleFactor = 1f }
    @get:Rule val rule = createComposeRule(effectContext = motionScale)

    private fun show(rtl: Boolean = false) {
        rule.setContent {
            val base = LocalContext.current
            val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList(Locale.ENGLISH)) }
            CompositionLocalProvider(
                LocalContext provides base.createConfigurationContext(config),
                LocalConfiguration provides config,
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) { RescueAuthTheme { RescueAuthApp(versionName = "1.0.0") } }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
    private fun midFrame() { rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeBy(80) }
    private fun finish() { rule.mainClock.advanceTimeBy(240); rule.waitForIdle() }
    private fun about() = rule.onNodeWithTag(SettingsTestTags.ABOUT_ROW).performScrollTo().performClick()
    private fun back() = rule.onNodeWithContentDescription("Back").performClick()

    private fun dockPosition(): Float {
        val first = bounds(RescueAuthTestTags.NAV_AUTHENTICATOR).left.value
        val second = bounds(RescueAuthTestTags.NAV_DEVELOPER).left.value
        return (bounds("navigation_indicator").left.value - first) / (second - first)
    }

    private fun assertDockTracksPages(rtl: Boolean = false, from: Int? = null, to: Int? = null) {
        val viewport = rule.onRoot().getUnclippedBoundsInRoot()
        val width = (viewport.right - viewport.left).value
        val tags = listOf(RescueAuthTestTags.SCREEN_AUTHENTICATOR,
            RescueAuthTestTags.SCREEN_DEVELOPER, RescueAuthTestTags.SCREEN_SETTINGS)
        val visible = tags.mapIndexedNotNull { index, tag ->
            if (rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) null else {
                val page = bounds(tag)
                if (page.right > viewport.left && page.left < viewport.right) index to page else null
            }
        }.first()
        val direction = if (rtl) -1 else 1
        val distance = if (from != null && to != null) kotlin.math.abs(to - from) else 1
        val pagePosition = visible.first -
            (visible.second.left - viewport.left).value / (width * direction) * distance
        assertEquals("Dock and page must represent the same transition progress", pagePosition, dockPosition(), .02f)
    }

    private fun capture(name: String) {
        // Gradle collects this ignored build output; no gallery or image fixture is committed.
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        val directory = File(output, "navigation-motion").apply { mkdirs() }
        // A semantics/clock update may precede presentation of the new Android window buffer.
        if (Build.VERSION.SDK_INT >= 29) {
            val committed = CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
                    .window.decorView.apply {
                        viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                        invalidate()
                    }
            }
            assertTrue("Navigation frame was not committed", committed.await(5, TimeUnit.SECONDS))
            val presented = CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val view = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single().window.decorView
                view.postOnAnimation { view.postOnAnimation { presented.countDown() } }
            }
            assertTrue("Navigation frame was not presented", presented.await(5, TimeUnit.SECONDS))
        }
        File(directory, "$name.png").outputStream().use {
            checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                .let { bitmap -> try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } finally { bitmap.recycle() } }
        }
    }

    @Test fun searchFieldMovesUpWhileThePageStaysStillAndFocusWaits() {
        show()
        val start = bounds("home_search_entry")
        capture("search-lift-000")
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("home_search_entry").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(32)
        rule.waitForIdle()
        capture("search-lift-032")
        rule.mainClock.advanceTimeBy(32)
        rule.waitForIdle()
        rule.onNodeWithTag("search_input").assertIsNotFocused()
        val middle = bounds("search_input")
        val page = bounds("screen_search")
        assertEquals(0f, page.left.value, 1f)
        capture("search-lift-middle")
        capture("search-lift-064")
        for (millis in listOf(96, 128, 160, 192, 224)) {
            rule.mainClock.advanceTimeBy(32)
            rule.waitForIdle()
            capture("search-lift-${millis.toString().padStart(3, '0')}")
        }
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        val end = bounds("search_input")
        assertTrue("The field must travel upward", end.top < start.top)
        assertTrue("A real intermediate position must exist: start=$start, middle=$middle, end=$end",
            middle.top > end.top && middle.top < start.top)
        rule.onNodeWithTag("search_input").assertIsFocused()
        capture("search-lift-complete")
        capture("search-return-000")
        rule.onNodeWithContentDescription("Close").performClick()
        rule.mainClock.advanceTimeByFrame()
        for (millis in listOf(32, 64, 96, 128, 160, 192, 224)) {
            rule.mainClock.advanceTimeBy(32)
            rule.waitForIdle()
            capture("search-return-${millis.toString().padStart(3, '0')}")
            if (millis == 96) capture("search-return-middle")
        }
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        rule.onNodeWithTag("home_search_entry").assertIsDisplayed()
    }

    @Test fun searchCanBeClosedBeforeTheLiftFinishes() {
        show()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("home_search_entry").performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithContentDescription("Close").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithTag("home_search_entry").assertIsDisplayed()
        rule.onAllNodesWithTag("search_input").assertCountEquals(0)
    }

    @Test fun searchRemainsUsableWithSystemAnimationsDisabled() {
        motionScale.scaleFactor = 0f
        show()
        rule.onNodeWithTag("home_search_entry").performClick()
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        rule.onNodeWithTag("search_input").assertIsDisplayed().assertIsFocused()
    }

    @Test fun tabsPushHorizontallyWhileDockStaysFixed() {
        show()
        val source = bounds(RescueAuthTestTags.SCREEN_AUTHENTICATOR)
        val dock = bounds("navigation_dock")
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(RescueAuthTestTags.NAV_DEVELOPER).performClick()
        midFrame()
        val outgoing = bounds(RescueAuthTestTags.SCREEN_AUTHENTICATOR)
        val incoming = bounds(RescueAuthTestTags.SCREEN_DEVELOPER)
        assertTrue("Outgoing page must move left, not just fade", outgoing.left < source.left)
        assertTrue("Incoming page must enter from the right", incoming.left > source.left && incoming.left < source.right)
        assertEquals(dock, bounds("navigation_dock"))
        assertDockTracksPages()
        capture("tabs-forward")
        finish()
        rule.onNodeWithTag(RescueAuthTestTags.SCREEN_AUTHENTICATOR).assertIsNotDisplayed()
        rule.onNodeWithTag(RescueAuthTestTags.SCREEN_DEVELOPER).assertIsDisplayed()
    }

    @Test fun endpointTargetsArePreparedBeforeTheFirstTapButStayOffscreen() {
        show()
        rule.onNodeWithTag(RescueAuthTestTags.SCREEN_AUTHENTICATOR).assertIsDisplayed()
        rule.onNodeWithTag(RescueAuthTestTags.SCREEN_DEVELOPER).assertExists().assertIsNotDisplayed()
        rule.onNodeWithTag(RescueAuthTestTags.SCREEN_SETTINGS).assertExists().assertIsNotDisplayed()
    }

    @Test fun endpointSwitchMovesOneScreenWithoutShowingTheMiddlePage() {
        show()
        rule.mainClock.autoAdvance = false
        for ((destination, expected) in listOf(RescueAuthTestTags.NAV_SETTINGS to 2f, RescueAuthTestTags.NAV_AUTHENTICATOR to 0f)) {
            val name = if (expected == 2f) "endpoint-forward" else "endpoint-back"
            capture("$name-000")
            rule.onNodeWithTag(destination).performClick()
            rule.mainClock.advanceTimeByFrame()
            var previous = if (expected == 2f) 0f else 2f
            repeat(7) { sample ->
                rule.mainClock.advanceTimeBy(32)
                rule.waitForIdle()
                assertDockTracksPages(from = if (expected == 2f) 0 else 2, to = expected.toInt())
                rule.onNodeWithTag(RescueAuthTestTags.SCREEN_DEVELOPER).assertIsNotDisplayed()
                val dock = bounds("navigation_dock")
                val first = bounds(RescueAuthTestTags.NAV_AUTHENTICATOR)
                val second = bounds(RescueAuthTestTags.NAV_DEVELOPER)
                val indicator = bounds("navigation_indicator")
                val position = (indicator.left - first.left).value / (second.left - first.left).value
                assertTrue("No backwards jump: $previous -> $position", if (expected == 2f) position >= previous - .02f else position <= previous + .02f)
                assertTrue(indicator.left >= dock.left && indicator.right <= dock.right)
                previous = position
                capture("$name-${((sample + 1) * 32).toString().padStart(3, '0')}")
                if (sample == 3) capture(if (expected == 2f) "endpoint-forward-middle" else "endpoint-back-middle")
            }
            assertEquals("Endpoint and adjacent switches both finish in 220 ms", expected, previous, .02f)
            rule.onNodeWithTag(if (expected == 2f) RescueAuthTestTags.SCREEN_SETTINGS else RescueAuthTestTags.SCREEN_AUTHENTICATOR).assertIsDisplayed()
            assertDockTracksPages()
        }
    }

    @Test fun skippingATabKeepsPagesAndDockInStepAtEverySample() {
        show()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).performClick()
        repeat(5) {
            rule.mainClock.advanceTimeBy(32)
            rule.waitForIdle()
            assertDockTracksPages(from = 0, to = 2)
        }
        finish()
        rule.onNodeWithTag(RescueAuthTestTags.SCREEN_SETTINGS).assertIsDisplayed()
        assertDockTracksPages(from = 0, to = 2)
    }

    @Test fun endpointTravelAlsoSkipsTheMiddlePageInRtl() {
        show(rtl = true)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).performClick()
        rule.mainClock.advanceTimeByFrame()
        repeat(5) {
            rule.mainClock.advanceTimeBy(32)
            rule.waitForIdle()
            rule.onNodeWithTag(RescueAuthTestTags.SCREEN_DEVELOPER).assertIsNotDisplayed()
            assertDockTracksPages(rtl = true, from = 0, to = 2)
        }
        finish()
        rule.onNodeWithTag(RescueAuthTestTags.SCREEN_SETTINGS).assertIsDisplayed()
    }

    @Test fun reversingAnEndpointSwitchPreservesBothVisiblePagePositions() {
        show()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).performClick()
        midFrame()
        val accounts = bounds(RescueAuthTestTags.SCREEN_AUTHENTICATOR)
        val settings = bounds(RescueAuthTestTags.SCREEN_SETTINGS)
        val dock = bounds("navigation_indicator")
        // Invoke the click callback without a down/up gesture advancing the test clock.
        // Physical taps are covered separately; here the assertion is continuity at retarget.
        rule.onNodeWithTag(RescueAuthTestTags.NAV_AUTHENTICATOR)
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals(accounts.left.value, bounds(RescueAuthTestTags.SCREEN_AUTHENTICATOR).left.value, 1f)
        assertEquals(settings.left.value, bounds(RescueAuthTestTags.SCREEN_SETTINGS).left.value, 1f)
        assertEquals(dock.left.value, bounds("navigation_indicator").left.value, 1f)
        finish()
        rule.onNodeWithTag(RescueAuthTestTags.SCREEN_AUTHENTICATOR).assertIsDisplayed()
        rule.onNodeWithTag(RescueAuthTestTags.SCREEN_DEVELOPER).assertIsNotDisplayed()
        rule.onNodeWithTag(RescueAuthTestTags.SCREEN_SETTINGS).assertIsNotDisplayed()
    }

    private fun reverseTabs(rtl: Boolean) {
        show(rtl)
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).performClick()
        val origin = bounds(RescueAuthTestTags.SCREEN_SETTINGS).left.value
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(RescueAuthTestTags.NAV_DEVELOPER).performClick()
        midFrame()
        val sign = if (rtl) -1 else 1
        assertTrue((bounds(RescueAuthTestTags.SCREEN_SETTINGS).left.value - origin) * sign > 0)
        assertTrue((bounds(RescueAuthTestTags.SCREEN_DEVELOPER).left.value - origin) * sign < 0)
        assertDockTracksPages(rtl)
        finish()
        rule.onNodeWithTag(RescueAuthTestTags.SCREEN_SETTINGS).assertIsNotDisplayed()
    }

    @Test fun reverseTabSwitchMirrorsTheTravelDirection() = reverseTabs(rtl = false)
    @Test fun tabTravelRespectsRtlOrder() = reverseTabs(rtl = true)

    @Test fun systemBackFromAnotherHomeReturnsToAccounts() {
        show()
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).performClick()
        rule.onNodeWithTag(RescueAuthTestTags.SCREEN_SETTINGS).assertIsDisplayed()
        pressBack()
        rule.onNodeWithTag(RescueAuthTestTags.SCREEN_AUTHENTICATOR).assertIsDisplayed()
        rule.onNodeWithTag(RescueAuthTestTags.NAV_AUTHENTICATOR).assertIsSelected()
        assertDockTracksPages()
    }

    @Test fun detailPushAndBackSlideWithoutResizingTheUnderlyingRoot() {
        show()
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).performClick()
        val root = bounds(RescueAuthTestTags.SCREEN_SETTINGS)
        rule.mainClock.autoAdvance = false
        about()
        midFrame()
        assertTrue(bounds(AboutTestTags.SCREEN).left > root.left)
        assertTrue(bounds(RescueAuthTestTags.SCREEN_SETTINGS).left < root.left)
        val departingRoot = bounds(RescueAuthTestTags.SCREEN_SETTINGS)
        assertEquals("Hiding the dock must not resize the departing page", root.bottom - root.top,
            departingRoot.bottom - departingRoot.top)
        capture("detail-push")
        finish()
        rule.onNodeWithTag(RescueAuthTestTags.SCREEN_SETTINGS).assertDoesNotExist()
        back()
        midFrame()
        assertTrue("Back must move the detail toward the right", bounds(AboutTestTags.SCREEN).left > root.left)
        assertTrue(bounds(RescueAuthTestTags.SCREEN_SETTINGS).left < root.left)
        val returningRoot = bounds(RescueAuthTestTags.SCREEN_SETTINGS)
        assertEquals(root.bottom - root.top, returningRoot.bottom - returningRoot.top)
        capture("detail-back")
        finish()
        rule.onNodeWithTag(AboutTestTags.SCREEN).assertDoesNotExist()
        assertEquals(root, bounds(RescueAuthTestTags.SCREEN_SETTINGS))
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).assertIsSelected()
    }

    @Test fun rapidTabSwitchesSettleOnTheLastTap() {
        show()
        rule.mainClock.autoAdvance = false
        for (tag in listOf(RescueAuthTestTags.NAV_SETTINGS, RescueAuthTestTags.NAV_DEVELOPER,
            RescueAuthTestTags.NAV_AUTHENTICATOR)) {
            rule.onNodeWithTag(tag).performClick()
            rule.mainClock.advanceTimeBy(48)
            // Flush NavController's back-stack Flow between taps, as a real UI frame does.
            rule.waitForIdle()
        }
        finish()
        rule.onNodeWithTag(RescueAuthTestTags.NAV_AUTHENTICATOR).assertIsSelected()
        rule.onNodeWithTag(RescueAuthTestTags.SCREEN_AUTHENTICATOR).assertIsDisplayed()
        assertEquals(0f, bounds(RescueAuthTestTags.SCREEN_AUTHENTICATOR).left.value, .5f)
        // Transition-completion effects remove the now off-screen back-stack entries.
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        rule.onNodeWithTag(RescueAuthTestTags.SCREEN_DEVELOPER).assertIsNotDisplayed()
        rule.onNodeWithTag(RescueAuthTestTags.SCREEN_SETTINGS).assertIsNotDisplayed()
        assertDockTracksPages()
    }

    @Test fun tappingOriginalTabBeforeTheFirstAnimationFrameCancelsTheSwitch() {
        show()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS)
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithTag(RescueAuthTestTags.NAV_AUTHENTICATOR)
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        finish()
        rule.onNodeWithTag(RescueAuthTestTags.NAV_AUTHENTICATOR).assertIsSelected()
        rule.onNodeWithTag(RescueAuthTestTags.SCREEN_SETTINGS).assertIsNotDisplayed()
        rule.onNodeWithTag(RescueAuthTestTags.SCREEN_AUTHENTICATOR).assertIsDisplayed()
    }

    @Test fun disabledSystemAnimationsSnapTabsAndDetailBack() {
        motionScale.scaleFactor = 0f
        show()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).performClick()
        repeat(4) { rule.mainClock.advanceTimeByFrame() }
        rule.onNodeWithTag(RescueAuthTestTags.SCREEN_AUTHENTICATOR).assertIsNotDisplayed()
        about()
        repeat(4) { rule.mainClock.advanceTimeByFrame() }
        rule.onNodeWithTag(RescueAuthTestTags.SCREEN_SETTINGS).assertDoesNotExist()
        back()
        repeat(4) { rule.mainClock.advanceTimeByFrame() }
        rule.onNodeWithTag(AboutTestTags.SCREEN).assertDoesNotExist()
        rule.onNodeWithTag(RescueAuthTestTags.SCREEN_SETTINGS).assertIsDisplayed()
    }

    @Test fun bannerTextIsOpaqueFromItsFirstFrame() {
        rule.mainClock.autoAdvance = false
        rule.setContent { RescueAuthTheme {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                StudioVaultHero(accountCount = 12)
            }
        } }
        rule.mainClock.advanceTimeByFrame()
        val first = rule.onNodeWithTag("vault_brand_banner").captureToImage()
        rule.mainClock.advanceTimeBy(240)
        val settled = rule.onNodeWithTag("vault_brand_banner").captureToImage()
        fun ink(image: ImageBitmap): Int {
            val pixels = image.toPixelMap()
            var count = 0
            for (y in 0 until pixels.height) for (x in 0 until (pixels.width * .6f).toInt()) {
                val color = pixels[x, y]
                if (maxOf(color.red, color.green, color.blue) < .4f) count++
            }
            return count
        }
        assertTrue("The settled banner must contain readable text", ink(settled) > 20)
        assertTrue("Only artwork may fade; text must already have its final contrast",
            ink(first) >= ink(settled) * .95f)
    }
}
