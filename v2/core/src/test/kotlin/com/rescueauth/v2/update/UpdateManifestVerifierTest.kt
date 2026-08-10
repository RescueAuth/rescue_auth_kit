package com.rescueauth.v2.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Core update-protocol unit tests (Issue #20 §22).
 *
 * Covers the signature/byte contract, schema validation, URL/field validation
 * and forward-compatibility. All keys used here are TEST-ONLY (see
 * [TestEd25519]).
 */
class UpdateManifestVerifierTest {

    private val keyPair = TestEd25519.generateKeyPair()
    private val verifier = UpdateManifestVerifier(keyPair.publicKeyRaw)

    private fun signedJson(json: String = TestManifestJson.build()): Pair<String, String> {
        val bytes = json.toByteArray()
        return json to TestEd25519.sign(keyPair.privateKey, bytes)
    }

    // --- 1. valid Ed25519 signature accepted ---
    @Test
    fun validSignatureAccepted() {
        val (json, sig) = signedJson()
        val result = verifier.verify(json.toByteArray(), sig)
        assertTrue("expected valid", result is UpdateManifestVerifier.Result.Valid)
    }

    // --- 2. one-byte manifest mutation rejected ---
    @Test
    fun oneByteMutationRejected() {
        val (json, sig) = signedJson()
        val mutated = json.toByteArray()
        // Flip a byte inside the versionName value.
        val idx = mutated.indexOf('1'.code.toByte())
        if (idx >= 0) mutated[idx] = '9'.code.toByte()
        val result = verifier.verify(mutated, sig)
        assertTrue("expected invalid", result is UpdateManifestVerifier.Result.Invalid)
    }

    // --- 3. wrong public key rejected ---
    @Test
    fun wrongPublicKeyRejected() {
        val (json, sig) = signedJson()
        val wrong = UpdateManifestVerifier(TestEd25519.generateKeyPair().publicKeyRaw)
        val result = wrong.verify(json.toByteArray(), sig)
        assertTrue("expected invalid", result is UpdateManifestVerifier.Result.Invalid)
    }

    // --- 4. malformed signature rejected ---
    @Test
    fun malformedSignatureRejected() {
        val (json, _) = signedJson()
        val result = verifier.verify(json.toByteArray(), "not-base64!!!")
        assertTrue(result is UpdateManifestVerifier.Result.Invalid)
        assertEquals(
            UpdateManifestVerifier.Result.Reason.INVALID_BASE64,
            (result as UpdateManifestVerifier.Result.Invalid).reason,
        )
    }

    // --- 5. wrong-length signature rejected ---
    @Test
    fun wrongLengthSignatureRejected() {
        val (json, _) = signedJson()
        val shortSig = java.util.Base64.getEncoder()
            .encodeToString(ByteArray(16) { 1 })
        val result = verifier.verify(json.toByteArray(), shortSig)
        assertTrue(result is UpdateManifestVerifier.Result.Invalid)
        assertEquals(
            UpdateManifestVerifier.Result.Reason.INVALID_BASE64,
            (result as UpdateManifestVerifier.Result.Invalid).reason,
        )
    }

    // --- 6. invalid Base64 rejected ---
    @Test
    fun invalidBase64Rejected() {
        val (json, _) = signedJson()
        val result = verifier.verify(json.toByteArray(), "###invalid###")
        assertTrue(result is UpdateManifestVerifier.Result.Invalid)
    }

    // --- 7. signature verifies exact raw bytes ---
    @Test
    fun signatureVerifiesExactRawBytes() {
        val bytes = TestManifestJson.defaultBytes()
        val sig = TestEd25519.sign(keyPair.privateKey, bytes)
        val result = verifier.verify(bytes, sig)
        assertTrue("expected valid", result is UpdateManifestVerifier.Result.Valid)
    }

    // --- 8. reformatting same logical JSON invalidates original signature ---
    @Test
    fun reformattingInvalidatesOriginalSignature() {
        val (json, sig) = signedJson()
        // Reformat: same logical JSON, different whitespace.
        val reformatted = json.replace("{", "{\n  ").replace(",", ",\n  ")
        val result = verifier.verify(reformatted.toByteArray(), sig)
        assertTrue(
            "reformatted bytes must fail the original signature",
            result is UpdateManifestVerifier.Result.Invalid,
        )
    }

    // --- signature encoding: optional trailing newline allowed ---
    @Test
    fun trailingNewlineSignatureAllowed() {
        val (json, sig) = signedJson()
        val result = verifier.verify(json.toByteArray(), sig + "\n")
        assertTrue("trailing newline must be allowed", result is UpdateManifestVerifier.Result.Valid)
    }

    // --- oversized signature rejected before decode ---
    @Test
    fun oversizedSignatureRejected() {
        val (json, _) = signedJson()
        val big = "A".repeat(UpdateSizeLimits.MAX_SIGNATURE_BYTES + 1)
        val result = verifier.verify(json.toByteArray(), big)
        assertTrue(result is UpdateManifestVerifier.Result.Invalid)
        assertEquals(
            UpdateManifestVerifier.Result.Reason.SIGNATURE_TOO_LARGE,
            (result as UpdateManifestVerifier.Result.Invalid).reason,
        )
    }

    // --- signature decode helper ---
    @Test
    fun decodeSignatureAcceptsValidAndRejectsInvalid() {
        val sig = TestEd25519.sign(keyPair.privateKey, ByteArray(8) { 1 })
        assertTrue(UpdateManifestVerifier.decodeSignature(sig)!!.size == 64)
        assertNull(UpdateManifestVerifier.decodeSignature("not-base64"))
        assertNull(UpdateManifestVerifier.decodeSignature(""))
    }
}

/** Parser / schema validation tests (Issue #20 §22 items 9-18). */
class UpdateManifestParserTest {

    private fun parseOrFail(json: String): UpdateManifest = UpdateManifestParser.parse(json.toByteArray())

    private fun assertRejects(json: String, kind: ManifestParseException.Kind) {
        try {
            parseOrFail(json)
            fail("expected $kind but parse succeeded")
        } catch (e: ManifestParseException) {
            assertEquals(kind, e.kind)
        }
    }

    private fun validBase() = TestManifestJson.build()

    // --- 9. schemaVersion != 1 rejected ---
    @Test
    fun schemaVersionNot1Rejected() {
        assertRejects(TestManifestJson.build(schemaVersion = 2), ManifestParseException.Kind.UNSUPPORTED_SCHEMA)
    }

    // --- 10. channel != stable rejected ---
    @Test
    fun channelNotStableRejected() {
        assertRejects(TestManifestJson.build(channel = "beta"), ManifestParseException.Kind.CHANNEL_UNSUPPORTED)
    }

    // --- 11. missing required field rejected ---
    @Test
    fun missingRequiredFieldRejected() {
        val json = validBase()
        // Remove versionCode by hand.
        val stripped = json.replace(",\"versionCode\":${TestManifestJson.VERSION_CODE}", "")
        assertRejects(stripped, ManifestParseException.Kind.MISSING_REQUIRED_FIELD)
    }

    // --- 12. invalid ISO date rejected ---
    @Test
    fun invalidIsoDateRejected() {
        assertRejects(TestManifestJson.build(publishedAt = "not-a-date"), ManifestParseException.Kind.INVALID_PUBLISHED_AT)
    }

    // --- 13. non-HTTPS apkUrl rejected ---
    @Test
    fun nonHttpsApkUrlRejected() {
        assertRejects(TestManifestJson.build(apkUrl = "http://example.com/a.apk"), ManifestParseException.Kind.APK_URL_NOT_HTTPS)
        assertRejects(TestManifestJson.build(apkUrl = "file:///a.apk"), ManifestParseException.Kind.APK_URL_NOT_HTTPS)
    }

    // --- 14. non-HTTPS releaseNotesUrl rejected ---
    @Test
    fun nonHttpsReleaseNotesUrlRejected() {
        assertRejects(TestManifestJson.build(releaseNotesUrl = "http://example.com/n.html"), ManifestParseException.Kind.RELEASE_NOTES_URL_NOT_HTTPS)
    }

    // --- 15. invalid apkSha256 rejected ---
    @Test
    fun invalidApkSha256Rejected() {
        assertRejects(TestManifestJson.build(apkSha256 = "xyz"), ManifestParseException.Kind.APK_SHA256_INVALID)
        assertRejects(TestManifestJson.build(apkSha256 = "A".repeat(64)), ManifestParseException.Kind.APK_SHA256_INVALID) // uppercase
    }

    // --- 16. invalid versionCode rejected ---
    @Test
    fun invalidVersionCodeRejected() {
        assertRejects(TestManifestJson.build(versionCode = 0), ManifestParseException.Kind.VERSION_CODE_NOT_POSITIVE)
    }

    // --- 17. minSupported > latest rejected ---
    @Test
    fun minSupportedAboveLatestRejected() {
        assertRejects(
            TestManifestJson.build(minSupportedVersionCode = 99999),
            ManifestParseException.Kind.MIN_SUPPORTED_ABOVE_LATEST,
        )
    }

    // --- 18. unknown additive field tolerated ---
    @Test
    fun unknownAdditiveFieldTolerated() {
        val json = TestManifestJson.build(extraFields = mapOf("futureField" to jsonStr("future")))
        val manifest = parseOrFail(json)
        assertEquals(TestManifestJson.VERSION_CODE, manifest.versionCode)
    }

    // --- valid manifest parses fully ---
    @Test
    fun validManifestParses() {
        val manifest = parseOrFail(validBase())
        assertEquals(1, manifest.schemaVersion)
        assertEquals("stable", manifest.channel)
        assertEquals(TestManifestJson.VERSION_CODE, manifest.versionCode)
        assertEquals(TestManifestJson.MIN_SUPPORTED, manifest.minSupportedVersionCode)
        assertEquals(Severity.NORMAL, manifest.severity)
    }

    private fun jsonStr(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
