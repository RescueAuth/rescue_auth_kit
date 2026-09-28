package com.rescueauth.v2.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.ui.theme.AddActionTokens
import com.rescueauth.v2.ui.components.RescueAuthFloatingAddOverlay
import com.rescueauth.v2.ui.components.rememberFloatingAddPosition
import com.rescueauth.v2.ui.authenticator.AuthenticatorUiState
import com.rescueauth.v2.ui.developer.DeveloperListUiState
import com.rescueauth.v2.ui.model.ProviderUi
import com.rescueauth.v2.ui.model.DeveloperEntryType
import com.rescueauth.v2.ui.screens.authenticator.AuthenticatorScreen
import com.rescueauth.v2.ui.screens.developer.DeveloperScreen
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real shell and modal windows, with empty Vault state: screenshots never contain secrets. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class GlobalAddMotionTest {
    private val motionScale = object : MotionDurationScale { override var scaleFactor = 1f }
    @get:Rule val rule = createComposeRule(effectContext = motionScale)

    private fun show(dark: Boolean = false, large: Boolean = false, rtl: Boolean = false) {
        rule.setContent {
            val base = LocalContext.current
            val config = Configuration(base.resources.configuration).apply {
                setLocales(LocaleList(if (dark) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE))
            }
            val activityResults = checkNotNull(LocalActivityResultRegistryOwner.current)
            val backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
            CompositionLocalProvider(
                LocalContext provides base.createConfigurationContext(config),
                LocalConfiguration provides config,
                LocalActivityResultRegistryOwner provides activityResults,
                LocalOnBackPressedDispatcherOwner provides backDispatcher,
                LocalDensity provides Density(LocalDensity.current.density, if (large) 1.5f else 1f),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) { RescueAuthTheme(darkTheme = dark) { RescueAuthApp() } }
        }
        rule.waitForIdle()
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val external = checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File) ?: external
        val directory = File(output, "global-add").apply { check(isDirectory || mkdirs()) }
        val bitmap = if (rule.mainClock.autoAdvance) {
            // uiAutomation includes the separate ModalBottomSheet window.
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.waitForIdle(350, 5_000)
            checkNotNull(automation.takeScreenshot())
        } else {
            // Flush the Compose draw at the manually selected frame; the window screenshot can lag it.
            rule.onRoot().captureToImage().asAndroidBitmap()
        }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun finish() { rule.mainClock.advanceTimeBy(1_000); rule.waitForIdle() }

    @Test fun addStaysFixedAtIntermediatePageFrames() {
        show()
        val before = rule.onNodeWithTag("global_add").getUnclippedBoundsInRoot()
        val dock = rule.onNodeWithTag("navigation_dock").getUnclippedBoundsInRoot()
        capture("01-accounts")
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(RescueAuthTestTags.NAV_DEVELOPER).performClick()
        rule.mainClock.advanceTimeBy(80)
        rule.onAllNodesWithTag("global_add").assertCountEquals(1)
        assertEquals(before, rule.onNodeWithTag("global_add").getUnclippedBoundsInRoot())
        assertEquals(dock, rule.onNodeWithTag("navigation_dock").getUnclippedBoundsInRoot())
        rule.onNodeWithTag("global_add").assertIsNotEnabled()
        capture("02-between-pages")
        finish()
        rule.onNodeWithTag("global_add").assertIsEnabled()
        assertEquals(before, rule.onNodeWithTag("global_add").getUnclippedBoundsInRoot())
    }

    @Test fun settingsShrinkThenReturnWithOvershootAndSettle() {
        show()
        // Semantic bounds include graphics-layer transforms; layout size deliberately stays fixed.
        val before = rule.onNodeWithTag("global_add").fetchSemanticsNode().boundsInRoot
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).performClick()
        rule.mainClock.advanceTimeBy(96)
        rule.onNodeWithTag("global_add").assertIsNotEnabled()
        val shrinking = rule.onNodeWithTag("global_add").fetchSemanticsNode().boundsInRoot
        assertTrue(shrinking.right - shrinking.left < before.right - before.left)
        capture("03-shrink")
        finish()
        rule.onNodeWithTag("global_add").assertDoesNotExist()
        capture("04-settings")
        rule.onNodeWithTag(RescueAuthTestTags.NAV_AUTHENTICATOR).performClick()
        var grewPastRest = false
        repeat(16) {
            rule.mainClock.advanceTimeBy(32)
            val bounds = rule.onNodeWithTag("global_add").fetchSemanticsNode().boundsInRoot
            if (bounds.right - bounds.left > before.right - before.left) grewPastRest = true
        }
        assertTrue("Reappearance should have a small spring overshoot", grewPastRest)
        finish()
        assertEquals(before, rule.onNodeWithTag("global_add").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun interruptedExitReversesAndLastDestinationOwnsTheAction() {
        show()
        rule.mainClock.autoAdvance = false
        for (tag in listOf(RescueAuthTestTags.NAV_SETTINGS, RescueAuthTestTags.NAV_AUTHENTICATOR,
            RescueAuthTestTags.NAV_SETTINGS, RescueAuthTestTags.NAV_DEVELOPER)) {
            rule.onNodeWithTag(tag).performClick()
            rule.mainClock.advanceTimeBy(48)
            rule.waitForIdle()
        }
        finish()
        rule.mainClock.autoAdvance = true
        rule.onAllNodesWithTag("global_add").assertCountEquals(1)
        rule.onNodeWithTag("global_add").performClick()
        rule.onNodeWithTag("developer_add_sheet").assertIsDisplayed()
        capture("05-developer-sheet")
        pressBack()
        rule.onNodeWithTag("developer_add_sheet").assertDoesNotExist()
        rule.onNodeWithTag(RescueAuthTestTags.NAV_AUTHENTICATOR).performClick()
        rule.onNodeWithTag("global_add").performClick()
        rule.onNodeWithTag("authenticator_add_sheet").assertIsDisplayed()
        capture("06-accounts-sheet")
        rule.onNodeWithTag("add_authenticator").performClick()
        rule.onNodeWithTag("add_totp_sheet").assertIsDisplayed()
    }

    @Test fun largeEnglishDarkSheetKeepsEveryTypeReachable() {
        show(dark = true, large = true)
        rule.onNodeWithTag(RescueAuthTestTags.NAV_DEVELOPER).performClick()
        rule.onNodeWithTag("global_add").performClick()
        rule.onNodeWithTag("add_type_ENVIRONMENT_VARIABLE_SET").performScrollTo().assertIsDisplayed()
        capture("07-dark-large-developer-sheet")
        rule.onNodeWithTag("add_type_SSH_KEY").performScrollTo().performClick()
        rule.onNodeWithTag("developer_form_next").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithTag(RescueAuthTestTags.NAV_AUTHENTICATOR).performClick()
        rule.onNodeWithTag("global_add").performClick()
        rule.onNodeWithText("Create a service").performScrollTo().assertIsDisplayed()
        capture("08-dark-large-accounts-sheet")
    }

    @Test fun disabledAnimationsSnapAndRtlUsesTheLogicalEnd() {
        motionScale.scaleFactor = 0f
        show(rtl = true)
        val before = rule.onNodeWithTag("global_add").getUnclippedBoundsInRoot()
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        assertTrue(before.left + before.right < root.left + root.right)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).performClick()
        repeat(5) { rule.mainClock.advanceTimeByFrame() }
        rule.onNodeWithTag("global_add").assertDoesNotExist()
        rule.onNodeWithTag(RescueAuthTestTags.NAV_DEVELOPER).performClick()
        repeat(5) { rule.mainClock.advanceTimeByFrame() }
        assertEquals(before, rule.onNodeWithTag("global_add").getUnclippedBoundsInRoot())
        rule.onNodeWithTag("global_add").assertIsEnabled()
    }

    @Test fun draggingDocksInsideTheScreenAndRetainsPositionAcrossTabs() {
        show()
        val viewport = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val before = rule.onNodeWithTag("global_add").getUnclippedBoundsInRoot()
        rule.onNodeWithTag("global_add").performTouchInput {
            swipe(center, center + Offset(-viewport.width * .9f, -viewport.height * .35f), 400)
        }
        val moved = rule.onNodeWithTag("global_add").getUnclippedBoundsInRoot()
        assertTrue(moved.right < before.left)
        assertTrue(moved.top < before.top)
        assertTrue(moved.left >= rootBounds().left)
        rule.onNodeWithTag("authenticator_add_sheet").assertDoesNotExist()
        rule.onNodeWithTag(RescueAuthTestTags.NAV_DEVELOPER).performClick()
        assertEquals(moved, rule.onNodeWithTag("global_add").getUnclippedBoundsInRoot())
        rule.onNodeWithTag(RescueAuthTestTags.NAV_SETTINGS).performClick()
        rule.onNodeWithTag(RescueAuthTestTags.NAV_AUTHENTICATOR).performClick()
        assertEquals(moved, rule.onNodeWithTag("global_add").getUnclippedBoundsInRoot())
        capture("09-dragged-left")
        rule.onNodeWithTag("global_add").performTouchInput {
            swipe(center, center + Offset(-viewport.width, -viewport.height), 300)
        }
        val clamped = rule.onNodeWithTag("global_add").getUnclippedBoundsInRoot()
        assertTrue(clamped.top > rootBounds().top)
        assertTrue(clamped.left >= rootBounds().left)
        rule.onNodeWithTag("global_add").performClick()
        rule.onNodeWithTag("authenticator_add_sheet").assertIsDisplayed()
    }

    private fun rootBounds() = rule.onRoot().getUnclippedBoundsInRoot()

    @Test fun finalProviderCanScrollAboveTheButtonAndRemainClickable() {
        var opened: String? = null
        rule.setContent { RescueAuthTheme {
            Box(Modifier.height(480.dp)) {
                AuthenticatorScreen(
                    uiState = AuthenticatorUiState(loading = false,
                        providers = (1..20).map { ProviderUi("demo-$it", "Demo $it", emptyList()) }),
                    showAddAction = false,
                    contentBottomPadding = AddActionTokens.contentClearance,
                    onOpenProvider = { opened = it },
                )
                RescueAuthFloatingAddOverlay(true, true, rememberFloatingAddPosition(), {})
            }
        } }
        rule.onNodeWithTag("provider_directory").performScrollToIndex(20)
        val last = rule.onNodeWithTag("provider_row_Demo 20")
        assertTrue(last.getUnclippedBoundsInRoot().bottom < rule.onNodeWithTag("global_add").getUnclippedBoundsInRoot().top)
        last.performClick()
        assertEquals("Demo 20", opened)
        capture("10-last-provider-clearance")
    }

    @Test fun finalLargeTextDeveloperCategoryClearsTheButton() {
        var opened: DeveloperEntryType? = null
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                RescueAuthTheme {
                    Box(Modifier.height(480.dp)) {
                        DeveloperScreen(uiState = DeveloperListUiState(loading = false),
                            contentBottomPadding = AddActionTokens.contentClearance,
                            onOpenCategory = { opened = it })
                        RescueAuthFloatingAddOverlay(true, true, rememberFloatingAddPosition(), {})
                    }
                }
            }
        }
        rule.onNodeWithTag("developer_directory").performScrollToIndex(5)
        val last = rule.onNodeWithTag("developer_category_GENERIC_SECRET")
        assertTrue(last.getUnclippedBoundsInRoot().bottom < rule.onNodeWithTag("global_add").getUnclippedBoundsInRoot().top)
        last.performClick()
        assertEquals(DeveloperEntryType.GENERIC_SECRET, opened)
        capture("11-last-category-clearance")
    }
}
