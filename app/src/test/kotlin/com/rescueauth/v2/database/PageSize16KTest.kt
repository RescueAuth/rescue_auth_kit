package com.rescueauth.v2.database

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Android 15+ 16 KB page-size compatibility guard.
 *
 * Google Play requires apps to support 16 KB page size from 2027-02. The
 * SQLCipher native libraries are aligned to 16 KB by AGP 8.7+
 * (`useLegacyPackaging=false`, the default). This JVM test verifies the
 * packaged APK: every ELF `.so`'s load segment alignment is a multiple of
 * 16384 and the ELF program headers are 16 KB aligned.
 *
 * The APK path is injected by `:app:testDebugUnitTest` via the
 * `rescueauth.debugApk` system property (the test task depends on
 * `assembleDebug`). If the APK cannot be found the test FAILS — it never
 * silently returns, so a missing build cannot produce a false green.
 *
 * What this test can and cannot prove:
 * - CAN prove: every ELF `.so` inside the final APK has PT_LOAD segments with
 *   p_align satisfying the 16 KB rule (zipalign/ELF metadata).
 * - CANNOT prove: runtime `malloc`/`mmap` alignment, actual load behavior on a
 *   16 KB device, or correctness of the C code's internal allocation.
 */
class PageSize16KTest {

    @Test
    fun debugApkNativeLibsAre16kPageAligned() {
        val apk = findDebugApk()
        assertNotNull("APK not found — run :app:assembleDebug first", apk)
        assertTrue("APK must exist and be non-empty: ${apk!!.absolutePath}", apk.length() > 0)

        ZipFile(apk).use { zip ->
            val soEntries = zip.entries().asSequence()
                .filter { it.name.endsWith(".so") && !it.isDirectory }
                .toList()
            assertTrue("no native libs found in $apk", soEntries.isNotEmpty())
            for (entry in soEntries) {
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val worst = elfLoadSegmentAlign(bytes)
                assertTrue(
                    "${entry.name}: PT_LOAD p_align=$worst violates 16 KB rule",
                    worst == 0L,
                )
            }
        }
    }

    private fun findDebugApk(): File? {
        // System property injected by build.gradle.kts (testDebugUnitTest).
        val injected = System.getProperty("rescueauth.debugApk")
        if (!injected.isNullOrBlank()) {
            val f = File(injected)
            if (f.exists()) return f
        }
        // Fallbacks relative to the current working directory, for IDE runs.
        val cwd = File("").absoluteFile
        val candidates = listOf(
            File(cwd, "build/outputs/apk/debug/app-debug.apk"),
            File(cwd, "app/build/outputs/apk/debug/app-debug.apk"),
            File("/workspace/app/build/outputs/apk/debug/app-debug.apk"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: run {
                fail(
                    "Debug APK not found for 16 KB page-size verification. " +
                        "Run ./gradlew :app:assembleDebug before this test, or " +
                        "pass -Drescueauth.debugApk=<path>."
                )
                null
            }
    }

    /**
     * Parses the ELF program header table and checks that every PT_LOAD
     * segment's p_align satisfies the Android 16 KB page-size rule:
     * `p_align <= 4096` OR `p_align % 16384 == 0`.
     */
    private fun elfLoadSegmentAlign(bytes: ByteArray): Long {
        require(bytes.size > 64 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte()) {
            "not an ELF file"
        }
        val is64 = bytes[4] == 2.toByte()
        val programHeaderOffset = if (is64) {
            readU64(bytes, 32)
        } else {
            readU32(bytes, 28).toLong()
        }
        val programHeaderEntrySize = if (is64) {
            readU16(bytes, 54).toLong()
        } else {
            readU16(bytes, 42).toLong()
        }
        val programHeaderCount = if (is64) {
            readU16(bytes, 56).toLong()
        } else {
            readU16(bytes, 44).toLong()
        }

        // Return the LARGEST non-compliant p_align (or 0 if all compliant),
        // encoded so the test message is meaningful. We track the max p_align
        // of segments that violate the rule.
        var worst = 0L
        for (i in 0 until programHeaderCount) {
            val base = programHeaderOffset + i * programHeaderEntrySize
            val pType = readU32(bytes, base.toInt()).toLong()
            if (pType != 1L) continue // PT_LOAD
            // ELF64 p_align at +48; ELF32 p_align at +28.
            val pAlignOffset = if (is64) base + 48 else base + 28
            val pAlign = if (is64) readU64(bytes, pAlignOffset.toInt())
            else readU32(bytes, pAlignOffset.toInt()).toLong()
            val compliant = pAlign <= 4096 || pAlign % 16384 == 0L
            if (!compliant && pAlign > worst) worst = pAlign
        }
        return worst
    }

    private fun readU32(b: ByteArray, off: Int): Int {
        var v = 0
        for (i in 0 until 4) v = v or ((b[off + i].toInt() and 0xff) shl (8 * i))
        return v
    }

    private fun readU16(b: ByteArray, off: Int): Int {
        return (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)
    }

    private fun readU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = v or ((b[off + i].toLong() and 0xff) shl (8 * i))
        }
        return v
    }
}
