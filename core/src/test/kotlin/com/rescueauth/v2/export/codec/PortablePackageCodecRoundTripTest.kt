package com.rescueauth.v2.export.codec

import com.rescueauth.v2.export.PackageSourceMetadata
import com.rescueauth.v2.export.SnapshotBuilder
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.VaultKeyValue
import com.rescueauth.v2.export.VaultPackagePayload
import com.rescueauth.v2.export.VaultSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for the Phase 3B encrypted package codec.
 *
 * Covers every snapshot scope and the binary-keystore exact round-trip
 * (PACKAGE_FORMAT.md §Round-trip requirements).
 */
class PortablePackageCodecRoundTripTest {

    private val pin = "test-pin-1234"

    private fun encodeDecode(
        snapshot: VaultSnapshot,
        source: PackageSourceMetadata = PackageSourceMetadata(client = "test", appVersion = "1.0.0"),
        packageId: String = "pkg-1",
    ): Pair<ByteArray, VaultPackagePayload> {
        val payload = VaultPackagePayload(
            logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
            packageId = packageId,
            createdAt = "2024-01-01T00:00:00Z",
            source = source,
            snapshot = snapshot,
        )
        val bytes = PortablePackageCodec.encode(payload, pin)
        val decoded = PortablePackageCodec.decode(bytes, pin)
        return bytes to decoded
    }

    @Test
    fun `empty logical package round-trips`() {
        val (_, decoded) = encodeDecode(VaultSnapshot(scope = SnapshotScope.FULL_VAULT))
        assertEquals(SnapshotScope.FULL_VAULT, decoded.snapshot.scope)
        assertTrue(decoded.snapshot.accounts.isEmpty())
        assertTrue(decoded.snapshot.developerEntries.isEmpty())
    }

    @Test
    fun `authenticator-only package round-trips`() {
        val snapshot = VaultSnapshot(
            scope = SnapshotScope.AUTHENTICATOR_ONLY,
            accounts = listOf(
                SnapshotBuilder.account(
                    "acc-1", "GitHub", "alice",
                    totps = listOf(SnapshotBuilder.totp("totp-1")),
                    recoverySets = listOf(
                        SnapshotBuilder.recoverySet(
                            "set-1", "Backup codes",
                            codes = listOf(
                                SnapshotBuilder.recoveryCode("c1", "AAAA-BBBB", status = "USED"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val (_, decoded) = encodeDecode(snapshot)
        assertEquals(SnapshotScope.AUTHENTICATOR_ONLY, decoded.snapshot.scope)
        assertEquals(1, decoded.snapshot.accounts.size)
        assertEquals(1, decoded.snapshot.accounts[0].totpCredentials.size)
        assertEquals(1, decoded.snapshot.accounts[0].recoveryCodeSets[0].codes.size)
    }

    @Test
    fun `developer-only package round-trips`() {
        val snapshot = VaultSnapshot(
            scope = SnapshotScope.DEVELOPER_ONLY,
            developerEntries = listOf(
                SnapshotBuilder.sshKey("ssh-1"),
                SnapshotBuilder.envVarSet("env-1", variables = listOf(VaultKeyValue("API_KEY", "abc"))),
            ),
        )
        val (_, decoded) = encodeDecode(snapshot)
        assertEquals(SnapshotScope.DEVELOPER_ONLY, decoded.snapshot.scope)
        assertEquals(2, decoded.snapshot.developerEntries.size)
    }

    @Test
    fun `selected-items package round-trips`() {
        val snapshot = VaultSnapshot(
            scope = SnapshotScope.SELECTED_ITEMS,
            accounts = listOf(SnapshotBuilder.account("acc-1", "GitHub", "alice")),
            developerEntries = listOf(SnapshotBuilder.genericSecret("g1")),
        )
        val (_, decoded) = encodeDecode(snapshot)
        assertEquals(SnapshotScope.SELECTED_ITEMS, decoded.snapshot.scope)
        assertEquals(1, decoded.snapshot.accounts.size)
        assertEquals(1, decoded.snapshot.developerEntries.size)
    }

    @Test
    fun `full vault package with all five developer types round-trips`() {
        val snapshot = VaultSnapshot(
            scope = SnapshotScope.FULL_VAULT,
            accounts = listOf(
                SnapshotBuilder.account(
                    "acc-1", "GitHub", "alice",
                    totps = listOf(SnapshotBuilder.totp("totp-1")),
                    recoverySets = listOf(
                        SnapshotBuilder.recoverySet(
                            "set-1", "Backup codes",
                            codes = listOf(SnapshotBuilder.recoveryCode("c1", "AAAA-BBBB", status = "UNUSED")),
                        ),
                    ),
                ),
            ),
            developerEntries = listOf(
                SnapshotBuilder.signingKey("sk-1"),
                SnapshotBuilder.apiCredential("api-1"),
                SnapshotBuilder.sshKey("ssh-1"),
                SnapshotBuilder.envVarSet("env-1", variables = listOf(VaultKeyValue("A", "1"))),
                SnapshotBuilder.genericSecret("gen-1", fields = listOf(VaultKeyValue("x", "y"))),
            ),
        )
        val (bytes, decoded) = encodeDecode(snapshot)
        assertEquals(snapshot, decoded.snapshot)
        assertEquals(5, decoded.snapshot.developerEntries.size)
        assertTrue(bytes.isNotEmpty())
    }

    @Test
    fun `recovery used and unused round-trips`() {
        val snapshot = VaultSnapshot(
            scope = SnapshotScope.AUTHENTICATOR_ONLY,
            accounts = listOf(
                SnapshotBuilder.account(
                    "acc-1", "GitHub", "alice",
                    recoverySets = listOf(
                        SnapshotBuilder.recoverySet(
                            "set-1", "Backup codes",
                            codes = listOf(
                                SnapshotBuilder.recoveryCode("c1", "AAAA", status = "USED", usedAt = "2024-01-02"),
                                SnapshotBuilder.recoveryCode("c2", "BBBB", status = "UNUSED"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val (_, decoded) = encodeDecode(snapshot)
        val codes = decoded.snapshot.accounts[0].recoveryCodeSets[0].codes
        assertEquals("USED", codes[0].status)
        assertEquals("2024-01-02", codes[0].usedAt)
        assertEquals("UNUSED", codes[1].status)
    }

    @Test
    fun `random binary keystore exact byte round-trip`() {
        // Random 4 KiB binary keystore — must survive encode → decode
        // byte-for-byte (JSON/Base64 must not corrupt binary data).
        val randomBytes = ByteArray(4096)
        java.security.SecureRandom().nextBytes(randomBytes)
        val keystoreB64 = java.util.Base64.getEncoder().encodeToString(randomBytes)

        val snapshot = VaultSnapshot(
            scope = SnapshotScope.DEVELOPER_ONLY,
            developerEntries = listOf(
                SnapshotBuilder.signingKey(
                    "sk-1",
                    projectName = "my-project",
                    packageName = "com.example.app",
                    keystoreFileName = "release.jks",
                    keystoreBase64 = keystoreB64,
                    storePassword = "store-pass-1",
                    keyAlias = "release",
                    keyPassword = "key-pass-1",
                ),
            ),
        )
        val (_, decoded) = encodeDecode(snapshot)
        val signing = decoded.snapshot.developerEntries.filterIsInstance<com.rescueauth.v2.export.VaultAndroidSigningKey>().single()
        assertEquals(keystoreB64, signing.keystoreBase64)
        assertArrayEquals(
            randomBytes,
            java.util.Base64.getDecoder().decode(signing.keystoreBase64),
        )
    }

    @Test
    fun `same payload same pin two exports differ but decrypt identically`() {
        val payload = VaultPackagePayload(
            logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
            packageId = "pkg-1",
            createdAt = "2024-01-01T00:00:00Z",
            source = PackageSourceMetadata(client = "test", appVersion = "1.0.0"),
            snapshot = VaultSnapshot(
                scope = SnapshotScope.AUTHENTICATOR_ONLY,
                accounts = listOf(
                    SnapshotBuilder.account(
                        "acc-1", "GitHub", "alice",
                        totps = listOf(SnapshotBuilder.totp("totp-1")),
                    ),
                ),
            ),
        )
        val first = PortablePackageCodec.encode(payload, pin)
        val second = PortablePackageCodec.encode(payload, pin)

        // Same payload + same PIN must NOT produce identical ciphertext.
        assertNotEquals("two exports must differ", first.contentToString(), second.contentToString())
        // Sanity: they are both valid and decrypt to the same payload.
        assertEquals(payload, PortablePackageCodec.decode(first, pin))
        assertEquals(payload, PortablePackageCodec.decode(second, pin))
    }

    @Test
    fun `two exports with same payload decrypt to identical logical payload`() {
        val payload = VaultPackagePayload(
            logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
            packageId = "pkg-1",
            createdAt = "2024-01-01T00:00:00Z",
            source = PackageSourceMetadata(client = "test", appVersion = "1.0.0"),
            snapshot = VaultSnapshot(
                scope = SnapshotScope.FULL_VAULT,
                accounts = listOf(
                    SnapshotBuilder.account(
                        "acc-1", "GitHub", "alice",
                        totps = listOf(SnapshotBuilder.totp("totp-1")),
                    ),
                ),
            ),
        )
        val first = PortablePackageCodec.encode(payload, pin)
        val second = PortablePackageCodec.encode(payload, pin)
        assertEquals(PortablePackageCodec.decode(first, pin), PortablePackageCodec.decode(second, pin))
        assertEquals(payload, PortablePackageCodec.decode(first, pin))
    }

    @Test
    fun `salt nonce and wrapped key differ between exports`() {
        val payload = VaultPackagePayload(
            logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
            packageId = "pkg-1",
            createdAt = "2024-01-01T00:00:00Z",
            snapshot = VaultSnapshot(scope = SnapshotScope.FULL_VAULT),
        )
        val first = PortablePackageCodec.encode(payload, pin)
        val second = PortablePackageCodec.encode(payload, pin)

        // Salt region (offset 27..27+16).
        val salt1 = first.copyOfRange(27, 27 + 16)
        val salt2 = second.copyOfRange(27, 27 + 16)
        assertNotEquals("salt must be regenerated per export", salt1.contentToString(), salt2.contentToString())

        // Wrapping nonce (after salt + algorithm + nonce-length byte).
        val wrapNonceOff = 27 + 16 + 2
        val wrapNonce1 = first.copyOfRange(wrapNonceOff, wrapNonceOff + 24)
        val wrapNonce2 = second.copyOfRange(wrapNonceOff, wrapNonceOff + 24)
        assertNotEquals(
            "wrapping nonce must be regenerated per export",
            wrapNonce1.contentToString(),
            wrapNonce2.contentToString(),
        )
    }

    @Test
    fun `package plaintext bytes do not contain known test secrets`() {
        // Must be valid base32 (the TOTP validator rejects non-base32 secrets),
        // and must not appear anywhere in the package bytes.
        val secret = "JBSWY3DPEHPK3PXPQQQQQQQQQQQQQQ"
        val snapshot = VaultSnapshot(
            scope = SnapshotScope.AUTHENTICATOR_ONLY,
            accounts = listOf(
                SnapshotBuilder.account(
                    "acc-1", "GitHub", "alice",
                    totps = listOf(SnapshotBuilder.totp("totp-1", secret = secret)),
                ),
            ),
        )
        val payload = VaultPackagePayload(
            logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
            packageId = "pkg-1",
            createdAt = "2024-01-01T00:00:00Z",
            snapshot = snapshot,
        )
        val bytes = PortablePackageCodec.encode(payload, pin)
        val asString = bytes.toString(Charsets.ISO_8859_1)
        // The secret must not appear in the ciphertext or the plaintext header.
        assertTrue("secret leaked into package bytes", !asString.contains(secret))
    }

    @Test
    fun `plaintext header does not expose account or developer metadata`() {
        val developerNote = "DEVELOPER-SECRET-NOTE-XYZ"
        val accountName = "alice-secret-account"
        val snapshot = VaultSnapshot(
            scope = SnapshotScope.FULL_VAULT,
            accounts = listOf(SnapshotBuilder.account("acc-1", "GitHub", accountName)),
            developerEntries = listOf(
                SnapshotBuilder.genericSecret("g1", title = developerNote),
            ),
        )
        val payload = VaultPackagePayload(
            logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
            packageId = "pkg-1",
            createdAt = "2024-01-01T00:00:00Z",
            snapshot = snapshot,
        )
        val bytes = PortablePackageCodec.encode(payload, pin)

        // The header region (up to payload ciphertext) must not contain any
        // user business metadata.
        val envelope = PackageHeaderParser.parse(bytes)
        val headerText = String(envelope.headerAadBytes, Charsets.ISO_8859_1)
        assertTrue("account name leaked into header", !headerText.contains(accountName))
        assertTrue("developer title leaked into header", !headerText.contains(developerNote))
        assertTrue("packageId leaked into header", !headerText.contains("pkg-1"))
    }
}
