package com.rescueauth.v2.migration

import com.rescueauth.v2.totp.TotpCore
import java.util.Base64

/**
 * Google Authenticator `otpauth-migration://` import adapter (Phase 4 P2).
 *
 * **IMPORT ONLY.** Rescue Auth Kit never exports this format.
 *
 * This is a pure-Kotlin, dependency-free adapter that converts an
 * `otpauth-migration://offline?data=...` URI into a list of native-v2 TOTP
 * import candidates. It is deliberately isolated from the rest of the codebase
 * so the whole migration feature can be deleted independently:
 *
 * - **no Camera / Compose / Room / Android Context / legacy-model deps**;
 * - the migration format is **never** mixed into [com.rescueauth.v2.totp.OtpauthParser];
 * - the resulting [MigrationTotpCandidate]s are **not** legacy / package
 *   types — they are ordinary native TOTP inputs that flow into the standard
 *   production repository import path.
 *
 * ## URI format
 *
 * ```
 * otpauth-migration://offline?data=<base64>
 * ```
 *
 * `data` is the Base64 encoding of the protobuf `MigrationPayload`
 * (see [MinimalProtobuf]). This adapter implements **only the verified real
 * Google Authenticator wire contract**:
 *
 * - `data` must be **standard** Base64 (RFC 4648 §4, `A–Z a–z 0–9 + /`) with
 *   **RFC 4648 `=` padding**, percent-encoded into the query string (real
 *   exports encode `+` as `%2B`, `/` as `%2F`, `=` as `%3D`). Anything else
 *   (Base64URL `-`/`_`, unpadded encodings, mixed alphabets) is rejected as
 *   a malformed migration payload — we do **not** widen the parser contract
 *   for third-party tooling.
 * - The **only** query parameter accepted is `data`. Any other parameter
 *   (including the non-protocol `batch_size` / `batch_index` / `batch_id`
 *   extensions) is rejected as `unknown-query-parameter` so the contract
 *   stays single and unambiguous:
 *
 *   ```
 *   URI → data → protobuf → batch metadata
 *   ```
 *
 *   Batch metadata is always read from the decoded `MigrationPayload`
 *   protobuf fields (`batch_size`/`batch_index`/`batch_id`), never from the
 *   query string.
 *
 * ## Wire semantics (verified against real Google Authenticator exports)
 *
 * The `OtpParameters` enum fields are **protobuf enums**, not raw integers
 * (confirmed independently by the Aegis `google_auth.proto`, ente auth
 * `googleauth.proto` and the Go `otpauth` migration decoder):
 *
 * ```
 * Algorithm  : 0 = UNSPECIFIED, 1 = SHA1, 2 = SHA256, 3 = SHA512, 4 = MD5
 * DigitCount : 0 = UNSPECIFIED, 1 = SIX (6),  2 = EIGHT (8)
 * OtpType    : 0 = UNSPECIFIED, 1 = HOTP,     2 = TOTP
 * ```
 *
 * Native v2 mapping (frozen v1 TOTP contract, digits 6..10 / period 1..120):
 *
 * - `Algorithm.UNSPECIFIED` → SHA1 (same default as every independent
 *   implementation); `SHA1/SHA256/SHA512` → mapped directly; `MD5` →
 *   UNSUPPORTED.
 * - `DigitCount.UNSPECIFIED` → 6; `SIX` → 6; `EIGHT` → 8; any other raw
 *   value is rejected as UNSUPPORTED (v2 supports 6..10 but the Google
 *   format only defines 6/8).
 * - `OtpType.UNSPECIFIED` → treated as TOTP (matches Google/Aegis/ente);
 *   `TOTP` → importable; `HOTP` → UNSUPPORTED.
 *
 * ## Status model
 *
 * Every entry is classified independently:
 *
 * - [MigrationEntryStatus.IMPORTABLE] — can be used by native v2 as-is;
 * - [MigrationEntryStatus.UNSUPPORTED] — known format but outside native v2
 *   semantics (HOTP, MD5, digits outside the Google-defined set);
 * - [MigrationEntryStatus.INVALID] — structurally malformed (missing/empty
 *   secret, undecodable secret).
 *
 * A malformed entry never fails the whole payload: it is reported per-entry so
 * valid entries are never silently dropped.
 *
 * ## Security
 *
 * No logging of the raw URI, the decoded payload, any secret bytes or the
 * generated Base32 secret. Errors carry stable reason tokens only.
 */
object MigrationPayloadParser {

    const val SCHEME = "otpauth-migration"
    const val HOST = "offline"

    /** Google Authenticator migration protobuf version we accept. */
    const val SUPPORTED_MIGRATION_VERSION = 1

    class MigrationParseException(val reason: String) : Exception("migration payload invalid ($reason)")

    /**
     * Parses a full `otpauth-migration://offline?data=<base64>` URI into a
     * per-entry classification + batch metadata.
     *
     * Batch metadata is read from the decoded `MigrationPayload` protobuf
     * fields (the only authoritative source). Any query parameter other than
     * `data` is rejected (`unknown-query-parameter`).
     *
     * @throws MigrationParseException when the URI structure, base64 payload
     *   or protobuf body is malformed (a single malformed *entry* is reported
     *   per-entry instead).
     */
    fun parseUri(uri: String): MigrationParseResult {
        val trimmed = uri.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) throw MigrationParseException("not-an-otpauth-migration-uri")
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        if (scheme != SCHEME) throw MigrationParseException("unsupported-scheme")

        val rest = trimmed.substring(schemeEnd + 3)
        val fragmentIdx = rest.indexOf('#')
        val withoutFragment = if (fragmentIdx >= 0) rest.substring(0, fragmentIdx) else rest

        val pathEnd = withoutFragment.indexOf('?')
        val path = if (pathEnd >= 0) withoutFragment.substring(0, pathEnd) else withoutFragment
        val query = if (pathEnd >= 0) withoutFragment.substring(pathEnd + 1) else ""

        if (!path.startsWith("/") && path != HOST) throw MigrationParseException("missing-host")
        val host = path.removePrefix("/").substringBefore('/')
        if (host != HOST) throw MigrationParseException("unsupported-host")

        val params = parseQuery(query)
        val data = params["data"] ?: throw MigrationParseException("missing-data")

        // Strict URI contract: the only query parameter this adapter accepts
        // is `data`. The non-protocol `batch_*` extensions are NOT part of the
        // real Google Authenticator wire format; rejecting them keeps the
        // parser contract single and unambiguous (URI → data → protobuf →
        // batch metadata) and prevents query params from overriding / falling
        // back to protobuf values.
        val unknownParams = params.keys - "data"
        if (unknownParams.isNotEmpty()) {
            throw MigrationParseException("unknown-query-parameter")
        }

        val bytes = decodeData(data)

        // Batch metadata: the single authoritative source is the decoded
        // MigrationPayload protobuf fields. Real Google exports never carry
        // batch metadata in the query string.
        val wire = MinimalProtobuf.decodeMigrationPayload(bytes)
        val batchSize = wire.batchSize
        val batchIndex = wire.batchIndex
        val batchIdRaw = wire.batchId

        val entries = wire.entries.map { classify(it) }

        return MigrationParseResult(
            entries = entries,
            batchSize = batchSize,
            batchIndex = batchIndex,
            batchId = batchIdRaw,
        )
    }

    // ------------------------------------------------------------------
    // Classification
    // ------------------------------------------------------------------

    private fun classify(wire: MigrationWireEntry): MigrationTotpCandidate {
        // Google Authenticator OtpParameters are protobuf enums (verified
        // against real v6.0 exports):
        //   Algorithm : 0 = UNSPECIFIED, 1 = SHA1, 2 = SHA256, 3 = SHA512, 4 = MD5
        //   DigitCount: 0 = UNSPECIFIED, 1 = SIX(6), 2 = EIGHT(8)
        //   OtpType   : 0 = UNSPECIFIED, 1 = HOTP, 2 = TOTP
        val algorithm = when (wire.algorithm) {
            0, 1 -> "SHA1"
            2 -> "SHA256"
            3 -> "SHA512"
            4 -> return MigrationTotpCandidate.unsupported(
                reason = "unsupported-algorithm",
                wireAlgorithm = wire.algorithm,
                wireDigits = wire.digits,
                wireType = wire.type,
            )
            else -> return MigrationTotpCandidate.unsupported(
                reason = "unsupported-algorithm",
                wireAlgorithm = wire.algorithm,
                wireDigits = wire.digits,
                wireType = wire.type,
            )
        }

        // OtpType: 0 = UNSPECIFIED, 1 = HOTP, 2 = TOTP. Google's own export
        // writes TOTP as 2 (Aegis/ente treat UNSPECIFIED as TOTP too).
        if (wire.type == 1) {
            return MigrationTotpCandidate.unsupported(
                reason = "hotp-not-supported",
                wireAlgorithm = wire.algorithm,
                wireDigits = wire.digits,
                wireType = wire.type,
            )
        }
        if (wire.type != 0 && wire.type != 2) {
            return MigrationTotpCandidate.unsupported(
                reason = "unsupported-otp-type",
                wireAlgorithm = wire.algorithm,
                wireDigits = wire.digits,
                wireType = wire.type,
            )
        }

        val secret = wire.secret
        if (secret.isEmpty()) {
            return MigrationTotpCandidate.invalid("missing-secret")
        }
        val secretBase32 = try {
            encodeBase32NoPadding(secret)
        } catch (e: Exception) {
            return MigrationTotpCandidate.invalid("invalid-secret")
        }
        if (!TotpCore.isValidBase32(secretBase32)) {
            return MigrationTotpCandidate.invalid("invalid-secret")
        }

        // DigitCount: 0 = UNSPECIFIED, 1 = SIX(6), 2 = EIGHT(8).
        // Native v2 supports digits 6..10 but the Google format only defines
        // 6 and 8; anything else is outside the format and rejected.
        val digits = when (wire.digits) {
            0, 1 -> TotpCore.DEFAULT_DIGITS
            2 -> 8
            else -> return MigrationTotpCandidate.unsupported(
                reason = "unsupported-digits",
                wireAlgorithm = wire.algorithm,
                wireDigits = wire.digits,
                wireType = wire.type,
            )
        }
        if (digits !in TotpCore.SUPPORTED_DIGITS) {
            return MigrationTotpCandidate.unsupported(
                reason = "unsupported-digits",
                wireAlgorithm = wire.algorithm,
                wireDigits = wire.digits,
                wireType = wire.type,
            )
        }

        // Google Authenticator uses a 30s period; native v2 supports 1..120.
        val period = TotpCore.DEFAULT_PERIOD_SECONDS

        val issuer = wire.issuer?.trim()?.takeIf { it.isNotEmpty() }
        val name = wire.name?.trim()?.takeIf { it.isNotEmpty() }

        return MigrationTotpCandidate.importable(
            secretBase32 = secretBase32,
            name = name,
            issuer = issuer,
            algorithm = algorithm,
            digits = digits,
            periodSeconds = period,
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Decodes the `data` query parameter into the raw protobuf payload.
     *
     * ## Final data decoding rule (frozen — strict Google Authenticator only)
     *
     * 1. The `data` value is **percent-decoded** first (URI query semantics).
     *    A real Google export encodes `+` as `%2B`, `/` as `%2F` and `=` as
     *    `%3D`; a percent-decoded `+`/`/`/`=` must never be treated as an
     *    invalid character.
     * 2. After percent-decoding, the value is interpreted as **standard**
     *    RFC 4648 §4 Base64 only: `A–Z a–z 0–9 + /`. This is the alphabet real
     *    Google Authenticator emits.
     * 3. **RFC 4648 `=` padding** is required (the strict decoder below
     *    rejects any length that is not a multiple of 4, i.e. missing
     *    padding, and any misplaced / over-long `=`).
     * 4. Anything outside that contract is **rejected as malformed**:
     *    - Base64URL alphabet (`-` / `_`) → `invalid-data-character`;
     *    - mixed standard/Base64URL alphabet → `invalid-data-character`;
     *    - unpadded (no-padding) forms → `malformed-base64`;
     *    - misplaced / over-long padding (`ab=c`, `aGVsbG8====`) →
     *      `malformed-base64`;
     *    - any other non-alphabet character (`!!!!`) →
     *      `invalid-data-character`.
     *
     * We deliberately do **not** widen the contract for third-party tools:
     * only the verified Google Authenticator wire form is accepted.
     */
    internal fun decodeData(data: String): ByteArray {
        val normalized = data.trim()
        if (normalized.isEmpty()) throw MigrationParseException("empty-data")
        // Standard RFC 4648 §4 alphabet only: A–Z a–z 0–9 + / =.
        for (c in normalized) {
            val inAlphabet = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') ||
                c == '+' || c == '/' || c == '='
            if (!inAlphabet) throw MigrationParseException("invalid-data-character")
        }
        // Real GA always emits RFC 4648 `=` padding, so the encoded length
        // must be a multiple of 4. This rejects unpadded (no-padding) forms
        // even when the bytes would otherwise decode (Java's strict decoder
        // tolerates valid unpadded quanta).
        if (normalized.length % 4 != 0) throw MigrationParseException("malformed-base64")
        // Java's strict RFC 4648 decoder: rejects misplaced / over-long `=`
        // and enforces a canonical bit-length.
        return try {
            Base64.getDecoder().decode(normalized)
        } catch (e: IllegalArgumentException) {
            throw MigrationParseException("malformed-base64")
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            if (key.isEmpty()) continue
            out.putIfAbsent(key, percentDecode(value))
        }
        return out
    }

    private fun percentDecode(input: String): String {
        if (!input.contains('%')) return input
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '%' && i + 2 < input.length) {
                val hi = input.getOrNull(i + 1)?.digitToIntOrNull(16)
                val lo = input.getOrNull(i + 2)?.digitToIntOrNull(16)
                if (hi != null && lo != null) {
                    bytes.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            bytes.write(c.code)
            i++
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    /** RFC 4648 base32 (no padding), uppercased — native v2 storage form. */
    internal fun encodeBase32NoPadding(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(alphabet[(buffer ushr bits) and 0x1f])
            }
        }
        if (bits > 0) {
            sb.append(alphabet[(buffer shl (5 - bits)) and 0x1f])
        }
        return sb.toString()
    }
}
