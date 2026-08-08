package com.rescueauth.v2.migration

/**
 * **Independent interoperability fixtures** for the otpauth-migration adapter
 * (Phase 4 P2 compatibility CR).
 *
 * These fixtures are **NOT** produced by this project's [ProtoFixture] builder
 * or by the project's MinimalProtobuf encoder. They come from two independent
 * sources:
 *
 * ## A. Real Google Authenticator v6.0 exports (synthetic test accounts)
 *
 * The four single-QR/multi-QR URIs below are the actual QR payloads exported
 * by **Google Authenticator v6.0** (`krissrex/google-authenticator-exporter`
 * `test-assets/test-qr-codes.json`, MIT license, generated 2024-06-11 with
 * `usingAppVersion: 6.0`). They contain only **disposable synthetic test
 * credentials** (`Hello!…`, `Test account 1/2`, `TOTPgenerator`, …) — no real
 * credential material.
 *
 * Decoding them with an independent protobuf implementation (protoc + Python
 * `google.protobuf`, see `tools/interop-fixture/`) proves the real wire format
 * uses **enum semantics**:
 *
 * ```
 * Algorithm  : 0=UNSPECIFIED, 1=SHA1, 2=SHA256, 3=SHA512, 4=MD5
 * DigitCount : 0=UNSPECIFIED, 1=SIX(6), 2=EIGHT(8)
 * OtpType    : 0=UNSPECIFIED, 1=HOTP, 2=TOTP
 * ```
 *
 * and that Google emits **padded standard Base64** percent-encoded into the
 * `data` query parameter (e.g. `%3D%3D` for `==`, `%2F` for `/`).
 *
 * ## B. protoc-generated fixture (independent protobuf implementation)
 *
 * [PROTOC_FIXTURE_*] is built with `protoc` 3.21.12 + the Python protobuf
 * runtime from the confirmed `MigrationPayload` schema (see
 * `tools/interop-fixture/google_auth_migration.proto`). It is **not** encoded
 * by this project's MinimalProtobuf / ProtoFixture.
 *
 * ## Usage
 *
 * `*_EXPECTED` constants lock the exact expected values the parser must
 * produce for each fixture (issuer / name / algorithm / digits / secret
 * Base32 / batch metadata). See [InteropFixtureTest].
 */
internal object InteropFixtures {

    // =====================================================================
    // A. Real Google Authenticator v6.0 export URIs (synthetic test accounts)
    // =====================================================================

    /** Single QR: 2 TOTP + 1 HOTP. batch_id=1862651516. */
    const val GA_TEST1_URI = "otpauth-migration://offline?data=CiIKCkhlbGwPId6tvugSDlRlc3QgYWNjb3VudCAxIAEoATACCiIKCgBlb" +
        "GxvId6tvu8SDlRlc3QgYWNjb3VudCAyIAEoATACCiMKCgBEjWxkLzvjHR8SDUNvdW50ZXIga2V5IDEgASgBMAE4ARA" +
        "BGAEgACj8nJf4Bg%3D%3D"

    /** Multi-QR part 1/2: 10 entries. batch_size=2, batch_index=0, batch_id=27091391. */
    const val GA_TEST2_QR1_URI = "otpauth-migration://offline?data=CiIKCkhlbGwPId6tvugSDlRlc3QgYWNjb3VudCAxIAEoATACCiIKCgBlb" +
        "GxvId6tvu8SDlRlc3QgYWNjb3VudCAyIAEoATACCiMKCgBEjWxkLzvjHR8SDUNvdW50ZXIga2V5IDEgASgBMAE4AQo" +
        "VCgoAAAAAAAAAAAAAEgEzIAEoATACChUKCghCEIQhCEIQhCESATQgASgBMAIKFQoKEIQhCEIQhCEIQhIBNSABKAEwA" +
        "goVCgoQhCEIQhCEIQhCEgE2IAEoATACChUKChjGMYxjGMYxjGMSATcgASgBMAIKFQoKIQhCEIQhCEIQhBIBOCABKAE" +
        "wAgoWCgspSlKUpSlKUpSlKRIBOSABKAEwAhABGAIgACi%2Fw%2FUM"

    /** Multi-QR part 2/2: 2 entries. batch_size=2, batch_index=1, batch_id=27091391. */
    const val GA_TEST2_QR2_URI = "otpauth-migration://offline?data=ChgKDDGMYxjGMYxjGMYxjBICMTAgASgBMAIKFgoKOc5znOc5znOc5xICM" +
        "TEgASgBMAIQARgCIAEov8P1DA%3D%3D"

    /** Single QR: SHA512 + 8 digits. batch_id=28672797. */
    const val GA_SHA512_8DIGIT_URI = "otpauth-migration://offline?data=CjoKFD1jwRTgK6xTGKA0gdTWaGMebxmTEg1UT1RQZ2VuZXJhdG9yGg1UT" +
        "1RQZ2VuZXJhdG9yIAMoAjACEAEYASAAKJ2G1g0%3D"

    // ---- Expected decoded values (from independent protoc decode) ----

    val GA_TEST1_EXPECTED = listOf(
        GoogleFixtureEntry(
            name = "Test account 1",
            issuer = null,
            algorithm = "SHA1",
            digits = 6,
            secretBase32 = "JBSWY3APEHPK3PXI",
            type = "TOTP",
        ),
        GoogleFixtureEntry(
            name = "Test account 2",
            issuer = null,
            algorithm = "SHA1",
            digits = 6,
            secretBase32 = "ABSWY3DPEHPK3PXP",
            type = "TOTP",
        ),
        GoogleFixtureEntry(
            name = "Counter key 1",
            issuer = null,
            algorithm = "SHA1",
            digits = 6,
            secretBase32 = "ABCI23DEF456GHI7",
            type = "HOTP",
        ),
    )

    val GA_TEST2_QR1_EXPECTED = listOf(
        GoogleFixtureEntry("Test account 1", null, "SHA1", 6, "JBSWY3APEHPK3PXI", "TOTP"),
        GoogleFixtureEntry("Test account 2", null, "SHA1", 6, "ABSWY3DPEHPK3PXP", "TOTP"),
        GoogleFixtureEntry("Counter key 1", null, "SHA1", 6, "ABCI23DEF456GHI7", "HOTP"),
        GoogleFixtureEntry("3", null, "SHA1", 6, "AAAAAAAAAAAAAAAA", "TOTP"),
        GoogleFixtureEntry("4", null, "SHA1", 6, "BBBBBBBBBBBBBBBB", "TOTP"),
        GoogleFixtureEntry("5", null, "SHA1", 6, "CCCCCCCCCCCCCCCC", "TOTP"),
        GoogleFixtureEntry("6", null, "SHA1", 6, "CCCCCCCCCCCCCCCC", "TOTP"),
        GoogleFixtureEntry("7", null, "SHA1", 6, "DDDDDDDDDDDDDDDD", "TOTP"),
        GoogleFixtureEntry("8", null, "SHA1", 6, "EEEEEEEEEEEEEEEE", "TOTP"),
        GoogleFixtureEntry("9", null, "SHA1", 6, "FFFFFFFFFFFFFFFFFE", "TOTP"),
    )

    val GA_TEST2_QR2_EXPECTED = listOf(
        GoogleFixtureEntry("10", null, "SHA1", 6, "GGGGGGGGGGGGGGGGGGGA", "TOTP"),
        GoogleFixtureEntry("11", null, "SHA1", 6, "HHHHHHHHHHHHHHHH", "TOTP"),
    )

    val GA_SHA512_8DIGIT_EXPECTED = listOf(
        GoogleFixtureEntry(
            name = "TOTPgenerator",
            issuer = "TOTPgenerator",
            algorithm = "SHA512",
            digits = 8,
            secretBase32 = "HVR4CFHAFOWFGGFAGSA5JVTIMMPG6GMT",
            type = "TOTP",
        ),
    )

    const val GA_TEST1_BATCH_ID = 1862651516
    const val GA_TEST1_BATCH_SIZE = 1
    const val GA_TEST1_BATCH_INDEX = 0

    const val GA_TEST2_BATCH_ID = 27091391
    const val GA_TEST2_BATCH_SIZE = 2

    // =====================================================================
    // B. protoc-generated fixture (independent protobuf implementation)
    // =====================================================================

    /**
     * Protobuf payload built with `protoc` 3.21.12 + Python `google.protobuf`
     * 4.21.12 from the confirmed `MigrationPayload` schema
     * (`tools/interop-fixture/google_auth_migration.proto`). Serialized bytes
     * (197), base64 **standard** (padded), with a 3-entry payload carrying
     * batch metadata **inside the protobuf** (batch_size=3, batch_index=1,
     * batch_id=424242).
     */
    const val PROTOC_FIXTURE_STANDARD_PADDED = "CjgKCkhlbGwPId6tvugSFWZpeHR1cmUtYUBleGFtcGxlLmNvbRoNRml4dHVyZUlzc3VlciABKAEwAgpCChQ9Y8EU4CusUxigNIHU1mhjHm8ZkxIVZml4dHVyZS1iQGV4YW1wbGUuY29tGg1GaXh0dXJlU2hhNTEyIAMoAjACCjsKCgBEjWxkLzvjHR8SGGZpeHR1cmUtaG90cEBleGFtcGxlLmNvbRoLRml4dHVyZUhvdHAgASgBMAE4BxABGAMgASiy8hk="

    /** Same payload, URL-safe no-padding form (accepted leniency). */
    const val PROTOC_FIXTURE_URLSAFE_NOPAD = "CjgKCkhlbGwPId6tvugSFWZpeHR1cmUtYUBleGFtcGxlLmNvbRoNRml4dHVyZUlzc3VlciABKAEwAgpCChQ9Y8EU4CusUxigNIHU1mhjHm8ZkxIVZml4dHVyZS1iQGV4YW1wbGUuY29tGg1GaXh0dXJlU2hhNTEyIAMoAjACCjsKCgBEjWxkLzvjHR8SGGZpeHR1cmUtaG90cEBleGFtcGxlLmNvbRoLRml4dHVyZUhvdHAgASgBMAE4BxABGAMgASiy8hk"

    /**
     * Alphabet-distinguishing fixture: a single SHA1/6/TOTP entry whose
     * **standard** Base64 form contains both `+` and `/` (and whose URL-safe
     * form differs by using `-`/`_`). Frozen from a protoc-generated payload
     * (secret `3698654ebf…f823`, name `H-w-`, issuer `I`).
     */
    const val ALPHABET_DISTINGUISHING_STANDARD = "CiUKFDaYZU6/UgCl+gk5uZ16HXsoK/gjEgRILXctGgFJIAEoATAC"
    const val ALPHABET_DISTINGUISHING_URLSAFE = "CiUKFDaYZU6_UgCl-gk5uZ16HXsoK_gjEgRILXctGgFJIAEoATAC"

    val ALPHABET_DISTINGUISHING_EXPECTED = listOf(
        GoogleFixtureEntry(
            name = "H-w-",
            issuer = "I",
            algorithm = "SHA1",
            digits = 6,
            secretBase32 = "G2MGKTV7KIAKL6QJHG4Z26Q5PMUCX6BD",
            type = "TOTP",
        ),
    )

    const val PROTOC_FIXTURE_BATCH_SIZE = 3
    const val PROTOC_FIXTURE_BATCH_INDEX = 1
    const val PROTOC_FIXTURE_BATCH_ID = 424242
    const val PROTOC_FIXTURE_VERSION = 1

    val PROTOC_FIXTURE_EXPECTED = listOf(
        GoogleFixtureEntry(
            name = "fixture-a@example.com",
            issuer = "FixtureIssuer",
            algorithm = "SHA1",
            digits = 6,
            secretBase32 = "JBSWY3APEHPK3PXI",
            type = "TOTP",
        ),
        GoogleFixtureEntry(
            name = "fixture-b@example.com",
            issuer = "FixtureSha512",
            algorithm = "SHA512",
            digits = 8,
            secretBase32 = "HVR4CFHAFOWFGGFAGSA5JVTIMMPG6GMT",
            type = "TOTP",
        ),
        GoogleFixtureEntry(
            name = "fixture-hotp@example.com",
            issuer = "FixtureHotp",
            algorithm = "SHA1",
            digits = 6,
            secretBase32 = "ABCI23DEF456GHI7",
            type = "HOTP",
        ),
    )
}

/** One decoded expected entry for an interoperability fixture. */
internal data class GoogleFixtureEntry(
    val name: String?,
    val issuer: String?,
    val algorithm: String,
    val digits: Int,
    val secretBase32: String,
    val type: String,
)
