package com.rescueauth.v2.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Canonicalization + semantic fingerprint tests.
 *
 * Security principle: never merge two different secrets; a fingerprint is
 * secret-derived material that must not be persisted or leaked into a
 * plaintext header.
 */
class CanonicalizationTest {

    @Test
    fun canonicalSecretStripsWhitespaceAndDashesAndIsCaseInsensitive() {
        assertEquals(
            Canonicalization.canonicalSecret("JBSWY3DPEHPK3PXP"),
            Canonicalization.canonicalSecret("  jbswy3dpehp k3pxp  "),
        )
        assertEquals(
            Canonicalization.canonicalSecret("JBSWY-3DPEH-PK3P-XP"),
            Canonicalization.canonicalSecret("JBSWY3DPEHPK3PXP"),
        )
    }

    @Test
    fun canonicalLabelsAreTrimmedCollapsedAndCaseFolded() {
        assertEquals(
            Canonicalization.canonicalLabel("  GitHub   Enterprise  "),
            Canonicalization.canonicalLabel("github enterprise"),
        )
    }

    @Test
    fun blankLabelsCollapseButStable() {
        // A blank label is canonicalised to empty deterministically.
        assertEquals(
            Canonicalization.canonicalLabel(""),
            Canonicalization.canonicalLabel(" "),
        )
        // And it is stable across calls.
        assertEquals(
            Canonicalization.canonicalLabel(""),
            Canonicalization.canonicalLabel(""),
        )
    }

    @Test
    fun totpFingerprintIgnoresAccountLabelsButUsesSecretAndParams() {
        val fpA = Canonicalization.totpFingerprint(
            "JBSWY3DPEHPK3PXP", "SHA1", 6, 30,
        )
        val fpB = Canonicalization.totpFingerprint(
            "JBSWY3DPEHPK3PXP", "SHA1", 6, 30,
        )
        assertEquals(fpA, fpB)

        // Same secret + params regardless of where it lives.
        val cred = SnapshotBuilder.totp("t1", secret = "JBSWY3DPEHPK3PXP")
        val credRenamed = SnapshotBuilder.totp("t2", secret = "JBSWY3DPEHPK3PXP")
        assertEquals(
            Canonicalization.totpFingerprint(cred),
            Canonicalization.totpFingerprint(credRenamed),
        )

        // Different secret -> different fingerprint (never merged).
        val other = SnapshotBuilder.totp("t3", secret = "4F6VS6KX3UXWY2FQ")
        assertNotEquals(
            Canonicalization.totpFingerprint(cred),
            Canonicalization.totpFingerprint(other),
        )
    }

    @Test
    fun totpFingerprintChangesWhenAlgorithmOrParamsChange() {
        val base = SnapshotBuilder.totp("t1", secret = "JBSWY3DPEHPK3PXP")
        assertNotEquals(
            Canonicalization.totpFingerprint(base),
            Canonicalization.totpFingerprint(base.copy(algorithm = "SHA256")),
        )
        assertNotEquals(
            Canonicalization.totpFingerprint(base),
            Canonicalization.totpFingerprint(base.copy(digits = 8)),
        )
        assertNotEquals(
            Canonicalization.totpFingerprint(base),
            Canonicalization.totpFingerprint(base.copy(periodSeconds = 60)),
        )
    }

    @Test
    fun recoverySetFingerprintIgnoresStatusButUsesTitleAndValues() {
        val setA = SnapshotBuilder.recoverySet(
            "set-1", "Backup codes",
            codes = listOf(
                SnapshotBuilder.recoveryCode("c1", "AAAA-BBBB", status = "UNUSED", sortOrder = 0),
                SnapshotBuilder.recoveryCode("c2", "CCCC-DDDD", status = "UNUSED", sortOrder = 1),
            ),
        )
        // Same set on another device: different stable ids, codes marked used.
        val setB = SnapshotBuilder.recoverySet(
            "set-2", "backup codes",
            codes = listOf(
                SnapshotBuilder.recoveryCode("c9", "AAAA-BBBB", status = "USED", sortOrder = 0),
                SnapshotBuilder.recoveryCode("c8", "CCCC-DDDD", status = "USED", sortOrder = 1),
            ),
        )
        assertEquals(
            Canonicalization.recoverySetFingerprint(setA.title, setA.codes),
            Canonicalization.recoverySetFingerprint(setB.title, setB.codes),
        )
    }

    @Test
    fun recoverySetFingerprintIsSensitiveToCodeValues() {
        val setA = SnapshotBuilder.recoverySet(
            "set-1", "Backup codes",
            codes = listOf(SnapshotBuilder.recoveryCode("c1", "AAAA-BBBB")),
        )
        val setB = SnapshotBuilder.recoverySet(
            "set-1", "Backup codes",
            codes = listOf(SnapshotBuilder.recoveryCode("c1", "ZZZZ-ZZZZ")),
        )
        assertNotEquals(
            Canonicalization.recoverySetFingerprint(setA.title, setA.codes),
            Canonicalization.recoverySetFingerprint(setB.title, setB.codes),
        )
    }

    @Test
    fun fingerprintIsSecretDerivedSha256OfDeterministicLength() {
        val fp = Canonicalization.totpFingerprint("JBSWY3DPEHPK3PXP", "SHA1", 6, 30)
        assertEquals(64, fp.length) // SHA-256 hex
        assertTrue(fp.matches(Regex("[0-9a-f]{64}")))
    }
}
