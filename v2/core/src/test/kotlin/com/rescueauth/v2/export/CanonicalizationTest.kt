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

    // ------------------------------------------------------------------
    // Developer Entry logical fingerprint (FULL LOGICAL PAYLOAD)
    // ------------------------------------------------------------------

    @Test
    fun developerLogicalFingerprintIsSensitiveToEveryUserMeaningfulField() {
        // Same stableId-level entry: same private key but different keyName /
        // title / notes → different full logical payload (any user-meaningful
        // field counts, NOT only the sensitive payload).
        val a = SnapshotBuilder.sshKey(
            "k1", keyName = "work",
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAA\n-----END OPENSSH PRIVATE KEY-----",
        )
        val renamed = SnapshotBuilder.sshKey(
            "k1", keyName = "renamed", title = "Renamed",
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAA\n-----END OPENSSH PRIVATE KEY-----",
        )
        assertNotEquals(
            Canonicalization.developerLogicalFingerprint(a),
            Canonicalization.developerLogicalFingerprint(renamed),
        )

        // Different private key -> different fingerprint (never merged).
        val c = SnapshotBuilder.sshKey(
            "k3", keyName = "work",
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nBBB\n-----END OPENSSH PRIVATE KEY-----",
        )
        assertNotEquals(
            Canonicalization.developerLogicalFingerprint(a),
            Canonicalization.developerLogicalFingerprint(c),
        )
    }

    @Test
    fun developerLogicalFingerprintIsSensitiveToKeystoreBytesPasswordsAndLabels() {
        val base = SnapshotBuilder.signingKey("sk1", keystoreBase64 = "AAECAwQFBgc=")
        // Identical bytes + passwords but different project/package (a
        // user-meaningful logical field) → different fingerprint.
        val diffProject = SnapshotBuilder.signingKey(
            "sk3", projectName = "other", packageName = "com.other.app",
            keystoreBase64 = "AAECAwQFBgc=",
        )
        assertNotEquals(
            Canonicalization.developerLogicalFingerprint(base),
            Canonicalization.developerLogicalFingerprint(diffProject),
        )

        assertNotEquals(
            Canonicalization.developerLogicalFingerprint(base),
            Canonicalization.developerLogicalFingerprint(base.copy(keystoreBase64 = "AQIDBAUGBwg=")),
        )
        assertNotEquals(
            Canonicalization.developerLogicalFingerprint(base),
            Canonicalization.developerLogicalFingerprint(base.copy(storePassword = "different")),
        )
        assertNotEquals(
            Canonicalization.developerLogicalFingerprint(base),
            Canonicalization.developerLogicalFingerprint(base.copy(keyPassword = "different")),
        )
        assertNotEquals(
            Canonicalization.developerLogicalFingerprint(base),
            Canonicalization.developerLogicalFingerprint(base.copy(title = "Renamed")),
        )
    }

    @Test
    fun developerLogicalFingerprintForEnvAndGenericIsOrderInsensitiveAndValueSensitive() {
        val envA = SnapshotBuilder.envVarSet(
            "e1",
            variables = listOf(VaultKeyValue("API_KEY", "secret-1"), VaultKeyValue("URL", "https://x")),
        )
        val envB = SnapshotBuilder.envVarSet(
            "e2",
            variables = listOf(VaultKeyValue("url", "https://x"), VaultKeyValue("api_key", "secret-1")),
        )
        // Same key/value pairs in different order / case -> same fingerprint.
        assertEquals(
            Canonicalization.developerLogicalFingerprint(envA),
            Canonicalization.developerLogicalFingerprint(envB),
        )

        val envC = SnapshotBuilder.envVarSet(
            "e3",
            variables = listOf(VaultKeyValue("API_KEY", "secret-2"), VaultKeyValue("URL", "https://x")),
        )
        assertNotEquals(
            Canonicalization.developerLogicalFingerprint(envA),
            Canonicalization.developerLogicalFingerprint(envC),
        )

        // Same values but different variable NAME -> different fingerprint
        // (variable names are user-meaningful logical fields).
        val envD = SnapshotBuilder.envVarSet(
            "e4",
            variables = listOf(VaultKeyValue("OTHER_NAME", "secret-1"), VaultKeyValue("URL", "https://x")),
        )
        assertNotEquals(
            Canonicalization.developerLogicalFingerprint(envA),
            Canonicalization.developerLogicalFingerprint(envD),
        )

        val genA = SnapshotBuilder.genericSecret(
            "g1",
            fields = listOf(VaultKeyValue("token", "t-1")),
        )
        val genB = SnapshotBuilder.genericSecret(
            "g2",
            fields = listOf(VaultKeyValue("token", "t-2")),
        )
        assertNotEquals(
            Canonicalization.developerLogicalFingerprint(genA),
            Canonicalization.developerLogicalFingerprint(genB),
        )

        // Same values but different field LABEL -> different fingerprint.
        val genC = SnapshotBuilder.genericSecret(
            "g3",
            fields = listOf(VaultKeyValue("other-label", "t-1")),
        )
        assertNotEquals(
            Canonicalization.developerLogicalFingerprint(genA),
            Canonicalization.developerLogicalFingerprint(genC),
        )
    }

    @Test
    fun apiCredentialLogicalFingerprintUsesKeySecretAndDisplayLabels() {
        val a = SnapshotBuilder.apiCredential("api1", serviceName = "stripe", apiKey = "sk_1", apiSecret = "sec-1")
        // Same apiKey/apiSecret but different serviceName/accountName →
        // different full logical payload (CONFLICT on same stableId).
        val renamed = SnapshotBuilder.apiCredential("api1", serviceName = "renamed", apiKey = "sk_1", apiSecret = "sec-1")
        assertNotEquals(
            Canonicalization.developerLogicalFingerprint(a),
            Canonicalization.developerLogicalFingerprint(renamed),
        )
        assertNotEquals(
            Canonicalization.developerLogicalFingerprint(a),
            Canonicalization.developerLogicalFingerprint(a.copy(apiSecret = "sec-2")),
        )
    }

    @Test
    fun developerLogicalFingerprintIgnoresPureTechnicalMetadata() {
        val a = SnapshotBuilder.apiCredential("api1", apiKey = "sk_1", apiSecret = "sec-1")
        // createdAt / updatedAt are pure technical metadata: a same-stableId
        // entry whose only difference is these timestamps is DUPLICATE, so the
        // fingerprint must ignore them.
        val b = a.copy(createdAt = "2025-01-01T00:00:00Z", updatedAt = "2025-01-01T00:00:00Z")
        assertEquals(
            Canonicalization.developerLogicalFingerprint(a),
            Canonicalization.developerLogicalFingerprint(b),
        )
    }
}
