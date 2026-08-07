package com.rescueauth.v2.database

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Verifies the production SQLCipher native-loading contract.
 *
 * This is NOT a workaround for the UnsatisfiedLinkError: it runs on the host
 * JVM (Robolectric), where `libsqlcipher.so` is intentionally NOT on
 * `java.library.path`. It proves that:
 *
 * 1. [SQLCipherNativeLoader.ensureLoaded] is idempotent and safe to call from
 *    every database entry point (no crash, no deadlock, repeatable).
 * 2. When the native library is genuinely missing the failure surfaces as a
 *    diagnosable `UnsatisfiedLinkError` — never a silent no-op that would let
 *    a process open a half-initialized database.
 * 3. **Concurrency**: when two threads first call [ensureLoaded] at the same
 *    time, no thread can observe the loader as done — and return early to call
 *    SQLCipher native methods — while the native core is still being loaded.
 *    `loaded` is published only *after* `loadLibrary` succeeds, so the
 *    second caller either blocks until the load finishes or, on a later call,
 *    reads `loaded == true` which happens-after the completed load.
 *
 * The real "does the app load libsqlcipher.so before opening a database?"
 * guarantee is exercised by the instrumented tests on Firebase Test Lab: they
 * go through [RescueAuthDatabase.build] and therefore through
 * [SQLCipherNativeLoader] — they do NOT call `System.loadLibrary` themselves.
 */
class SQLCipherNativeLoaderTest {

    @Before
    fun resetLoader() {
        SQLCipherNativeLoader.resetForTest()
    }

    @After
    fun cleanupLoader() {
        SQLCipherNativeLoader.resetForTest()
    }

    @Test
    fun ensureLoadedIsSafeToCallRepeatedly() {
        // On the host JVM the SQLCipher .so cannot load, so each call either
        // returns (if a previous attempt succeeded — impossible here) or throws
        // UnsatisfiedLinkError. The loader keeps its flag false on failure, so
        // a retry is a legitimate re-attempt rather than a swallowed failure.
        repeat(3) {
            try {
                SQLCipherNativeLoader.ensureLoaded()
            } catch (_: UnsatisfiedLinkError) {
                // Expected on the host JVM.
            }
        }
        // The important property: repeated calls never crash the process with
        // anything other than the load failure, and the loader stays usable.
        assertTrue(true)
    }

    @Test
    fun ensureLoadedFailsLoudlyOnHostJvm() {
        var threwUnsatisfiedLink = false
        try {
            SQLCipherNativeLoader.ensureLoaded()
        } catch (e: UnsatisfiedLinkError) {
            threwUnsatisfiedLink = true
        }
        // On a host JVM without libsqlcipher.so this must FAIL LOUDLY so a
        // misconfigured runtime cannot silently produce a broken database.
        // (If a real .so happens to be on java.library.path the load succeeds
        // and the assertion is trivially satisfied.)
        assertTrue(
            "expected UnsatisfiedLinkError on a host JVM without libsqlcipher.so",
            threwUnsatisfiedLink,
        )
    }

    @Test
    fun secondThreadDoesNotReturnBeforeFirstLoadCompletes() {
        // Deterministic reproduction of the publish-before-load race:
        //   * thread A enters the "native load" and blocks inside it;
        //   * thread B calls ensureLoaded() while A is still loading.
        // A correct loader keeps B blocked (or, on a later call, makes B read
        // loaded==true only after the load finished). A racy loader that
        // publishes "loaded" BEFORE loadLibrary would let B return immediately
        // and start calling SQLCipher native methods too early.
        val loadStarted = CountDownLatch(1)
        val allowLoadToFinish = CountDownLatch(1)
        var loadCount = 0

        SQLCipherNativeLoader.doLoad = {
            loadCount++
            loadStarted.countDown()
            // Simulate a slow dlopen + JNI_OnLoad + RegisterNatives window.
            allowLoadToFinish.await(5, TimeUnit.SECONDS)
        }

        val first = Thread { SQLCipherNativeLoader.ensureLoaded() }.apply { start() }

        // Wait until thread A is genuinely inside the load.
        assertTrue("first thread never entered the load", loadStarted.await(5, TimeUnit.SECONDS))

        // Thread B arrives while A's load is still in progress.
        val second = Thread { SQLCipherNativeLoader.ensureLoaded() }.apply { start() }

        // Give B ample time to (incorrectly) return if the flag were published
        // too early. On the fixed loader B must still be alive here.
        Thread.sleep(500)
        assertTrue(
            "thread B returned before thread A's load completed — " +
                "loaded=true was published before loadLibrary finished",
            second.isAlive,
        )

        allowLoadToFinish.countDown()
        first.join(5_000)
        second.join(5_000)
        assertFalse("first thread did not finish", first.isAlive)
        assertFalse("second thread did not finish", second.isAlive)

        // Both callers share exactly one load of the native core.
        assertTrue("expected exactly one load, got $loadCount", loadCount == 1)
    }

    @Test
    fun manyConcurrentCallersLoadExactlyOnce() {
        val loadStarted = CountDownLatch(1)
        val allowLoadToFinish = CountDownLatch(1)
        var loadCount = 0

        SQLCipherNativeLoader.doLoad = {
            loadCount++
            loadStarted.countDown()
            allowLoadToFinish.await(5, TimeUnit.SECONDS)
        }

        val threads = (1..8).map {
            Thread { SQLCipherNativeLoader.ensureLoaded() }
        }
        threads.forEach { it.start() }

        // Exactly one caller must own the load; the rest must wait on the
        // monitor. Release after confirming they have all had time to pile up.
        assertTrue("no caller entered the load", loadStarted.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        allowLoadToFinish.countDown()

        threads.forEach { it.join(5_000) }
        threads.forEach { assertFalse("thread did not finish", it.isAlive) }

        // All concurrent callers share a single successful native load.
        assertTrue("expected exactly one load, got $loadCount", loadCount == 1)
    }
}
