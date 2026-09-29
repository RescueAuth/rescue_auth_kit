package com.rescueauth.v2.ui

import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
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
 * Empty security fields and synthetic account metadata only; measurements are diagnostic, not
 * a device-independent performance pass threshold or a production/release benchmark. */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class HomeSwitchFrameTimingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun node(tag: String): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.viewIdResourceName == tag && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return null
    }

    @Test @Suppress("DEPRECATION")
    fun recordAdjacentAndEndpointSwitchesWithRealFrames() {
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
        runBlocking {
            val repo = checkNotNull(VaultAccess.providerAccountRepository())
            repeat(24) { index ->
                val provider = "Review service ${index + 1}"
                repo.createProvider(provider, "Personal")
                repo.createAccount(provider, "Work")
            }
        }
        val mounted = mutableStateOf(true)
        val handlerThread = HandlerThread("home-frame-review").apply { start() }
        val startNanos = AtomicLong(Long.MAX_VALUE)
        val frames = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val droppedReports = AtomicLong(0)
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, drops ->
            val intended = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            if (intended >= startNanos.get()) {
                frames.add(intended to metrics.getMetric(FrameMetrics.TOTAL_DURATION))
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
                    if (mounted.value) RescueAuthTheme(darkTheme = false) {
                        RescueAuthApp(modifier = Modifier.semantics { testTagsAsResourceId = true })
                    }
                }
            }
            val readyDeadline = System.nanoTime() + 10_000_000_000L
            while (node("home_search_entry") == null && System.nanoTime() < readyDeadline) Thread.sleep(50)
            assertTrue("Home must be visible before measuring", node("home_search_entry") != null)
            Thread.sleep(700)
            val samples = JSONArray()
            val routes = listOf(
                "first_0_to_2" to RescueAuthTestTags.NAV_SETTINGS,
                "warm_2_to_0" to RescueAuthTestTags.NAV_AUTHENTICATOR,
                "first_0_to_1" to RescueAuthTestTags.NAV_DEVELOPER,
                "warm_1_to_2" to RescueAuthTestTags.NAV_SETTINGS,
                "warm_2_to_0_repeat" to RescueAuthTestTags.NAV_AUTHENTICATOR,
                "warm_0_to_2_repeat" to RescueAuthTestTags.NAV_SETTINGS,
            )
            for ((name, tag) in routes) {
                frames.clear(); droppedReports.set(0)
                startNanos.set(System.nanoTime())
                checkNotNull(node(tag)) { "Missing visible dock action: $tag" }.let {
                    assertTrue("Dock click must be accepted", it.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                }
                Thread.sleep(600)
                val end = System.nanoTime(); startNanos.set(Long.MAX_VALUE)
                val values = synchronized(frames) { frames.filter { it.first <= end }.map { it.second / 1_000_000.0 } }
                assertTrue("Window must report rendered frames", values.isNotEmpty())
                val sorted = values.sorted()
                samples.put(JSONObject().put("name", name).put("frame_count", values.size)
                    .put("total_duration_ms", JSONArray(values))
                    .put("p95_ms", sorted[((sorted.size - 1) * .95).toInt()])
                    .put("max_ms", sorted.last())
                    .put("over_refresh_budget", values.count { it > 1000.0 / refreshRate })
                    .put("listener_dropped_reports", droppedReports.get()))
                // Ensure the next sample really starts at its named endpoint, even on a slow
                // software-rendered emulator. This extra settling wait is outside the sample.
                val screenTag = when (tag) {
                    RescueAuthTestTags.NAV_SETTINGS -> RescueAuthTestTags.SCREEN_SETTINGS
                    RescueAuthTestTags.NAV_DEVELOPER -> RescueAuthTestTags.SCREEN_DEVELOPER
                    else -> RescueAuthTestTags.SCREEN_AUTHENTICATOR
                }
                val deadline = System.nanoTime() + 5_000_000_000L
                var settled = false
                while (!settled && System.nanoTime() < deadline) {
                    val rootBounds = Rect().also { instrumentation.uiAutomation.rootInActiveWindow?.getBoundsInScreen(it) }
                    val bounds = Rect().also { node(screenTag)?.getBoundsInScreen(it) }
                    settled = rootBounds.width() > 0 && kotlin.math.abs(bounds.left - rootBounds.left) <= 2 &&
                        kotlin.math.abs(bounds.right - rootBounds.right) <= 2
                    if (!settled) Thread.sleep(32)
                }
                assertTrue("Requested home page must settle before the next sample", settled)
            }
            val output = checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
            File(output, "home-switch/timing.json").apply { parentFile!!.mkdirs() }.writeText(
                JSONObject().put("refresh_hz", refreshRate).put("synthetic_accounts", 48)
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
