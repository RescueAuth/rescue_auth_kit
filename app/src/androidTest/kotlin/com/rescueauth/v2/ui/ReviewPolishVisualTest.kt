package com.rescueauth.v2.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.LocaleList
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rescueauth.v2.ui.authenticator.AddMode
import com.rescueauth.v2.ui.authenticator.AddTotpFormState
import com.rescueauth.v2.ui.screens.about.AboutScreen
import com.rescueauth.v2.ui.screens.about.AboutTestTags
import com.rescueauth.v2.ui.screens.authenticator.AddTotpSheet
import com.rescueauth.v2.ui.screens.authenticator.ProviderPickerDialog
import com.rescueauth.v2.ui.screens.search.SearchScreen
import com.rescueauth.v2.ui.screens.search.SearchTestTags
import com.rescueauth.v2.ui.search.SearchUiState
import com.rescueauth.v2.ui.screens.startup.StartupIntroScreen
import com.rescueauth.v2.ui.screens.startup.StartupLockTestTags
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import com.rescueauth.v2.update.UpdateUiState
import java.io.File
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native layout and real touch checks for the three review follow-ups; no secrets. */
@RunWith(AndroidJUnit4::class)
class ReviewPolishVisualTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var density = 1f
    private var originalConfiguration: Configuration? = null

    @Suppress("DEPRECATION")
    private fun content(dark: Boolean = false, english: Boolean = false, fontScale: Float = 1f, body: @Composable () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Dialogs create a separate window using the Activity's resources.
            // LocalContext alone only localizes the surrounding Compose tree.
            val resources = rule.activity.resources
            originalConfiguration = Configuration(resources.configuration)
            resources.updateConfiguration(Configuration(resources.configuration).apply {
                setLocales(LocaleList(if (english) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE))
            }, resources.displayMetrics)
            rule.activity.enableEdgeToEdge()
            WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        }
        rule.setContent {
            val base = LocalContext.current
            val config = Configuration(base.resources.configuration).apply {
                setLocales(LocaleList(if (english) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE))
            }
            density = LocalDensity.current.density
            CompositionLocalProvider(LocalContext provides base.createConfigurationContext(config),
                LocalConfiguration provides config, LocalDensity provides Density(density, fontScale),
                LocalActivityResultRegistryOwner provides rule.activity,
                LocalOnBackPressedDispatcherOwner provides rule.activity) {
                RescueAuthTheme(darkTheme = dark, content = body)
            }
        }
    }

    @After
    @Suppress("DEPRECATION")
    fun restoreActivityLocale() {
        val original = originalConfiguration ?: return
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val resources = rule.activity.resources
            resources.updateConfiguration(original, resources.displayMetrics)
        }
    }

    private fun capture(id: String, title: String, section: String) {
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(350, 5_000)
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: checkNotNull(rule.activity.getExternalFilesDir(null))
        val directory = File(output, "polish-ui-review").apply { mkdirs() }
        val bitmap = checkNotNull(automation.takeScreenshot())
        try {
            File(directory, "$id.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        File(directory, "$id.json").writeText(JSONObject().put("id", id).put("title", title).put("section", section)
            .put("source", "ReviewPolishVisualTest").toString())
    }

    private fun about(dark: Boolean = false, large: Boolean = false) {
        var checks = 0
        content(dark, english = large, fontScale = if (large) 1.5f else 1f) {
            AboutScreen("1.0.0", 10000, UpdateUiState.Idle, { checks++ }, {}, {})
        }
        val button = rule.onNodeWithTag(AboutTestTags.CHECK_BUTTON).assertIsDisplayed().getUnclippedBoundsInRoot()
        val screen = rule.onNodeWithTag(AboutTestTags.SCREEN).getUnclippedBoundsInRoot()
        assertTrue(button.top > screen.bottom - 120.dp)
        val id = if (large) "202-about-large" else if (dark) "201-about-dark" else "200-about-light"
        capture(id, if (large) "关于 · 英文大字体" else if (dark) "关于 · 深色新版" else "关于 · 新版", "备份与设置")
        rule.onNodeWithTag(AboutTestTags.CONTENT).performTouchInput { swipeUp() }
        rule.onNodeWithText("10000", substring = true).performScrollTo().assertIsDisplayed()
        assertEquals(button, rule.onNodeWithTag(AboutTestTags.CHECK_BUTTON).getUnclippedBoundsInRoot())
        rule.onNodeWithTag(AboutTestTags.CHECK_BUTTON).performClick()
        rule.runOnIdle { assertEquals(1, checks) }
    }
    @Test fun aboutLight() = about()
    @Test fun aboutDark() = about(dark = true)
    @Test fun aboutLargeEnglish() = about(large = true)

    private fun welcome(english: Boolean, dark: Boolean = false, large: Boolean = false) {
        var continued = 0
        var imported = 0
        content(dark, english, if (large) 1.5f else 1f) { StartupIntroScreen({ continued++ }, { imported++ }) }
        val name = if (english) "RescueAuth" else "拾遗坊"
        rule.onAllNodes(hasText(name) or hasContentDescription(name)).assertCountEquals(1)
        rule.onNodeWithText(if (english) "Your vault." else "你的保险库。").assertDoesNotExist()
        val action = rule.onNodeWithTag(StartupLockTestTags.INTRO_CONTINUE_BUTTON)
        action.performScrollTo().assertIsDisplayed()
        rule.onNode(hasText(if (english) "Fingerprint or screen lock" else "指纹或锁屏密码") and
            hasAnyAncestor(hasTestTag(StartupLockTestTags.INTRO_CONTINUE_BUTTON)), useUnmergedTree = true).assertExists()
        val id = if (dark) "213-welcome-dark" else if (large) "212-welcome-large" else if (english) "210-welcome-en" else "211-welcome-zh"
        capture(id, if (large) "欢迎页 · 大字体新版" else if (dark) "欢迎页 · 深色新版" else if (english) "欢迎页 · 英文新版" else "欢迎页 · 新版", "启动与外观")
        action.performClick(); rule.runOnIdle { assertEquals(1, continued) }
        rule.onNodeWithTag(StartupLockTestTags.INTRO_IMPORT_V1_BUTTON).performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, imported) }
    }
    @Test fun welcomeEnglish() = welcome(true)
    @Test fun welcomeChinese() = welcome(false)
    @Test fun welcomeLargeEnglish() = welcome(true, large = true)
    @Test fun welcomeDark() = welcome(false, dark = true)

    @Test fun welcomeSmallViewportKeepsBothActionsReachable() {
        var continued = 0; var imported = 0
        content(english = true, fontScale = 1.5f) {
            StartupIntroScreen({ continued++ }, { imported++ }, Modifier.width(320.dp).height(480.dp))
        }
        rule.onNodeWithTag(StartupLockTestTags.INTRO_CONTINUE_BUTTON).performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithTag(StartupLockTestTags.INTRO_IMPORT_V1_BUTTON).performScrollTo().assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, continued); assertEquals(1, imported) }
    }

    @Composable private fun manual(submit: () -> Unit = {}) {
        AddTotpSheet(AddTotpFormState(mode = AddMode.MANUAL), {}, {}, onUriChange = {}, onProviderChange = {},
            onAccountNameChange = {}, onSecretChange = {}, onAlgorithmChange = {}, onDigitsChange = {},
            onPeriodChange = {}, onSubmit = submit)
    }

    @Test fun manualFormExpandedAndActionsStayWhileScrolling() {
        var submitted = 0
        content { manual { submitted++ } }
        val action = rule.onNodeWithTag("add_totp_submit").assertIsDisplayed()
        val before = action.getUnclippedBoundsInRoot()
        rule.onNodeWithText("密钥（Base32）").performScrollTo().assertIsDisplayed()
        capture("220-totp-expanded", "添加验证码 · 完整展开", "账户")
        rule.onNodeWithText("高级设置").performScrollTo().performClick()
        rule.onNodeWithTag("add_totp_fields").performTouchInput { swipeUp() }
        action.assertIsDisplayed()
        assertEquals(before, action.getUnclippedBoundsInRoot())
        capture("221-totp-advanced", "添加验证码 · 高级设置", "账户")
        action.performClick(); rule.runOnIdle { assertEquals(1, submitted) }
    }

    @Test fun manualFormActionsStayAboveRealKeyboard() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val original = automation.serviceInfo
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        try {
            content { manual() }
            rule.onNodeWithTag("add_totp_secret").performScrollTo().performClick()
            var imeBounds: Rect? = null
            rule.waitUntil(10_000) {
                imeBounds = automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    ?.let { window -> Rect().also(window::getBoundsInScreen) }
                imeBounds?.height()?.let { it > 0 } == true
            }
            val button = rule.onNodeWithTag("add_totp_submit").assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("Submit is covered by the keyboard", button.bottom.value * density <= checkNotNull(imeBounds).top + 2)
            capture("222-totp-keyboard", "添加验证码 · 键盘上方操作栏", "账户")
        } finally { automation.serviceInfo = original }
    }

    @Test fun filledSearchKeepsTypingAndClearActions() {
        val query = mutableStateOf("")
        var focus: FocusManager? = null
        var keyboard: SoftwareKeyboardController? = null
        content {
            focus = LocalFocusManager.current
            keyboard = LocalSoftwareKeyboardController.current
            SearchScreen(uiState = SearchUiState(query = query.value, unlocked = true),
                onQueryChange = { query.value = it }, onClearQuery = { query.value = "" })
        }
        rule.waitForIdle()
        rule.runOnIdle { focus?.clearFocus(force = true); keyboard?.hide() }
        rule.onNodeWithTag(SearchTestTags.SEARCH_INPUT).assertIsNotFocused()
        capture("232-search-default", "搜索输入框 · 未聚焦", "账户")
        rule.onNodeWithTag(SearchTestTags.SEARCH_INPUT).performClick()
        rule.onNodeWithTag(SearchTestTags.SEARCH_INPUT).performTextInput("GitHub")
        rule.onNodeWithTag(SearchTestTags.SEARCH_INPUT).assert(hasText("GitHub"))
        capture("230-input-search", "搜索输入框 · 聚焦", "账户")
        rule.onNodeWithTag(SearchTestTags.SEARCH_CLEAR).performClick()
        rule.runOnIdle { assertEquals("", query.value) }
    }

    @Test fun filledReadOnlyAnchorStillOpensAndSelectsItsMenu() {
        var selected: String? = null
        content {
            ProviderPickerDialog("移动账户", listOf("GitHub", "Google"), "GitHub", { selected = it }, {})
        }
        rule.onNodeWithText("GitHub").performClick()
        rule.onNodeWithText("Google").performClick()
        capture("231-input-picker", "只读选择框 · 切换服务", "账户")
        rule.onNodeWithText("确认").performClick()
        rule.runOnIdle { assertEquals("Google", selected) }
    }
}
