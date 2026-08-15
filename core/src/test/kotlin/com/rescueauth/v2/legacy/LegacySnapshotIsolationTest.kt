package com.rescueauth.v2.legacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5A §18 — source-level isolation lock between the legacy compatibility
 * layer and the shared logical / native codec layers.
 *
 * Dependency direction locked by ROADMAP §9:
 *   legacy   → shared logical
 *   native package → shared logical
 *   shared logical ✗ legacy ✗ native codec
 *
 * The shared layer (export) must never import legacy types, AND the legacy
 * adapter must never import the native package codec / envelope.
 */
class LegacySnapshotIsolationTest {

    @Test
    fun `legacy adapter never imports native package codec`() {
        val legacyDir = java.io.File("src/main/kotlin/com/rescueauth/v2/legacy")
        assertTrue("legacy source dir missing", legacyDir.isDirectory)

        var offenders = 0
        legacyDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val content = file.readText()
            for (forbidden in listOf(
                "com.rescueauth.v2.export.codec.PortablePackageCodec",
                "com.rescueauth.v2.export.codec.PackageEnvelope",
                "PackageEnvelope",
                "PortablePackageCodec",
            )) {
                if (content.contains(forbidden)) {
                    System.err.println("LEGACY ISOLATION VIOLATION: ${file.name} imports $forbidden")
                    offenders++
                }
            }
        }
        assertFalse(
            "legacy adapter must not depend on native package codec; found $offenders",
            offenders > 0,
        )
    }

    @Test
    fun `shared logical layer never imports legacy types`() {
        // Mirrors the existing guard in the export package; kept here so the
        // Phase 5A PR self-verifies the same boundary from the legacy side.
        val exportDir = java.io.File("src/main/kotlin/com/rescueauth/v2/export")
        assertTrue("export source dir missing", exportDir.isDirectory)

        var offenders = 0
        exportDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            if (file.readText().contains("com.rescueauth.v2.legacy")) {
                System.err.println("ISOLATION VIOLATION: ${file.name} imports a legacy type")
                offenders++
            }
        }
        assertFalse("shared logical layer must not depend on legacy types", offenders > 0)
    }
}
