package com.rescueauth.v2.export

import com.rescueauth.v2.export.codec.PayloadJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Capacity contract tests (PACKAGE_FORMAT.md §Capacity / ADR-0007 §Capacity).
 *
 * The logical validator and the byte-level codec must agree on how much a
 * package can carry: a validator-accepted payload MUST be encodable, and an
 * over-limit encode must fail explicitly (never OOM).
 */
class PackageCapacityTest {

    private fun payload(snapshot: VaultSnapshot) = VaultPackagePayload(
        logicalSchemaVersion = VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION,
        packageId = "pkg-capacity",
        createdAt = "2024-01-01T00:00:00Z",
        source = PackageSourceMetadata(client = "test", appVersion = "1.0.0"),
        snapshot = snapshot,
    )

    @Test
    fun `estimate is an upper bound of the actual serialized size`() {
        val snapshots = listOf(
            VaultSnapshot(scope = SnapshotScope.FULL_VAULT),
            VaultSnapshot(
                scope = SnapshotScope.AUTHENTICATOR_ONLY,
                accounts = (1..50).map { i ->
                    SnapshotBuilder.account(
                        "acc-$i", "Provider-$i", "user-$i",
                        totps = listOf(SnapshotBuilder.totp("totp-$i")),
                        recoverySets = listOf(
                            SnapshotBuilder.recoverySet(
                                "set-$i", "Codes",
                                codes = (1..10).map { SnapshotBuilder.recoveryCode("c$i-$it", "AAAA-BBBB-$i-$it") },
                            ),
                        ),
                    )
                },
            ),
            VaultSnapshot(
                scope = SnapshotScope.DEVELOPER_ONLY,
                developerEntries = (1..20).map { i ->
                    SnapshotBuilder.apiCredential("api-$i", serviceName = "svc-$i", apiKey = "k-$i", apiSecret = "s-$i")
                },
            ),
        )
        for (s in snapshots) {
            val p = payload(s)
            val estimated = PackageCapacity.estimateSerializedSize(p)
            val actual = PayloadJson.encode(p).size.toLong()
            assertTrue(
                "estimate $estimated must be >= actual $actual (it is a provable upper bound)",
                estimated >= actual,
            )
        }
    }

    @Test
    fun `single max-size keystore fits the package budget`() {
        // One real Android keystore, even at the per-asset cap, still fits
        // inside the 16 MiB package budget.
        val keystore = "A".repeat(PackageValidator.MAX_KEYSTORE_BASE64_LENGTH)
        val snapshot = VaultSnapshot(
            scope = SnapshotScope.DEVELOPER_ONLY,
            developerEntries = listOf(
                SnapshotBuilder.signingKey("sk-1", keystoreBase64 = keystore),
            ),
        )
        val p = payload(snapshot)
        val estimated = PackageCapacity.estimateSerializedSize(p)
        assertTrue(
            "single max keystore should fit budget: $estimated <= ${PackageValidator.MAX_SERIALIZED_PAYLOAD_BUDGET}",
            estimated <= PackageValidator.MAX_SERIALIZED_PAYLOAD_BUDGET,
        )
        // Validator accepts it (per-asset cap ok, total budget ok).
        PackageValidator.validate(p)
    }

    @Test
    fun `multiple large keystores exceed the package budget and are rejected`() {
        // Two 8 MiB keystores are individually under the per-asset cap but the
        // combined snapshot exceeds the package budget — validator must reject.
        val keystore = "A".repeat(8 * 1024 * 1024)
        val snapshot = VaultSnapshot(
            scope = SnapshotScope.DEVELOPER_ONLY,
            developerEntries = listOf(
                SnapshotBuilder.signingKey("sk-1", keystoreBase64 = keystore),
                SnapshotBuilder.signingKey("sk-2", keystoreBase64 = keystore),
            ),
        )
        val p = payload(snapshot)
        val estimated = PackageCapacity.estimateSerializedSize(p)
        assertTrue(
            "two 8 MiB keystores should exceed budget: $estimated > ${PackageValidator.MAX_SERIALIZED_PAYLOAD_BUDGET}",
            estimated > PackageValidator.MAX_SERIALIZED_PAYLOAD_BUDGET,
        )
        try {
            PackageValidator.validate(p)
            fail("expected ValidationException for over-budget payload")
        } catch (e: PackageValidator.ValidationException) {
            assertTrue(e.message!!.contains("capacity budget"))
        }
    }

    @Test
    fun `capacity constants are mutually consistent`() {
        // The chain package = header + ciphertext(plaintext + tag) must hold.
        assertEquals(
            PackageCapacity.MAX_PAYLOAD_CIPHERTEXT_SIZE,
            PackageCapacity.MAX_SERIALIZED_PAYLOAD_SIZE + PackageCapacity.XCHACHA20_TAG_BYTES,
        )
        assertTrue(PackageCapacity.MAX_PACKAGE_SIZE > PackageCapacity.MAX_PAYLOAD_CIPHERTEXT_SIZE)
        assertTrue(PackageCapacity.MAX_PAYLOAD_CIPHERTEXT_SIZE > PackageCapacity.MAX_SERIALIZED_PAYLOAD_SIZE)
    }
}
