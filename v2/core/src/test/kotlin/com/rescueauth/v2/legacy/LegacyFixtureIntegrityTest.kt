package com.rescueauth.v2.legacy

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/**
 * Guard: the fixture copies used by tests must stay byte-identical to the
 * canonical frozen assets (v2/legacy-fixtures) and their manifest SHA-256.
 * Regenerating fixtures with a different salt/nonce breaks this contract.
 */
class LegacyFixtureIntegrityTest {

    private val manifestSha256 = mapOf(
        "schema1_normal" to "a4d8feda1c95af5e81c245979a3e9f7deedfa3b09c05a59f9a6fc386d0785480",
        "schema2_normal" to "4129e2d44808dee3763f8f6484ff98457517e51908886104d71ddc85a4910533",
        "schema3_normal" to "967b1a89499eabf2e4464156f9532b4a01610e55668c7a41beaa527b140dbbb7",
        "rfc4226_sha1_secret" to "a8d05fa6c1124be03d59424bf658eb6f70054b7a58eb6cb09de774d1d3ad4518",
        "wrong_password" to "5de720373c235aa58c8c30e0501869b027141d61868a086ecc7f457e8fe681c2",
        "tampered_ciphertext" to "4b7584533b1984f06e965a2a5cfc7db054a9bcef470d3887cd988701a2d1dd6e",
        "truncated_ciphertext" to "51d7041a96a9d6f307689a40508d99415e49f71d6a105cd2f40b256a875044ad",
        "wrong_mac" to "a696d1f0978e11a41c996756eb62be0a548570c19a9172d7bd62548f6e514850",
        "extreme_kdf_params" to "a93bf914d93e06fe59af718363b3daec346425fd2977e15f4685d787fb818227",
        // Phase 1 fix: additive fixtures (see docs/LEGACY_IMPORT.md §6.1)
        "schema1_same_issuer_multi_account" to "903910bcb3e860debe9981f301a191f3fb64f92f36c2bb14479e7277fa19348b",
        "schema1_invalid_totp_params" to "8b764df75e0cb7cb0dd4c35a3d42d51414f6d79a42a20b056c11508fcf3912b7",
        // Phase 5A CR: frozen v1.2.0 producer fixtures (tools/legacy_fixtures_frozen).
        // Two DIFFERENT encryptions of the SAME logical vault (same durable ids,
        // different random salt/nonce). DO NOT regenerate casually.
        "phase5a/frozen_v1_producer_schema3" to "eb8f03e64af5a9b416367b55043573bb16052db174b18f2c1c55d04eb172398a",
        "phase5a/frozen_v1_producer_schema3_alt_backup" to "82f182460ec72ca1ab92c29212e33c59adf8e7d79c1320a8a81982d280e145ad",
    )

    @Test
    fun `test fixture copies match frozen manifest sha256`() {
        for ((name, expected) in manifestSha256) {
            val bytes = javaClass.classLoader.getResourceAsStream("legacy-fixtures/$name.rakvault")
                ?.use { it.readBytes() }
                ?: error("missing $name")
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            assertEquals("fixture $name", expected, digest)
        }
    }
}
