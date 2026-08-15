package com.rescueauth.v2.legacyimport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5B §27 — isolation lock for the VaultRepository legacy-dependency
 * cleanup.
 *
 * ROADMAP §9 / Issue #1 Phase 5B §3: the shared repository/apply boundary
 * must only accept shared logical types ([VaultSnapshot] / [MergePlan]), NOT
 * the legacy-specific [LegacyImportBundle]. The legacy bundle lives only in
 * the dedicated legacy compatibility path ([LegacyImportService]).
 *
 * These are source-level tests so a future refactor cannot silently reintroduce
 * the legacy model into the shared apply boundary.
 */
class LegacyRepositoryIsolationTest {

    @Test
    fun `VaultRepository never imports legacy types`() {
        val file = java.io.File("src/main/kotlin/com/rescueauth/v2/repository/VaultRepository.kt")
        assertTrue("VaultRepository.kt missing", file.isFile)
        val content = file.readText()

        for (forbidden in listOf(
            "com.rescueauth.v2.legacy.LegacyImportBundle",
            "com.rescueauth.v2.legacy.LegacyToV2Mapper",
            "importLegacy",
            "LegacyImportBundle",
        )) {
            assertFalse(
                "VaultRepository must not reference legacy types ($forbidden)",
                content.contains(forbidden),
            )
        }
    }

    @Test
    fun `MergePlanApplicator never imports legacy types`() {
        val file = java.io.File("src/main/kotlin/com/rescueauth/v2/repository/MergePlanApplicator.kt")
        assertTrue("MergePlanApplicator.kt missing", file.isFile)
        val content = file.readText()
        assertFalse(content.contains("com.rescueauth.v2.legacy"))
        assertFalse(content.contains("LegacyImportBundle"))
    }

    @Test
    fun `legacy importer is only referenced by the dedicated legacy path`() {
        // The core importer must NOT leak into the Native ExportImport
        // service/ViewModel (Native flow stays PIN/codec based).
        val nativeDir = java.io.File("src/main/kotlin/com/rescueauth/v2/exportimport")
        assertTrue("exportimport dir missing", nativeDir.isDirectory)
        var offenders = 0
        nativeDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val content = f.readText()
            if (content.contains("com.rescueauth.v2.legacy")) {
                System.err.println("NATIVE FLOW IMPORTS LEGACY: ${f.name}")
                offenders++
            }
        }
        assertFalse("Native ExportImport flow must not import legacy types", offenders > 0)
    }

    @Test
    fun `legacy import path does not depend on the native codec`() {
        val legacyDir = java.io.File("src/main/kotlin/com/rescueauth/v2/legacyimport")
        assertTrue("legacyimport dir missing", legacyDir.isDirectory)
        var offenders = 0
        legacyDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val content = f.readText()
            for (forbidden in listOf(
                "PortablePackageCodec",
                "ExportImportService",
                "ExportImportViewModel",
                "ExportImportError",
                "PinPolicy",
            )) {
                if (content.contains(forbidden)) {
                    System.err.println("LEGACY FLOW IMPORTS NATIVE: ${f.name} -> $forbidden")
                    offenders++
                }
            }
        }
        assertFalse("Legacy import path must not depend on the Native codec/flow", offenders > 0)
    }
}
