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
 * otpauth-migration://offline?data=<base64url>&batch_size=<n>&batch_index=<i>&batch_id=<id>
 * ```
 *
 * - `data` is the base64url (no padding) encoding of the protobuf
 *   `MigrationPayload` (see [MinimalProtobuf]).
 * - Batch query params are optional; when present they override the protobuf
 *   batch fields for Google-Authenticator-style split exports.
 *
 * ## Status model
 *
 * Every entry is classified independently:
 *
 * - [MigrationEntryStatus.IMPORTABLE] — can be used by native v2 as-is;
 * - [MigrationEntryStatus.UNSUPPORTED] — known format but outside native v2
 *   semantics (HOTP, unsupported algorithm, digits outside 6..10,
 *   unsupported period);
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
     * Parses a full `otpauth-migration://` URI (with or without query batch
     * metadata) into a per-entry classification + batch metadata.
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

        val bytes = decodeData(data)

        // Batch metadata: query params (when present) win over protobuf fields.
        val wire = MinimalProtobuf.decodeMigrationPayload(bytes)
        val batchSize = params["batch_size"]?.toIntOrNull() ?: wire.batchSize
        val batchIndex = params["batch_index"]?.toIntOrNull() ?: wire.batchIndex
        val batchIdRaw = params["batch_id"]?.let { runCatching { it.toInt() }.getOrNull() } ?: wire.batchId

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
        // Google Authenticator algorithm enum:
        //   0 = MD5, 1 = SHA1, 2 = SHA256, 3 = SHA512, 4 = SHA224
        val algorithm = when (wire.algorithm) {
            1 -> "SHA1"
            2 -> "SHA256"
            3 -> "SHA512"
            else -> {
                if (wire.algorithm == 0 || wire.algorithm == 4) {
                    return MigrationTotpCandidate.unsupported(
                        reason = "unsupported-algorithm",
                        wireAlgorithm = wire.algorithm,
                        wireDigits = wire.digits,
                        wireType = wire.type,
                    )
                }
                return MigrationTotpCandidate.unsupported(
                    reason = "unsupported-algorithm",
                    wireAlgorithm = wire.algorithm,
                    wireDigits = wire.digits,
                    wireType = wire.type,
                )
            }
        }

        // Google Authenticator type enum: 0 = HOTP, 1 = TOTP
        if (wire.type != 1) {
            return MigrationTotpCandidate.unsupported(
                reason = if (wire.type == 0) "hotp-not-supported" else "unsupported-otp-type",
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

        val digits = if (wire.digits == 0) TotpCore.DEFAULT_DIGITS else wire.digits
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

    private fun decodeData(data: String): ByteArray {
        val normalized = data.trim()
        if (normalized.isEmpty()) throw MigrationParseException("empty-data")
        // base64url, optionally padded. Also accept standard base64 for leniency.
        val sb = StringBuilder(normalized.length + 2)
        for (c in normalized) {
            when {
                c == '-' -> sb.append('+')
                c == '_' -> sb.append('/')
                c == '+' || c == '/' || c.isLetterOrDigit() -> sb.append(c)
                else -> throw MigrationParseException("invalid-data-character")
            }
        }
        while (sb.length % 4 != 0) sb.append('=')
        return try {
            Base64.getDecoder().decode(sb.toString())
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
            // Base64url data is already URL-safe; decode other params leniently.
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
