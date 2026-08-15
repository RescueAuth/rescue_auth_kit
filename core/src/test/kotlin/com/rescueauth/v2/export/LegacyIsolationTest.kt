package com.rescueauth.v2.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the ROADMAP §9 isolation boundary at the source level:
 *
 * ```
 * legacy-specific parser/crypto/models
 *         ↓
 * shared logical snapshot / merge   (this package)
 *         ↑
 * native package codec
 * ```
 *
 * The shared logical layer (`export`) must NEVER depend on legacy-import
 * types. If it did, deleting the legacy compatibility layer would force a
 * refactor of the Native Package Import — which ROADMAP §9 forbids.
 *
 * This test scans the production sources of the `export` package and fails if
 * any of them imports a `com.rescueauth.v2.legacy` type. It runs on the JVM
 * (source-level check, no reflection needed) and is intentionally cheap.
 */
class LegacyIsolationTest {

    @Test
    fun sharedLogicalLayerNeverImportsLegacyTypes() {
        val exportDir = java.io.File("src/main/kotlin/com/rescueauth/v2/export")
        assertTrue("export source dir missing: ${exportDir.absolutePath}", exportDir.isDirectory)

        var offenders = 0
        exportDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val content = file.readText()
            if (content.contains("com.rescueauth.v2.legacy")) {
                System.err.println("ISOLATION VIOLATION: ${file.name} imports a legacy type")
                offenders++
            }
        }

        assertFalse(
            "shared logical layer must not depend on legacy types (ROADMAP §9); found $offenders offender(s)",
            offenders > 0,
        )
    }
}
