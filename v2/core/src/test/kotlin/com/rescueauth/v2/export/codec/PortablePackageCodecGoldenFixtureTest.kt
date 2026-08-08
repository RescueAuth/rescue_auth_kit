package com.rescueauth.v2.export.codec

import com.rescueauth.v2.export.PackageSourceMetadata
import com.rescueauth.v2.export.SnapshotBuilder
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.VaultKeyValue
import com.rescueauth.v2.export.VaultPackagePayload
import com.rescueauth.v2.export.VaultSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Golden fixture / format-stability test (PACKAGE_FORMAT.md §Fixture / format
 * stability).
 *
 * Once Phase 3B ships, the package format has compatibility significance. This
 * test pins a fixed v2 package fixture so a future codec refactor can still
 * verify that old packages decrypt (and the fixture keeps the format from
 * silently changing).
 *
 * ## How the fixture is deterministic
 *
 * The codec's default randomness is [PackageCrypto.randomSource] backed by
 * `SecureRandom` (production never uses deterministic RNG). For the fixture we
 * temporarily swap in a deterministic counter source via
 * [PackageCrypto.withDeterministicRandom], so the encoded bytes are fully
 * reproducible. The fixture is then committed as a binary resource and every
 * test run verifies the codec still produces exactly those bytes.
 *
 * ## Fixture contents
 *
 * - synthetic fake secrets ONLY (test-only data, no real credentials);
 * - test-only PIN `"fixture-pin-0000"`;
 * - a FULL_VAULT snapshot with one Authenticator account (TOTP + recovery
 *   codes) and all five Developer Entry types (incl. a deterministic binary
 *   keystore);
 * - a fixed logical payload (deterministic packageId / createdAt).
 */
class PortablePackageCodecGoldenFixtureTest {

    private val fixturePin = "fixture-pin-0000"

    /** Deterministic counter RNG: salt(16) + wrapNonce(24) + payloadNonce(24) + packageKey(32). */
    private fun counterSource(): (Int) -> ByteArray {
        var counter = 0
        return { n -> ByteArray(n) { (counter++ and 0xff).toByte() } }
    }

    private fun fixturePayload(): VaultPackagePayload {
        val keystore = ByteArray(32) { (it * 7 and 0xff).toByte() } // deterministic binary keystore
        val keystoreB64 = java.util.Base64.getEncoder().encodeToString(keystore)
        return VaultPackagePayload(
            logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
            packageId = "fixture-package-id-001",
            createdAt = "2024-06-01T12:00:00Z",
            source = PackageSourceMetadata(
                client = "android-app",
                appVersion = "0.0.0-test-fixture",
                vaultInstanceId = "vault-fixture-001",
            ),
            snapshot = VaultSnapshot(
                scope = SnapshotScope.FULL_VAULT,
                accounts = listOf(
                    SnapshotBuilder.account(
                        "acc-fixture-1",
                        "FixtureProvider",
                        "fixture-user",
                        favorite = true,
                        sortOrder = 1,
                        totps = listOf(SnapshotBuilder.totp("totp-fixture-1")),
                        recoverySets = listOf(
                            SnapshotBuilder.recoverySet(
                                "set-fixture-1",
                                "Fixture backup codes",
                                codes = listOf(
                                    SnapshotBuilder.recoveryCode("code-fixture-1", "AAAA-1111", status = "UNUSED"),
                                    SnapshotBuilder.recoveryCode("code-fixture-2", "BBBB-2222", status = "USED", usedAt = "2024-05-01T00:00:00Z"),
                                ),
                            ),
                        ),
                    ),
                ),
                developerEntries = listOf(
                    SnapshotBuilder.signingKey(
                        "sk-fixture-1",
                        projectName = "fixture-project",
                        packageName = "com.example.fixture",
                        keystoreFileName = "fixture.jks",
                        keystoreBase64 = keystoreB64,
                        storePassword = "fixture-store-pass",
                        keyAlias = "release",
                        keyPassword = "fixture-key-pass",
                        title = "Fixture Signing Key",
                        notes = "fixture notes",
                    ),
                    SnapshotBuilder.apiCredential(
                        "api-fixture-1",
                        serviceName = "fixture-service",
                        accountName = "fixture-account",
                        apiKey = "fixture-api-key-0000",
                        apiSecret = "fixture-api-secret-0000",
                        title = "Fixture API",
                    ),
                    SnapshotBuilder.sshKey(
                        "ssh-fixture-1",
                        keyName = "fixture-ssh",
                        publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAfixture",
                        privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nfixture-private-key\n-----END OPENSSH PRIVATE KEY-----",
                        passphrase = "fixture-passphrase",
                        title = "Fixture SSH",
                    ),
                    SnapshotBuilder.envVarSet(
                        "env-fixture-1",
                        projectName = "fixture-env-project",
                        variables = listOf(
                            VaultKeyValue("FIXTURE_API_URL", "https://fixture.example"),
                            VaultKeyValue("FIXTURE_TOKEN", "fixture-token-0000"),
                        ),
                        title = "Fixture Env",
                    ),
                    SnapshotBuilder.genericSecret(
                        "gen-fixture-1",
                        fields = listOf(VaultKeyValue("fixture-label", "fixture-value")),
                        title = "Fixture Generic",
                    ),
                ),
            ),
        )
    }

    private fun fixtureBytes(): ByteArray {
        val payload = fixturePayload()
        return PackageCrypto.withDeterministicRandom(counterSource()) {
            PortablePackageCodec.encode(payload, fixturePin)
        }
    }

    private fun fixtureFile(): File =
        File("src/test/resources/codec-fixtures/v2_package_fixture_v1.bin")

    @Test
    fun `golden fixture file matches codec output`() {
        val fixtureFile = fixtureFile()
        assertTrue(
            "missing golden fixture ${fixtureFile.absolutePath}",
            fixtureFile.isFile,
        )
        val committed = fixtureFile.readBytes()
        val fresh = fixtureBytes()
        assertArrayEquals(
            "codec output drifted from the committed golden fixture — the v2 package format must not change without a fixture bump",
            committed,
            fresh,
        )
    }

    @Test
    fun `golden fixture decrypts to the expected logical payload`() {
        val committed = fixtureFile().readBytes()
        val decoded = PortablePackageCodec.decode(committed, fixturePin)
        assertEquals(fixturePayload(), decoded)
    }

    @Test
    fun `golden fixture with wrong pin fails`() {
        assertThrows(PackageCodecException.AuthenticationFailed::class.java) {
            PortablePackageCodec.decode(fixtureFile().readBytes(), "wrong-fixture-pin")
        }
    }

    @Test
    fun `deterministic seam produces stable bytes across runs`() {
        assertArrayEquals("deterministic seam must be stable", fixtureBytes(), fixtureBytes())
    }
}
