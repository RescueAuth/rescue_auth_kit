package com.rescueauth.v2.ui

import android.graphics.Rect
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rescueauth.v2.database.RescueAuthDatabase
import com.rescueauth.v2.repository.VaultAccess
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.session.SessionManager
import com.rescueauth.v2.ui.theme.RescueAuthTheme
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** Real Choreographer/Window timing, deliberately without the Compose test animation clock.
 * Synthetic data only; fake credentials stay masked and are never revealed or copied. Measurements are diagnostic, not
 * a device-independent performance pass threshold or a production/release benchmark. */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class RouteFrameTimingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val reviewLocale = if (InstrumentationRegistry.getArguments().getString("motionLocale") == "zh")
        Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH
    private val localizedContext by lazy {
        instrumentation.targetContext.createConfigurationContext(
            Configuration(instrumentation.targetContext.resources.configuration).apply { setLocales(LocaleList(reviewLocale)) })
    }
    private fun labelResource(resource: Int): String = localizedContext.getString(resource)

    private fun node(tag: String? = null, label: String? = null): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.isVisibleToUser && ((tag != null && n.viewIdResourceName == tag) ||
                (label != null && (n.contentDescription?.toString() == label ||
                    (label != labelResource(com.rescueauth.v2.R.string.a11y_back) && n.text?.toString()?.contains(label) == true))))) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return null
    }

    @Test @Suppress("DEPRECATION")
    fun recordNestedPagePushAndPopWithRealFrames() {
        val previousManager = VaultAccess.sessionManager
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val context = instrumentation.targetContext
        val manager = SessionManager(context, SecureSessionStateMachine(), scope,
            databaseFactory = { ctx, _ -> Room.inMemoryDatabaseBuilder(ctx, RescueAuthDatabase::class.java)
                .allowMainThreadQueries().build() })
        VaultAccess.clear()
        val key = ByteArray(32) { it.toByte() }
        try { check(manager.unlockWithFreshKey(key)) } finally { key.fill(0) }
        VaultAccess.sessionManager = manager
        var personalId = ""
        runBlocking {
            val repo = checkNotNull(VaultAccess.providerAccountRepository())
            repeat(12) { index ->
                val provider = "Review service ${index + 1}"
                val account = repo.createProvider(provider, "Personal")
                if (index == 0) personalId = account.id
                repo.createAccount(provider, "Work")
            }
            checkNotNull(VaultAccess.developerRepository()).createApiCredential(
                title = "Review entry", notes = null, serviceName = "Demo service", accountName = "Demo account",
                apiKey = "DEMO-NOT-A-REAL-KEY", apiSecret = "DEMO-NOT-A-REAL-SECRET")
        }
        val mounted = mutableStateOf(true)
        val handlerThread = HandlerThread("home-frame-review").apply { start() }
        val startNanos = AtomicLong(Long.MAX_VALUE)
        val frames = Collections.synchronizedList(mutableListOf<Pair<Long, List<Long>>>())
        val droppedReports = AtomicLong(0)
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, drops ->
            val intended = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            if (intended >= startNanos.get()) {
                frames.add(intended to listOf(FrameMetrics.TOTAL_DURATION, FrameMetrics.LAYOUT_MEASURE_DURATION,
                    FrameMetrics.ANIMATION_DURATION, FrameMetrics.DRAW_DURATION).map { metrics.getMetric(it) })
                droppedReports.addAndGet(drops.toLong())
            }
        }
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        var refreshRate = 60f
        try {
            scenario.onActivity { activity ->
                refreshRate = activity.windowManager.defaultDisplay.refreshRate
                activity.enableEdgeToEdge()
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(handlerThread.looper))
                activity.setContent {
                    val base = LocalContext.current
                    val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList(reviewLocale)) }
                    CompositionLocalProvider(LocalContext provides base.createConfigurationContext(config), LocalConfiguration provides config,
                        LocalActivityResultRegistryOwner provides activity, LocalOnBackPressedDispatcherOwner provides activity) {
                        if (mounted.value) RescueAuthTheme(darkTheme = false) {
                            RescueAuthApp(modifier = Modifier.semantics { testTagsAsResourceId = true })
                        }
                    }
                }
            }
            val readyDeadline = System.nanoTime() + 10_000_000_000L
            while (node("home_search_entry") == null && System.nanoTime() < readyDeadline) Thread.sleep(50)
            assertTrue("Home must be visible before measuring", node("home_search_entry") != null)
            Thread.sleep(700)
            fun click(tag: String? = null, label: String? = null) {
                val localized = when (label) {
                    "Back" -> labelResource(com.rescueauth.v2.R.string.a11y_back)
                    "Import Legacy v1 Vault" -> labelResource(com.rescueauth.v2.R.string.settings_import_legacy)
                    "Import Native Package" -> labelResource(com.rescueauth.v2.R.string.settings_import_native)
                    else -> label
                }
                val deadline = System.nanoTime() + 8_000_000_000L
                var found = node(tag, localized)
                while (found == null && System.nanoTime() < deadline) { Thread.sleep(50); found = node(tag, localized) }
                if (found == null) {
                    val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    if (output != null) {
                        val queue = ArrayDeque<AccessibilityNodeInfo>()
                        instrumentation.uiAutomation.rootInActiveWindow?.let(queue::add)
                        val lines = mutableListOf<String>()
                        while (queue.isNotEmpty()) {
                            val item = queue.removeFirst()
                            lines.add("${item.viewIdResourceName} | ${item.text} | ${item.contentDescription} | ${item.isVisibleToUser}")
                            repeat(item.childCount) { item.getChild(it)?.let(queue::add) }
                        }
                        File(output, "missing-route.txt").writeText(lines.joinToString("\n"))
                    }
                }
                var target = checkNotNull(found) { "Missing action: $tag / $label" }
                while (!target.isClickable && target.parent != null) target = target.parent
                check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Rejected action: $tag / $label" }
            }
            val samples = JSONArray()
            fun sample(name: String, action: () -> Unit) {
                frames.clear(); droppedReports.set(0); startNanos.set(System.nanoTime())
                action()
                Thread.sleep(700)
                val end = System.nanoTime(); startNanos.set(Long.MAX_VALUE)
                val values = synchronized(frames) { frames.filter { it.first <= end }.map { pair -> pair.second.map { it / 1_000_000.0 } } }
                assertTrue("Window must report frames for $name", values.isNotEmpty())
                val report = JSONObject().put("name", name).put("frame_count", values.size)
                    .put("listener_dropped_reports", droppedReports.get())
                listOf("total", "layout_measure", "animation", "draw").forEachIndexed { index, field ->
                    val column = values.map { it[index] }; val sorted = column.sorted()
                    report.put(field, JSONObject().put("duration_ms", JSONArray(column))
                        .put("p95_ms", sorted[((sorted.size - 1) * .95).toInt()]).put("max_ms", sorted.last()))
                }
                samples.put(report)
                InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { output ->
                    File(output, "route-motion/progress.json").apply { parentFile!!.mkdirs() }.writeText(samples.toString(2))
                }
                Thread.sleep(300)
            }
            repeat(2) { run ->
                sample("${run}_provider_push") { click(tag = "provider_row_Review service 1") }
                sample("${run}_account_push") { click(tag = "account_row_$personalId") }
                sample("${run}_recovery_push") { click(tag = "account_recovery") }
                sample("${run}_recovery_pop") { click(label = "Back") }
                sample("${run}_account_pop") { click(tag = "authenticator_back") }
                sample("${run}_provider_pop") { click(tag = "authenticator_back") }
                click(tag = RescueAuthTestTags.NAV_DEVELOPER); Thread.sleep(700)
                sample("${run}_category_push") { click(tag = "developer_category_API_CREDENTIAL") }
                sample("${run}_entry_push") { click(label = "Review entry") }
                sample("${run}_entry_pop") { click(label = "Back") }
                sample("${run}_form_push") { click(tag = "global_add") }
                sample("${run}_form_pop") { click(label = "Back") }
                sample("${run}_category_pop") { click(label = "Back") }
                click(tag = RescueAuthTestTags.NAV_SETTINGS); Thread.sleep(700)
                sample("${run}_transfer_push") { click(tag = "settings_transfer_row") }
                sample("${run}_legacy_push") { click(label = "Import Legacy v1 Vault") }
                sample("${run}_legacy_pop") { click(label = "Back") }
                sample("${run}_native_import_push") { click(label = "Import Native Package") }
                sample("${run}_native_import_pop") { click(label = "Back") }
                sample("${run}_transfer_pop") { click(label = "Back") }
                sample("${run}_about_push") { click(tag = "settings_about_row") }
                sample("${run}_about_pop") { click(label = "Back") }
                click(tag = RescueAuthTestTags.NAV_AUTHENTICATOR); Thread.sleep(700)
            }
            val output = checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
            File(output, "route-motion/timing.json").apply { parentFile!!.mkdirs() }.writeText(
                JSONObject().put("locale", reviewLocale.toLanguageTag()).put("refresh_hz", refreshRate).put("synthetic_accounts", 24)
                    .put("clock", "real Android frame clock; no Compose TestRule")
                    .put("samples", samples).toString(2))
        } finally {
            scenario.onActivity { activity -> mounted.value = false; activity.window.removeOnFrameMetricsAvailableListener(listener) }
            scenario.close(); handlerThread.quitSafely()
            runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
            manager.lock(); VaultAccess.clear(); VaultAccess.sessionManager = previousManager
        }
    }
}
