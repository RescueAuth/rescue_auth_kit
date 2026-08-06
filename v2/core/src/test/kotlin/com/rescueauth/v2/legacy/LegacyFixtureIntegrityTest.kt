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
