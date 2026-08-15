package com.rescueauth.v2.export

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON round-trip of the portable logical schema.
 *
 * Phase 3B will serialize [VaultPackagePayload] into the AEAD-encrypted
 * payload; Phase 3C/3D will deserialize it back. A round-trip test locks:
 *
 * - the sealed Developer Entry hierarchy (polymorphic serial names) survives
 *   encode → decode intact;
 * - the five Developer Entry types carry all their fields (incl. the binary
 *   keystore base64 and key/value lists);
 * - `scope` / partial snapshots round-trip;
 * - no platform / Room objects leak into the JSON (schema is logical only).
 */
class VaultSnapshotSerializationTest {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun fullVaultSnapshotRoundTripsWithAllFiveDeveloperTypes() {
        val snapshot = VaultSnapshot(
            scope = SnapshotScope.FULL_VAULT,
            accounts = listOf(
                SnapshotBuilder.account(
                    "acc-1", "GitHub", "alice",
                    totps = listOf(SnapshotBuilder.totp("totp-1")),
                    recoverySets = listOf(
                        SnapshotBuilder.recoverySet(
                            "set-1", "Backup codes",
                            codes = listOf(SnapshotBuilder.recoveryCode("c1", "AAAA-BBBB", status = "USED")),
                        )
                    ),
                )
            ),
            developerEntries = listOf(
                SnapshotBuilder.signingKey("sk-1", keystoreBase64 = "AAECAwQFBgc="),
                SnapshotBuilder.apiCredential("api-1"),
                SnapshotBuilder.sshKey("ssh-1"),
                SnapshotBuilder.envVarSet("env-1", variables = listOf(VaultKeyValue("A", "1"))),
                SnapshotBuilder.genericSecret("gen-1", fields = listOf(VaultKeyValue("x", "y"))),
            ),
        )

        val encoded = json.encodeToString(VaultSnapshot.serializer(), snapshot)
        val decoded = json.decodeFromString(VaultSnapshot.serializer(), encoded)

        assertEquals(snapshot, decoded)
        assertEquals(5, decoded.developerEntries.size)
        val types = decoded.developerEntries.map { it::class.simpleName }.toSet()
        assertEquals(
            setOf(
                "VaultAndroidSigningKey",
                "VaultApiCredential",
                "VaultSshKey",
                "VaultEnvironmentVariableSet",
                "VaultGenericSecret",
            ),
            types,
        )
        // The binary keystore survives the round trip intact.
        val signing = decoded.developerEntries.filterIsInstance<VaultAndroidSigningKey>().single()
        assertEquals("AAECAwQFBgc=", signing.keystoreBase64)
        assertEquals("com.example.demo", signing.packageName)
    }

    @Test
    fun developerOnlySnapshotRoundTrips() {
        val snapshot = VaultSnapshot(
            scope = SnapshotScope.DEVELOPER_ONLY,
            developerEntries = listOf(SnapshotBuilder.sshKey("ssh-1")),
        )
        val roundTrip = json.decodeFromString(
            VaultSnapshot.serializer(),
            json.encodeToString(VaultSnapshot.serializer(), snapshot),
        )
        assertEquals(SnapshotScope.DEVELOPER_ONLY, roundTrip.scope)
        assertEquals(1, roundTrip.developerEntries.size)
        assertTrue(roundTrip.accounts.isEmpty())
    }

    @Test
    fun selectedItemsSnapshotRoundTrips() {
        val snapshot = VaultSnapshot(
            scope = SnapshotScope.SELECTED_ITEMS,
            accounts = listOf(SnapshotBuilder.account("acc-1", "GitHub", "alice")),
            developerEntries = listOf(SnapshotBuilder.genericSecret("g1")),
        )
        val roundTrip = json.decodeFromString(
            VaultSnapshot.serializer(),
            json.encodeToString(VaultSnapshot.serializer(), snapshot),
        )
        assertEquals(SnapshotScope.SELECTED_ITEMS, roundTrip.scope)
        assertEquals(1, roundTrip.accounts.size)
        assertEquals(1, roundTrip.developerEntries.size)
    }

    @Test
    fun packagePayloadRoundTrips() {
        val payload = VaultPackagePayload(
            logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
            packageId = "pkg-1",
            createdAt = "2024-01-01T00:00:00Z",
            source = PackageSourceMetadata(client = "android-app", appVersion = "1.0.0"),
            snapshot = VaultSnapshot(
                scope = SnapshotScope.AUTHENTICATOR_ONLY,
                accounts = listOf(SnapshotBuilder.account("acc-1", "GitHub", "alice")),
            ),
        )
        val roundTrip = json.decodeFromString(
            VaultPackagePayload.serializer(),
            json.encodeToString(VaultPackagePayload.serializer(), payload),
        )
        assertEquals(payload, roundTrip)
    }
}
